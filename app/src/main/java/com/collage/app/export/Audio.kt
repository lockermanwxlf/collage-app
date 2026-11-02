package com.collage.app.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val OUT_SAMPLE_RATE = 48_000
const val OUT_CHANNELS = 2
/** ~5 ms fade at loop boundaries. */
private const val FADE_FRAMES = 240

/**
 * Decodes a video's audio track and produces it as 48 kHz stereo float, looping the trimmed range.
 * Each loop is exactly the trim length (padded with silence if the audio ends early) so the sound
 * stays in step with the looping video frames.
 */
class AudioSource(
    context: Context,
    uri: Uri,
    private val trimStartUs: Long,
    private val trimEndUs: Long,
    private val gain: Float,
) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()

    private var srcRate: Int
    private var srcChannels: Int
    private var pcmFloat = false

    /** Decoded source frames, stereo interleaved. */
    private var buf = FloatArray(32_768)
    private var bufFrames = 0
    /** Read position in source frames within [buf]. */
    private var pos = 0.0

    private val loopFrames = ((trimEndUs - trimStartUs) * OUT_SAMPLE_RATE / 1_000_000L).coerceAtLeast(1)
    private var producedInLoop = 0L
    private var inputEos = false
    private var decodeDone = false

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** Adds [frames] output frames (gain applied) into the stereo interleaved [out]. */
    fun mixInto(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (producedInLoop >= loopFrames) restart()
            // ensure() may compact the buffer and shift pos, so read the index after it.
            ensure(pos.toInt() + 2)
            val i0 = pos.toInt()
            var l = 0f
            var r = 0f
            if (i0 + 1 < bufFrames) {
                val f = (pos - i0).toFloat()
                l = buf[i0 * 2] + (buf[i0 * 2 + 2] - buf[i0 * 2]) * f
                r = buf[i0 * 2 + 1] + (buf[i0 * 2 + 3] - buf[i0 * 2 + 1]) * f
            } else if (i0 < bufFrames) {
                l = buf[i0 * 2]
                r = buf[i0 * 2 + 1]
            }
            val g = gain * edgeFade()
            out[i * 2] += l * g
            out[i * 2 + 1] += r * g
            pos += srcRate.toDouble() / OUT_SAMPLE_RATE
            producedInLoop++
        }
    }

    /**
     * Short ramp in/out at each loop seam (and where the audio runs out before the trim end) so the
     * jump back to the trim start doesn't click.
     */
    private fun edgeFade(): Float {
        var remaining = loopFrames - producedInLoop
        if (decodeDone) {
            val left = ((bufFrames - pos) * OUT_SAMPLE_RATE / srcRate).toLong()
            remaining = minOf(remaining, left)
        }
        val edge = minOf(producedInLoop, remaining).coerceAtLeast(0)
        return if (edge >= FADE_FRAMES) 1f else edge.toFloat() / FADE_FRAMES
    }

    private fun restart() {
        codec.flush()
        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        bufFrames = 0
        pos = 0.0
        producedInLoop = 0
        inputEos = false
        decodeDone = false
    }

    private fun ensure(frames: Int) {
        while (bufFrames < frames && !decodeDone) decodeMore()
    }

    private fun decodeMore() {
        // Drop frames already consumed so the buffer doesn't grow.
        val drop = pos.toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop * 2, buf, 0, (bufFrames - drop) * 2)
            bufFrames -= drop
            pos -= drop
        }
        var waits = 0
        while (true) {
            if (!inputEos) feedInput()
            val idx = codec.dequeueOutputBuffer(info, 5_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (++waits > 1000) error("Audio decoder timed out")
                }
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val pts = info.presentationTimeUs
                    if (info.size > 0 && pts < trimEndUs) {
                        append(codec.getOutputBuffer(idx)!!, pts)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (eos || pts >= trimEndUs) decodeDone = true
                    return
                }
            }
        }
    }

    private fun append(data: ByteBuffer, ptsUs: Long) {
        data.position(info.offset)
        data.limit(info.offset + info.size)
        data.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (pcmFloat) 4 else 2
        val total = info.size / (bytesPerSample * srcChannels)
        val skip = if (ptsUs < trimStartUs) {
            ((trimStartUs - ptsUs) * srcRate / 1_000_000L).toInt().coerceAtMost(total)
        } else 0
        val count = total - skip
        if (count <= 0) return
        if ((bufFrames + count) * 2 > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, (bufFrames + count) * 2))
        val shorts = if (pcmFloat) null else data.asShortBuffer()
        val floats = if (pcmFloat) data.asFloatBuffer() else null
        fun sample(frame: Int, ch: Int): Float {
            val i = frame * srcChannels + ch
            return floats?.get(i) ?: (shorts!!.get(i) / 32768f)
        }
        for (f in skip until total) {
            val l = sample(f, 0)
            val r = if (srcChannels > 1) sample(f, 1) else l
            buf[bufFrames * 2] = l
            buf[bufFrames * 2 + 1] = r
            bufFrames++
        }
    }

    private fun feedInput() {
        while (true) {
            val idx = codec.dequeueInputBuffer(0)
            if (idx < 0) return
            val size = extractor.readSampleData(codec.getInputBuffer(idx)!!, 0)
            if (size < 0 || extractor.sampleTime > trimEndUs) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEos = true
                return
            }
            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}

/** Sums all sources into 16-bit PCM. */
class AudioMixer(private val sources: List<AudioSource>) {
    private var scratch = FloatArray(0)

    fun render(out: ByteBuffer, frames: Int) {
        if (scratch.size < frames * OUT_CHANNELS) scratch = FloatArray(frames * OUT_CHANNELS)
        scratch.fill(0f, 0, frames * OUT_CHANNELS)
        sources.forEach { it.mixInto(scratch, frames) }
        out.order(ByteOrder.nativeOrder())
        for (i in 0 until frames * OUT_CHANNELS) {
            out.putShort((scratch[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
    }

    fun release() = sources.forEach { it.release() }
}
