package com.collage.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

object MediaProbe {

    /** Reads the type, display size and duration of a picked uri. Returns null if unreadable. */
    fun probe(context: Context, uri: Uri, id: Long): ClipItem? {
        val mime = context.contentResolver.getType(uri).orEmpty()
        return if (mime.startsWith("video/")) probeVideo(context, uri, id) else probeImage(context, uri, id)
    }

    private fun probeVideo(context: Context, uri: Uri, id: Long): ClipItem? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val swap = rot % 180 != 0
            ClipItem(
                id = id, uri = uri, type = MediaType.VIDEO,
                width = if (swap) h else w, height = if (swap) w else h,
                durationMs = dur, trimStartMs = 0, trimEndMs = dur, hasAudio = hasAudio,
            )
        } finally {
            r.release()
        }
    }.getOrNull()

    private fun probeImage(context: Context, uri: Uri, id: Long): ClipItem? = runCatching {
        // Decode a small copy: ImageDecoder applies EXIF orientation, so the result's
        // proportions are the true display proportions.
        var fullW = 0
        var fullH = 0
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            fullW = info.size.width; fullH = info.size.height
            d.setTargetSampleSize(max(1, max(fullW, fullH) / 256))
        }
        val swapped = (bmp.width > bmp.height) != (fullW > fullH) && fullW != fullH
        val item = ClipItem(
            id = id, uri = uri, type = MediaType.IMAGE,
            width = if (swapped) fullH else fullW, height = if (swapped) fullW else fullH,
        )
        bmp.recycle()
        item
    }.getOrNull()

    /** Decodes a software bitmap whose longest side is at most [maxDim]. */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxDim) {
                val f = maxDim.toFloat() / longest
                d.setTargetSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
            }
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
}
