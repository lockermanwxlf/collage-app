package com.collage.app.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import com.collage.app.model.ClipItem
import com.collage.app.model.CollageLayout
import com.collage.app.model.FRect
import com.collage.app.model.MediaProbe
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class ExportSpec(
    val items: List<ClipItem>,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val fps: Int = 30,
)

class ExportCancelled : Exception("Export cancelled")

/**
 * Renders the collage with OpenGL into an H.264 encoder. Blocking: call from a background thread
 * and keep the whole call on that thread (the EGL context is bound to it).
 */
class CollageExporter(private val context: Context) {

    fun export(
        spec: ExportSpec,
        output: FileDescriptor,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val (width, height) = supportedSize(spec.width, spec.height)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * spec.fps * 0.15f).roundToInt().coerceIn(2_000_000, 40_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // Audible videos are mixed into a single AAC track; without any, the output is silent.
        val audioSources = mutableListOf<AudioSource>()
        spec.items.filter { it.effectiveVolume > 0f }.forEach { item ->
            runCatching {
                AudioSource(context, item.uri, item.trimStartMs * 1000, item.trimEndMs * 1000, item.effectiveVolume)
            }.onSuccess { audioSources += it }
        }
        val audioEncoder = if (audioSources.isEmpty()) null else createAudioEncoder()
        val mixer = AudioMixer(audioSources)

        val muxer = Muxer(MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), if (audioEncoder != null) 2 else 1)
        val callbackThread = HandlerThread("collage-frames").apply { start() }
        val egl = EglEncoderSurface(inputSurface)
        val renderer = QuadRenderer(width, height)
        val sources = mutableListOf<FrameSource>()
        val drainer = Drainer(encoder, muxer)
        val audio = audioEncoder?.let { AudioFeeder(it, mixer, Drainer(it, muxer)) }

        try {
            val cells = CollageLayout.cells(spec.items.size, width.toFloat(), height.toFloat())
            val handler = Handler(callbackThread.looper)
            spec.items.forEachIndexed { i, item ->
                sources += if (item.isVideo) {
                    VideoSource(context, item.uri, item.trimStartMs * 1000, item.trimEndMs * 1000, handler)
                } else {
                    val rect = CollageLayout.mediaRect(cells[i], item)
                    val maxDim = max(rect.width, rect.height).roundToInt().coerceIn(64, 3072)
                    val bmp = MediaProbe.loadBitmap(context, item.uri, maxDim)
                    ImageSource(bmp, renderer.flipY).also { bmp.recycle() }
                }
            }

            val totalFrames = max(1, ceil(spec.durationMs * spec.fps / 1000.0).toInt())
            for (frame in 0 until totalFrames) {
                if (isCancelled()) throw ExportCancelled()
                val tUs = frame * 1_000_000L / spec.fps
                renderer.clear()
                spec.items.forEachIndexed { i, item ->
                    val src = sources[i]
                    src.prepare(tUs)
                    drawItem(renderer, src, cells[i], item)
                }
                egl.swap(tUs * 1000)
                drainer.drain(endOfStream = false)
                audio?.feedUntil((frame + 1L) * OUT_SAMPLE_RATE / spec.fps)
                onProgress((frame + 1f) / totalFrames)
            }
            encoder.signalEndOfInputStream()
            drainer.drain(endOfStream = true)
            audio?.finish()
        } finally {
            sources.forEach { runCatching { it.release() } }
            runCatching { mixer.release() }
            audioEncoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { renderer.release() }
            runCatching { egl.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            inputSurface.release()
            muxer.release()
            callbackThread.quitSafely()
        }
    }

    private fun drawItem(renderer: QuadRenderer, src: FrameSource, cell: FRect, item: ClipItem) {
        val r = CollageLayout.mediaRect(cell, item)
        renderer.draw(
            src.texId, src.external, src.texMatrix,
            r.left, r.top, r.right, r.bottom,
            cell.left.roundToInt(), cell.top.roundToInt(), cell.right.roundToInt(), cell.bottom.roundToInt(),
        )
    }

    /** Shrinks the requested size until the device's AVC encoder supports it. */
    private fun supportedSize(w: Int, h: Int): Pair<Int, Int> {
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
        if (caps.isEmpty()) return w to h
        var f = 1f
        while (f > 0.2f) {
            val cw = CollageLayout.even(w * f)
            val ch = CollageLayout.even(h * f)
            if (caps.any { it.isSizeSupported(cw, ch) }) return cw to ch
            // Some encoders want 16-aligned sizes.
            val aw = (cw / 16) * 16
            val ah = (ch / 16) * 16
            if (caps.any { it.isSizeSupported(aw, ah) }) return aw to ah
            f -= 0.05f
        }
        return w to h
    }

    private fun createAudioEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_SAMPLE_RATE, OUT_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_FRAMES * OUT_CHANNELS * 2)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Feeds mixed PCM into the AAC encoder, keeping it level with the video timeline. */
    private class AudioFeeder(private val encoder: MediaCodec, private val mixer: AudioMixer, private val drainer: Drainer) {
        private var written = 0L

        fun feedUntil(frames: Long) {
            while (written < frames) {
                val idx = encoder.dequeueInputBuffer(10_000)
                if (idx < 0) {
                    drainer.drain(endOfStream = false)
                    continue
                }
                val buf = encoder.getInputBuffer(idx)!!
                buf.clear()
                val n = minOf(AUDIO_CHUNK_FRAMES.toLong(), frames - written, buf.capacity() / (OUT_CHANNELS * 2L)).toInt()
                mixer.render(buf, n)
                encoder.queueInputBuffer(idx, 0, n * OUT_CHANNELS * 2, written * 1_000_000L / OUT_SAMPLE_RATE, 0)
                written += n
                drainer.drain(endOfStream = false)
            }
        }

        fun finish() {
            while (true) {
                val idx = encoder.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    encoder.queueInputBuffer(idx, 0, 0, written * 1_000_000L / OUT_SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drainer.drain(endOfStream = false)
            }
            drainer.drain(endOfStream = true)
        }
    }

    /** Starts the muxer once every track's format is known; earlier samples are held back. */
    private class Muxer(private val muxer: MediaMuxer, private val expectedTracks: Int) {
        private var added = 0
        private var started = false
        private val pending = mutableListOf<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

        fun addTrack(format: MediaFormat): Int {
            val track = muxer.addTrack(format)
            if (++added == expectedTracks) {
                muxer.start()
                started = true
                pending.forEach { (t, b, i) -> muxer.writeSampleData(t, b, i) }
                pending.clear()
            }
            return track
        }

        fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started) {
                muxer.writeSampleData(track, data, info)
            } else {
                val copy = ByteBuffer.allocateDirect(info.size).put(data).apply { flip() }
                val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending += Triple(track, copy, i)
            }
        }

        fun release() {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private class Drainer(private val encoder: MediaCodec, private val muxer: Muxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1

        fun drain(endOfStream: Boolean) {
            var waits = 0
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++waits > 500) error("Encoder did not finish")
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(encoder.outputFormat)
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.write(track, buf, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    private companion object {
        const val AUDIO_CHUNK_FRAMES = 1024
    }
}
