package com.collage.app.model

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PaletteFolder(val id: String, val name: String)

/** A copy of picked media kept in app storage. [folderId] null means the item isn't in a folder. */
data class PaletteItem(
    val id: String,
    val name: String,
    val fileName: String,
    val type: MediaType,
    val durationMs: Long,
    val folderId: String?,
    val addedAt: Long,
) {
    val isVideo get() = type == MediaType.VIDEO
}

data class PaletteIndex(val folders: List<PaletteFolder>, val items: List<PaletteItem>)

/**
 * The palette keeps its own copies of media in `files/palette/media`, so they survive the
 * originals being deleted from the device. Copies are served through a FileProvider and used
 * like any other content uri. `files/palette/index.json` lists the folders and items.
 * All methods block on disk or media access, so call them off the main thread.
 */
class PaletteStore(private val context: Context) {
    private val dir = File(context.filesDir, "palette")
    private val mediaDir = File(dir, "media").apply { mkdirs() }
    private val thumbDir = File(dir, "thumbs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    fun uriOf(item: PaletteItem): Uri =
        FileProvider.getUriForFile(context, authority(context), File(mediaDir, item.fileName))

    fun thumbnailFile(item: PaletteItem) = File(thumbDir, "${item.id}.jpg")

    fun load(): PaletteIndex = runCatching {
        val o = JSONObject(indexFile.readText())
        val folders = o.getJSONArray("folders")
        val items = o.getJSONArray("items")
        PaletteIndex(
            folders = List(folders.length()) { i ->
                folders.getJSONObject(i).let { PaletteFolder(it.getString("id"), it.getString("name")) }
            },
            items = List(items.length()) { i -> itemFromJson(items.getJSONObject(i)) }
                .filter { File(mediaDir, it.fileName).exists() },
        )
    }.getOrElse { PaletteIndex(emptyList(), emptyList()) }

    fun write(index: PaletteIndex) {
        val o = JSONObject().apply {
            put("version", 1)
            put("folders", JSONArray().apply {
                index.folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            put("items", JSONArray().apply { index.items.forEach { put(itemToJson(it)) } })
        }
        indexFile.writeTextAtomically(o.toString())
    }

    /** Copies [uri] into the palette. Returns null if it can't be read or isn't a photo or video. */
    fun import(uri: Uri, folderId: String?): PaletteItem? {
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val video = mime.startsWith("video/")
        // The extension is how the FileProvider reports the copy's type, so it has to be right.
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "jpg"
        val id = UUID.randomUUID().toString()
        val file = File(mediaDir, "$id.$ext")
        val copied = runCatching {
            resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        }.isSuccess
        val probed = if (copied) MediaProbe.probe(context, FileProvider.getUriForFile(context, authority(context), file), 0) else null
        if (probed == null) {
            file.delete()
            return null
        }
        val now = System.currentTimeMillis()
        val item = PaletteItem(
            id = id,
            name = displayName(uri) ?: defaultName(probed.isVideo, now),
            fileName = file.name,
            type = probed.type,
            durationMs = probed.durationMs,
            folderId = folderId,
            addedAt = now,
        )
        runCatching { writeThumbnail(item, file) }
        return item
    }

    fun deleteFiles(item: PaletteItem) {
        File(mediaDir, item.fileName).delete()
        thumbnailFile(item).delete()
    }

    /** The original file name, unless it's just a number, as the photo picker reports it. */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()?.takeIf { name -> name.isNotBlank() && !name.all(Char::isDigit) }

    private fun defaultName(video: Boolean, time: Long) =
        (if (video) "Video " else "Photo ") + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(time))

    private fun writeThumbnail(item: PaletteItem, file: File) {
        val bmp = if (item.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIZE, THUMB_SIZE)
            } finally {
                r.release()
            }
        } else {
            MediaProbe.loadBitmap(context, Uri.fromFile(file), THUMB_SIZE)
        } ?: return
        thumbnailFile(item).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
    }

    private fun itemToJson(i: PaletteItem) = JSONObject().apply {
        put("id", i.id)
        put("name", i.name)
        put("fileName", i.fileName)
        put("type", i.type.name)
        put("durationMs", i.durationMs)
        i.folderId?.let { put("folderId", it) }
        put("addedAt", i.addedAt)
    }

    private fun itemFromJson(o: JSONObject) = PaletteItem(
        id = o.getString("id"),
        name = o.getString("name"),
        fileName = o.getString("fileName"),
        type = MediaType.valueOf(o.getString("type")),
        durationMs = o.getLong("durationMs"),
        folderId = if (o.has("folderId")) o.getString("folderId") else null,
        addedAt = o.getLong("addedAt"),
    )

    companion object {
        private const val THUMB_SIZE = 320

        fun authority(context: Context) = "${context.packageName}.palette"

        fun isPaletteUri(context: Context, uri: Uri) = uri.authority == authority(context)
    }
}
