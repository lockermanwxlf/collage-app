package com.collage.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.collage.app.export.CollageExporter
import com.collage.app.export.ExportCancelled
import com.collage.app.export.ExportSpec
import com.collage.app.model.AspectOption
import com.collage.app.model.ClipItem
import com.collage.app.model.CollageLayout
import com.collage.app.model.MediaProbe
import com.collage.app.model.Ratio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

class CollageViewModel(app: Application) : AndroidViewModel(app) {

    val items = mutableStateListOf<ClipItem>()
    var selectedId by mutableStateOf<Long?>(null)
    var loading by mutableIntStateOf(0)
        private set

    var aspect by mutableStateOf<AspectOption>(AspectOption.Screen)
    var screenRatio by mutableStateOf(Ratio(9, 16))
    var customRatio by mutableStateOf(Ratio(5, 4))
    var resolution by mutableIntStateOf(1080)

    /** Null means "auto": the length of the longest trimmed video. */
    var customDurationMs by mutableStateOf<Long?>(null)

    var exportState by mutableStateOf<ExportState>(ExportState.Idle)
        private set
    private var exportJob: Job? = null
    private var nextId = 1L

    val ratio: Ratio
        get() = when (val a = aspect) {
            AspectOption.Screen -> screenRatio
            AspectOption.Custom -> customRatio
            is AspectOption.Preset -> a.ratio
        }

    val autoDurationMs: Long
        get() = items.filter { it.isVideo }.maxOfOrNull { it.trimmedMs } ?: DEFAULT_IMAGE_ONLY_MS

    val durationMs: Long get() = customDurationMs ?: autoDurationMs

    val selected: ClipItem? get() = items.firstOrNull { it.id == selectedId }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ids = uris.map { nextId++ }
        loading += uris.size
        viewModelScope.launch {
            val probed = withContext(Dispatchers.IO) {
                uris.zip(ids).map { (uri, id) -> MediaProbe.probe(getApplication(), uri, id) }
            }
            loading -= uris.size
            val added = probed.filterNotNull()
            items.addAll(added)
            added.lastOrNull()?.let { selectedId = it.id }
        }
    }

    private inline fun update(id: Long, block: (ClipItem) -> ClipItem) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = block(items[i])
    }

    fun setTransform(id: Long, scale: Float, offsetX: Float, offsetY: Float) =
        update(id) { it.copy(scale = scale, offsetX = offsetX, offsetY = offsetY) }

    fun resetTransform(id: Long) = update(id) { it.copy(scale = 1f, offsetX = 0f, offsetY = 0f) }

    fun setTrim(id: Long, startMs: Long, endMs: Long) =
        update(id) { it.copy(trimStartMs = startMs, trimEndMs = endMs) }

    fun setVolume(id: Long, volume: Float) =
        update(id) { it.copy(volume = volume.coerceIn(0f, 1f), muted = false) }

    fun toggleMute(id: Long) = update(id) { it.copy(muted = !it.muted) }

    fun move(id: Long, delta: Int) {
        val i = items.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in items.indices) return
        val item = items.removeAt(i)
        items.add(j, item)
    }

    fun remove(id: Long) {
        items.removeAll { it.id == id }
        if (selectedId == id) selectedId = null
    }

    fun export() {
        if (items.isEmpty() || exportJob?.isActive == true) return
        val (w, h) = CollageLayout.outputSize(ratio, resolution)
        val spec = ExportSpec(items.toList(), w, h, durationMs)
        exportState = ExportState.Running(0f)
        exportJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            var created: Uri? = null
            try {
                val uri = withContext(Dispatchers.IO) {
                    val name = "collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Collage")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val out = resolver.insert(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                    ) ?: error("Could not create output file")
                    created = out
                    val ctx = currentCoroutineContext()
                    resolver.openFileDescriptor(out, "rw")!!.use { pfd ->
                        CollageExporter(app).export(
                            spec, pfd.fileDescriptor,
                            isCancelled = { !ctx.isActive },
                            onProgress = { p -> exportState = ExportState.Running(p) },
                        )
                    }
                    resolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    out
                }
                exportState = ExportState.Done(uri)
            } catch (e: Throwable) {
                created?.let { u -> runCatching { resolver.delete(u, null, null) } }
                exportState = if (e is ExportCancelled || e is kotlinx.coroutines.CancellationException) {
                    ExportState.Idle
                } else {
                    ExportState.Failed(e.message ?: e.toString())
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportState = ExportState.Idle
    }

    fun dismissExport() {
        exportState = ExportState.Idle
    }

    companion object {
        const val DEFAULT_IMAGE_ONLY_MS = 5_000L
    }
}
