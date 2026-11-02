package com.collage.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.view.Surface

/** A texture that can be drawn for a given output time. */
sealed interface FrameSource {
    val texId: Int
    val external: Boolean
    val texMatrix: FloatArray

    /** Updates the texture to show the frame for [outputTimeUs]. Called on the GL thread. */
    fun prepare(outputTimeUs: Long) {}
    fun release()
}

class ImageSource(bitmap: Bitmap, flipY: FloatArray) : FrameSource {
    override val texId = QuadRenderer.createTexture(external = false)
    override val external = false
    override val texMatrix = flipY

    init {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        QuadRenderer.checkGl("texImage2D")
    }

    override fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
    }
}

/**
 * Decodes a trimmed video into a SurfaceTexture, looping the [trimStartUs, trimEndUs) range for
 * as long as frames are requested.
 */
class VideoSource(
    context: Context,
    uri: Uri,
    private val trimStartUs: Long,
    private val trimEndUs: Long,
    callbackHandler: Handler,
) : FrameSource {
    override val texId = QuadRenderer.createTexture(external = true)
    override val external = true
    override val texMatrix = FloatArray(16)

    private val surfaceTexture = SurfaceTexture(texId)
    private val surface = Surface(surfaceTexture)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()

    private val frameLock = Object()
    private var frameAvailable = false

    private var inputEos = false
    private var outputEos = false
    private var pendingIndex = -1
    private var pendingPtsUs = 0L
    private var renderedSinceSeek = false
    private var lastLocalUs = Long.MIN_VALUE

    init {
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }, callbackHandler)
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track in $uri")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        // The rotation key in the format makes the decoder rotate the output surface,
        // which then shows up in the SurfaceTexture transform matrix.
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        android.opengl.Matrix.setIdentityM(texMatrix, 0)
    }

    override fun prepare(outputTimeUs: Long) {
        val loopUs = (trimEndUs - trimStartUs).coerceAtLeast(1)
        val local = trimStartUs + outputTimeUs % loopUs
        if (local < lastLocalUs) restart()
        lastLocalUs = local
        advanceTo(local)
    }

    private fun restart() {
        codec.flush()
        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEos = false
        outputEos = false
        pendingIndex = -1
        renderedSinceSeek = false
    }

    /** Renders the latest frame with pts <= [localUs], holding the next decoded frame back. */
    private fun advanceTo(localUs: Long) {
        while (true) {
            if (pendingIndex < 0) {
                if (!nextOutput()) return
                pendingIndex = lastIndex
                pendingPtsUs = info.presentationTimeUs
                continue
            }
            if (pendingPtsUs > localUs) {
                // The very first frame after a seek may start after the requested time.
                if (!renderedSinceSeek) {
                    render(pendingIndex)
                    pendingIndex = -1
                }
                return
            }
            // Pending frame is due; look at the following frame to see if it is the latest due.
            if (!nextOutput()) {
                render(pendingIndex)
                pendingIndex = -1
                return
            }
            if (info.presentationTimeUs <= localUs) {
                codec.releaseOutputBuffer(pendingIndex, false)
                pendingIndex = lastIndex
                pendingPtsUs = info.presentationTimeUs
            } else {
                render(pendingIndex)
                pendingIndex = lastIndex
                pendingPtsUs = info.presentationTimeUs
                return
            }
        }
    }

    private var lastIndex = -1

    /** Dequeues the next output buffer into [lastIndex]/[info]. Returns false at end of stream. */
    private fun nextOutput(): Boolean {
        if (outputEos) return false
        var waits = 0
        while (true) {
            if (!inputEos) feedInput()
            val idx = codec.dequeueOutputBuffer(info, 5_000)
            when {
                idx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                        if (info.size == 0) {
                            codec.releaseOutputBuffer(idx, false)
                            return false
                        }
                    }
                    lastIndex = idx
                    return true
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (++waits > 1000) error("Video decoder timed out")
                }
            }
        }
    }

    private fun feedInput() {
        while (true) {
            val idx = codec.dequeueInputBuffer(0)
            if (idx < 0) return
            val buf = codec.getInputBuffer(idx)!!
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEos = true
                return
            }
            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun render(index: Int) {
        synchronized(frameLock) { frameAvailable = false }
        codec.releaseOutputBuffer(index, true)
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + 2500
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) error("Timed out waiting for decoded frame")
                frameLock.wait(left)
            }
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        renderedSinceSeek = true
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
    }
}
