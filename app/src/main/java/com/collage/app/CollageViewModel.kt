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
import com.collage.app.model.Slot
import com.collage.app.model.Template
import com.collage.app.model.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

enum class Screen { Home, Editor }

class CollageViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TemplateStore(app)
    val templates = mutableStateListOf<Template>()
    var screen by mutableStateOf(Screen.Home)

    /** The cells of the collage in layout order; empty ones are waiting for media. */
    val slots = mutableStateListOf<Slot>()
    var selectedId by mutableStateOf<Long?>(null)
    var loading by mutableIntStateOf(0)
        private set
    /** True while a template's media is being checked before its session opens. */
    var branching by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    /** A one-off note for the editor to show, such as the result of saving a template. */
    var message by mutableStateOf<String?>(null)

    var aspect by mutableStateOf<AspectOption>(AspectOption.Screen)
    var screenRatio by mutableStateOf(Ratio(9, 16))
    var customRatio by mutableStateOf(DEFAULT_CUSTOM_RATIO)
    var resolution by mutableIntStateOf(DEFAULT_RESOLUTION)

    /** Null means "auto": the length of the longest trimmed video. */
    var customDurationMs by mutableStateOf<Long?>(null)

    var exportState by mutableStateOf<ExportState>(ExportState.Idle)
        private set
    private var exportJob: Job? = null
    private var nextId = 1L

    init {
        viewModelScope.launch {
            // No session holds media yet, so grants that no template needs can go.
            val loaded = withContext(Dispatchers.IO) { store.loadAll().also(store::releaseUnusedUriPermissions) }
            templates.addAll(loaded)
        }
    }

    val ratio: Ratio
        get() = when (val a = aspect) {
            AspectOption.Screen -> screenRatio
            AspectOption.Custom -> customRatio
            is AspectOption.Preset -> a.ratio
        }

    /** The filled clips in layout order. */
    val items: List<ClipItem> get() = slots.mapNotNull { it.clip }

    val emptySlotCount: Int get() = slots.count { it.clip == null }

    val canExport: Boolean get() = slots.isNotEmpty() && emptySlotCount == 0

    val autoDurationMs: Long
        get() = items.filter { it.isVideo }.maxOfOrNull { it.trimmedMs } ?: DEFAULT_IMAGE_ONLY_MS

    val durationMs: Long get() = customDurationMs ?: autoDurationMs

    val selected: Slot? get() = slots.firstOrNull { it.id == selectedId }

    fun thumbnailFile(template: Template): File = store.thumbnailFile(template.id)

    /** Fills the empty slots in order, then adds a slot for each remaining uri. */
    fun addUris(uris: List<Uri>) {
        val empty = slots.filter { it.clip == null }.map { it.id }.take(uris.size)
        fill(uris, empty + List(uris.size - empty.size) { nextId++ })
    }

    fun fillSlot(id: Long, uri: Uri) = fill(listOf(uri), listOf(id))

    private fun fill(uris: List<Uri>, ids: List<Long>) {
        if (uris.isEmpty()) return
        loading += uris.size
        viewModelScope.launch {
            val app = getApplication<Application>()
            val probed = withContext(Dispatchers.IO) {
                uris.zip(ids).map { (uri, id) ->
                    // Taken now so the media still works after a restart if this becomes a template.
                    TemplateStore.persistUriPermission(app, uri)
                    MediaProbe.probe(app, uri, id)
                }
            }
            loading -= uris.size
            var last: Long? = null
            probed.forEach { clip ->
                if (clip == null) return@forEach
                val i = slots.indexOfFirst { it.id == clip.id }
                last = if (i >= 0 && slots[i].clip == null) {
                    slots[i] = Slot(clip.id, clip)
                    clip.id
                } else {
                    // The slot is new, or was removed or filled while probing: add the clip at the end.
                    val id = if (i >= 0) nextId++ else clip.id
                    slots.add(Slot(id, clip.copy(id = id)))
                    id
                }
            }
            last?.let { selectedId = it }
        }
    }

    fun addEmptySlot() {
        val id = nextId++
        slots.add(Slot(id))
        selectedId = id
    }

    private inline fun update(id: Long, block: (ClipItem) -> ClipItem) {
        val i = slots.indexOfFirst { it.id == id }
        val clip = slots.getOrNull(i)?.clip ?: return
        slots[i] = Slot(id, block(clip))
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
        val i = slots.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in slots.indices) return
        val slot = slots.removeAt(i)
        slots.add(j, slot)
    }

    /** Removes the slot, so the layout has one cell fewer. */
    fun remove(id: Long) {
        slots.removeAll { it.id == id }
        if (selectedId == id) selectedId = null
    }

    /** Takes the media out of the slot but keeps the cell. */
    fun clearSlot(id: Long) {
        val i = slots.indexOfFirst { it.id == id }
        if (i >= 0) slots[i] = Slot(id)
    }

    fun newCollage() {
        resetSession()
        screen = Screen.Editor
    }

    /**
     * Starts a new session from [template]: saved clips come back with their settings, and clips
     * whose media is gone become empty slots. The template itself is left as it is.
     */
    fun branchFrom(template: Template) {
        if (branching) return
        branching = true
        val ids = template.slots.map { nextId++ }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                template.slots.zip(ids).map { (saved, id) -> saved?.let { store.restoreClip(it, id) } }
            }
            resetSession()
            aspect = template.aspect
            customRatio = template.customRatio
            resolution = template.resolution
            customDurationMs = template.customDurationMs
            ids.zip(restored).forEach { (id, clip) -> slots.add(Slot(id, clip)) }
            selectedId = slots.firstOrNull { it.clip == null }?.id
            val missing = template.slots.zip(restored).count { (saved, clip) -> saved != null && clip == null }
            message = when (missing) {
                0 -> null
                1 -> "1 clip from this template couldn't be found, so its slot is empty"
                else -> "$missing clips from this template couldn't be found, so their slots are empty"
            }
            branching = false
            screen = Screen.Editor
        }
    }

    fun defaultTemplateName(): String =
        generateSequence(1) { it + 1 }.map { "Template $it" }.first { n -> templates.none { it.name == n } }

    fun saveTemplate(name: String) {
        if (slots.isEmpty() || saving) return
        saving = true
        val snapshot = slots.map { it.clip }
        val aspect = aspect
        val customRatio = customRatio
        val ratio = ratio
        val resolution = resolution
        val customDurationMs = customDurationMs
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    store.save(name, aspect, customRatio, ratio, resolution, customDurationMs, snapshot)
                }
            }
            saving = false
            result
                .onSuccess { templates.add(0, it); message = "Saved template “$name”" }
                .onFailure { message = "Couldn't save the template: ${it.message ?: it}" }
        }
    }

    fun deleteTemplate(template: Template) {
        templates.remove(template)
        viewModelScope.launch(Dispatchers.IO) { store.delete(template.id) }
    }

    private fun resetSession() {
        slots.clear()
        selectedId = null
        aspect = AspectOption.Screen
        customRatio = DEFAULT_CUSTOM_RATIO
        resolution = DEFAULT_RESOLUTION
        customDurationMs = null
        message = null
        exportState = ExportState.Idle
    }

    fun export() {
        if (!canExport || exportJob?.isActive == true) return
        val (w, h) = CollageLayout.outputSize(ratio, resolution)
        val spec = ExportSpec(items, w, h, durationMs)
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
        const val DEFAULT_RESOLUTION = 1080
        val DEFAULT_CUSTOM_RATIO = Ratio(5, 4)
    }
}
