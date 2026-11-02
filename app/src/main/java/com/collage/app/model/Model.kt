package com.collage.app.model

import android.net.Uri
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MediaType { IMAGE, VIDEO }

/**
 * One input of the collage.
 *
 * [width]/[height] are the media's display dimensions (rotation already applied).
 * [scale] is relative to "cover" fit: 1x means the media exactly covers its cell.
 * [offsetX]/[offsetY] are the media centre's offset from the cell centre, as a fraction of the
 * cell's width/height, so the same values work for the on-screen preview and the export.
 * [volume] (0..1) and [muted] only apply to videos that have an audio track ([hasAudio]).
 */
data class ClipItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val width: Int,
    val height: Int,
    val durationMs: Long = 0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = durationMs,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val hasAudio: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
) {
    val isVideo get() = type == MediaType.VIDEO
    val effectiveVolume get() = if (!isVideo || !hasAudio || muted) 0f else volume
    val trimmedMs get() = (trimEndMs - trimStartMs).coerceAtLeast(1)
}

data class Ratio(val w: Int, val h: Int) {
    val value get() = w.toFloat() / h
    override fun toString() = "$w:$h"

    fun reduced(): Ratio {
        val g = gcd(w, h).coerceAtLeast(1)
        return Ratio(w / g, h / g)
    }

    companion object {
        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

sealed interface AspectOption {
    data object Screen : AspectOption
    data class Preset(val ratio: Ratio) : AspectOption
    data object Custom : AspectOption

    companion object {
        val presets = listOf(
            Ratio(9, 16), Ratio(16, 9), Ratio(1, 1), Ratio(4, 5),
            Ratio(3, 4), Ratio(4, 3), Ratio(2, 3), Ratio(3, 2), Ratio(21, 9),
        ).map { Preset(it) }
    }
}

data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

/** Geometry shared by the preview and the exporter so both produce identical results. */
object CollageLayout {
    const val COLUMNS = 3

    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 6f
    /** Distance from 1x within which the zoom snaps to exactly 1x. */
    const val SCALE_SNAP = 0.07f
    /** Distance from centre (fraction of cell size) within which position snaps to centre. */
    const val OFFSET_SNAP = 0.035f

    /**
     * Every input is one third of the output width. Rows split the height evenly; an incomplete
     * last row is centred horizontally.
     */
    fun cells(count: Int, width: Float, height: Float): List<FRect> {
        if (count <= 0) return emptyList()
        val rows = ceil(count / COLUMNS.toFloat()).toInt()
        val cw = width / COLUMNS
        val ch = height / rows
        return List(count) { i ->
            val row = i / COLUMNS
            val col = i % COLUMNS
            val inRow = min(COLUMNS, count - row * COLUMNS)
            val startX = (width - inRow * cw) / 2f
            val l = startX + col * cw
            val t = row * ch
            FRect(l, t, l + cw, t + ch)
        }
    }

    fun coverScale(cellW: Float, cellH: Float, mediaW: Int, mediaH: Int): Float =
        max(cellW / mediaW, cellH / mediaH)

    /**
     * Largest allowed |offset| on each axis. When the media is larger than the cell it may pan
     * until its edge meets the cell edge; when smaller it may move until it touches the cell edge.
     */
    fun maxOffset(cellW: Float, cellH: Float, mediaW: Int, mediaH: Int, scale: Float): Pair<Float, Float> {
        val cover = coverScale(cellW, cellH, mediaW, mediaH)
        val relW = mediaW * cover * scale / cellW
        val relH = mediaH * cover * scale / cellH
        return abs(relW - 1f) / 2f to abs(relH - 1f) / 2f
    }

    fun mediaRect(cell: FRect, item: ClipItem): FRect {
        val cover = coverScale(cell.width, cell.height, item.width, item.height)
        val dw = item.width * cover * item.scale
        val dh = item.height * cover * item.scale
        val cx = cell.centerX + item.offsetX * cell.width
        val cy = cell.centerY + item.offsetY * cell.height
        return FRect(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
    }

    /** Output pixel size for a ratio, with [shortSide] on the shorter edge and even dimensions. */
    fun outputSize(ratio: Ratio, shortSide: Int, maxLongSide: Int = 4096): Pair<Int, Int> {
        var w: Float
        var h: Float
        if (ratio.w <= ratio.h) {
            w = shortSide.toFloat(); h = shortSide * ratio.h / ratio.w.toFloat()
        } else {
            h = shortSide.toFloat(); w = shortSide * ratio.w / ratio.h.toFloat()
        }
        val long = max(w, h)
        if (long > maxLongSide) {
            val f = maxLongSide / long
            w *= f; h *= f
        }
        return even(w) to even(h)
    }

    fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
}
