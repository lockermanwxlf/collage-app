package com.collage.app.model

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Templates live in `files/templates` as `<id>.json` plus a `<id>.png` thumbnail, and the
 * autosaved in-progress collage in `files/session.json`. All methods block on disk or media
 * access, so call them off the main thread.
 */
class TemplateStore(private val context: Context) {
    private val dir = File(context.filesDir, "templates").apply { mkdirs() }
    private val sessionFile = File(context.filesDir, "session.json")

    fun thumbnailFile(id: String) = File(dir, "$id.png")

    /** All readable templates, newest first. Corrupt files are skipped. */
    fun loadAll(): List<Template> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { Template.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    /** Writes a new template and its thumbnail, rendered at [ratio], the collage's current output ratio. */
    fun save(
        name: String,
        aspect: AspectOption,
        customRatio: Ratio,
        ratio: Ratio,
        resolution: Int,
        customDurationMs: Long?,
        slots: List<ClipItem?>,
    ): Template {
        val t = Template(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            aspect = aspect,
            customRatio = customRatio,
            resolution = resolution,
            customDurationMs = customDurationMs,
            slots = slots.map { it?.copy(id = 0) },
        )
        // Saved media must stay readable after the app restarts.
        t.slots.forEach { c -> c?.let { persistUriPermission(context, it.uri) } }
        runCatching {
            thumbnailFile(t.id).outputStream().use { renderThumbnail(t.slots, ratio).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "${t.id}.json").writeTextAtomically(t.toJson().toString())
        return t
    }

    /** The in-progress collage, autosaved in the template format, or null if there is none. */
    fun loadSession(): Template? =
        runCatching { Template.fromJson(JSONObject(sessionFile.readText())) }.getOrNull()

    /** Saves the in-progress collage; null removes it. Safe to call from any thread. */
    @Synchronized
    fun writeSession(session: Template?) {
        if (session == null) sessionFile.delete() else sessionFile.writeTextAtomically(session.toJson().toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        thumbnailFile(id).delete()
    }

    /**
     * Rebuilds a saved clip for a new session, or returns null if its media is gone or no longer
     * readable. The media is probed again so the size and duration are current; the saved trim
     * is clamped to the media's real length.
     */
    fun restoreClip(saved: ClipItem, id: Long): ClipItem? {
        val probed = runCatching { MediaProbe.probe(context, saved.uri, id) }.getOrNull() ?: return null
        val restored = probed.copy(
            scale = saved.scale, offsetX = saved.offsetX, offsetY = saved.offsetY,
            volume = saved.volume, muted = saved.muted,
        )
        if (!probed.isVideo || !saved.isVideo) return restored
        val start = saved.trimStartMs.coerceIn(0, probed.durationMs - 1)
        val end = saved.trimEndMs.coerceIn(start + 1, probed.durationMs)
        return restored.copy(trimStartMs = start, trimEndMs = end)
    }

    /**
     * Gives back persisted read grants that no template uses. Grants are taken as soon as media
     * is picked (the session might be saved later), so discarded sessions leave some behind.
     * Pass the autosaved session along with the templates so its media stays readable.
     */
    fun releaseUnusedUriPermissions(templates: List<Template>) {
        val used = templates.flatMap { t -> t.slots.mapNotNull { it?.uri } }.toSet()
        val resolver = context.contentResolver
        resolver.persistedUriPermissions.filter { it.uri !in used }.forEach { p ->
            runCatching { resolver.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun renderThumbnail(slots: List<ClipItem?>, ratio: Ratio): Bitmap {
        val long = THUMB_LONG_SIDE.toFloat()
        val w = (if (ratio.value >= 1f) long else long * ratio.value).roundToInt().coerceAtLeast(1)
        val h = (if (ratio.value >= 1f) long / ratio.value else long).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        CollageLayout.cells(slots.size, w.toFloat(), h.toFloat()).zip(slots).forEach { (cell, clip) ->
            val cellRect = RectF(cell.left, cell.top, cell.right, cell.bottom)
            canvas.save()
            canvas.clipRect(cellRect)
            val frame = clip?.let { runCatching { loadFrame(it) }.getOrNull() }
            if (clip != null && frame != null) {
                val r = CollageLayout.mediaRect(cell, clip)
                canvas.drawBitmap(frame, null, RectF(r.left, r.top, r.right, r.bottom), paint)
                frame.recycle()
            } else {
                drawEmptyCell(canvas, cellRect)
            }
            canvas.restore()
        }
        return bmp
    }

    private fun drawEmptyCell(canvas: Canvas, r: RectF) {
        val inset = min(r.width(), r.height()) * 0.06f
        val fill = Paint().apply { color = 0xFF2A2A2A.toInt() }
        canvas.drawRect(r, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9E9E9E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = max(2f, inset / 3f)
        }
        canvas.drawRect(RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), line)
        val arm = min(r.width(), r.height()) * 0.12f
        canvas.drawLine(r.centerX() - arm, r.centerY(), r.centerX() + arm, r.centerY(), line)
        canvas.drawLine(r.centerX(), r.centerY() - arm, r.centerX(), r.centerY() + arm, line)
    }

    private fun loadFrame(clip: ClipItem): Bitmap? {
        if (!clip.isVideo) return MediaProbe.loadBitmap(context, clip.uri, THUMB_LONG_SIDE)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, clip.uri)
            return r.getFrameAtTime(clip.trimStartMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            r.release()
        }
    }

    companion object {
        private const val THUMB_LONG_SIDE = 480

        /** Keeps read access to [uri] across restarts. Fails quietly for uris that can't be persisted. */
        fun persistUriPermission(context: Context, uri: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
