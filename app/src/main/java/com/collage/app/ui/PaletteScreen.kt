package com.collage.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.collage.app.CollageViewModel
import com.collage.app.Screen
import com.collage.app.model.PaletteFolder
import com.collage.app.model.PaletteItem

/** Which part of the palette is shown. */
private sealed interface FolderFilter {
    data object All : FolderFilter
    data object NoFolder : FolderFilter
    data class Folder(val id: String) : FolderFilter

    fun matches(item: PaletteItem) = when (this) {
        All -> true
        NoFolder -> item.folderId == null
        is Folder -> item.folderId == id
    }
}

/** Manages the palette: import media, organise it into folders, delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteScreen(vm: CollageViewModel) {
    var filter by remember { mutableStateOf<FolderFilter>(FolderFilter.All) }
    val folder = (filter as? FolderFilter.Folder)?.let { f -> vm.paletteFolders.firstOrNull { it.id == f.id } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.importToPalette(uris, folder?.id)
    }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<PaletteFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<PaletteFolder?>(null) }
    var openItem by remember { mutableStateOf<PaletteItem?>(null) }
    var movingItem by remember { mutableStateOf<PaletteItem?>(null) }
    var renamingItem by remember { mutableStateOf<PaletteItem?>(null) }
    var deletingItem by remember { mutableStateOf<PaletteItem?>(null) }
    val snackbar = rememberMessageSnackbar(vm)
    BackHandler { vm.screen = Screen.Home }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Palette") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Home }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Home")
                    }
                },
                actions = {
                    IconButton(onClick = { creatingFolder = true }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                    Button(
                        onClick = { importer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.paletteImporting > 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Copying ${vm.paletteImporting} into the palette…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            FolderChips(vm, filter) { filter = it }
            if (folder != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { renamingFolder = folder }) { Icon(Icons.Default.Edit, "Rename folder") }
                    IconButton(onClick = { deletingFolder = folder }) { Icon(Icons.Default.Delete, "Delete folder") }
                }
            }
            val shown = vm.paletteItems.filter(filter::matches)
            if (shown.isEmpty()) {
                Text(
                    when {
                        vm.paletteItems.isEmpty() -> "The palette keeps its own copy of media, so it stays available even if " +
                            "the original is deleted from your device. Import photos and videos, or save a clip from the editor."
                        folder != null -> "This folder is empty. Import media while it's open, or move items into it."
                        else -> "Nothing here."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            PaletteGrid(vm, shown, selected = emptySet(), onClick = { openItem = it }, modifier = Modifier.weight(1f))
        }
    }

    if (creatingFolder) {
        NameDialog("New folder", "", "Create", onDismiss = { creatingFolder = false }) {
            filter = FolderFilter.Folder(vm.createFolder(it).id)
            creatingFolder = false
        }
    }
    renamingFolder?.let { f ->
        NameDialog("Rename folder", f.name, "Rename", onDismiss = { renamingFolder = null }) {
            vm.renameFolder(f.id, it)
            renamingFolder = null
        }
    }
    deletingFolder?.let { f ->
        val n = vm.paletteItems.count { it.folderId == f.id }
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text("Delete folder “${f.name}”?") },
            text = {
                Text(if (n == 0) "The folder is empty." else "Its $n ${if (n == 1) "item stays" else "items stay"} in the palette, outside any folder.")
            },
            confirmButton = { TextButton(onClick = { vm.deleteFolder(f.id); deletingFolder = null; filter = FolderFilter.All }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text("Cancel") } },
        )
    }
    openItem?.let { item ->
        val folderName = vm.paletteFolders.firstOrNull { it.id == item.folderId }?.name ?: "No folder"
        AlertDialog(
            onDismissRequest = { openItem = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text((if (item.isVideo) "Video · ${formatMs(item.durationMs)}" else "Photo") + "\nFolder: $folderName") },
            confirmButton = { TextButton(onClick = { openItem = null; movingItem = item }) { Text("Move") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { openItem = null; deletingItem = item }) { Text("Delete") }
                    TextButton(onClick = { openItem = null; renamingItem = item }) { Text("Rename") }
                }
            },
        )
    }
    renamingItem?.let { item ->
        NameDialog("Rename", item.name, "Rename", onDismiss = { renamingItem = null }) {
            vm.renamePaletteItem(item, it)
            renamingItem = null
        }
    }
    movingItem?.let { item ->
        FolderPickerDialog(vm, "Move to folder", item.folderId, "Move", onDismiss = { movingItem = null }) {
            vm.moveToFolder(item, it)
            movingItem = null
        }
    }
    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("Delete “${item.name}” from the palette?") },
            text = { Text("The palette's copy is deleted. Templates and your current collage that use it will show an empty slot instead.") },
            confirmButton = { TextButton(onClick = { vm.deletePaletteItem(item); deletingItem = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingItem = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Picks media for the editor from the palette, or hands over to the device picker.
 * With [multiple], items are toggled and added together; otherwise a tap picks one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteSheet(
    vm: CollageViewModel,
    multiple: Boolean,
    onFromDevice: () -> Unit,
    onPick: (List<PaletteItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf<FolderFilter>(FolderFilter.All) }
    var picked by remember { mutableStateOf(listOf<PaletteItem>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Palette", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onFromDevice) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("From device")
                }
            }
            FolderChips(vm, filter) { filter = it }
            PaletteGrid(
                vm,
                vm.paletteItems.filter(filter::matches),
                selected = picked.map { it.id }.toSet(),
                onClick = { item ->
                    when {
                        !multiple -> onPick(listOf(item))
                        picked.any { it.id == item.id } -> picked = picked.filter { it.id != item.id }
                        else -> picked = picked + item
                    }
                },
                modifier = Modifier.heightIn(max = 420.dp),
            )
            if (multiple) {
                Button(
                    onClick = { onPick(picked) },
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp),
                ) { Text(if (picked.isEmpty()) "Add" else "Add (${picked.size})") }
            }
        }
    }
}

@Composable
private fun FolderChips(vm: CollageViewModel, filter: FolderFilter, onFilter: (FolderFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = filter == FolderFilter.All, onClick = { onFilter(FolderFilter.All) }, label = { Text("All") })
        if (vm.paletteFolders.isNotEmpty()) {
            FilterChip(
                selected = filter == FolderFilter.NoFolder,
                onClick = { onFilter(FolderFilter.NoFolder) },
                label = { Text("No folder") },
            )
        }
        vm.paletteFolders.forEach { f ->
            val selected = filter == FolderFilter.Folder(f.id)
            FilterChip(selected = selected, onClick = { onFilter(FolderFilter.Folder(f.id)) }, label = { Text(f.name) })
        }
    }
}

@Composable
private fun PaletteGrid(
    vm: CollageViewModel,
    items: List<PaletteItem>,
    selected: Set<String>,
    onClick: (PaletteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val isSelected = item.id in selected
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable { onClick(item) },
                ) {
                    FileImage(vm.thumbnailFile(item), ContentScale.Crop, Modifier.fillMaxSize())
                    if (item.isVideo) {
                        Text(
                            formatMs(item.durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color(0x99000000), RoundedCornerShape(50))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (isSelected) {
                        Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
                        Icon(
                            Icons.Default.CheckCircle, "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, RoundedCornerShape(50)),
                        )
                    }
                }
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Chooses a folder, or no folder, with the option of creating one by typing a name. */
@Composable
fun FolderPickerDialog(
    vm: CollageViewModel,
    title: String,
    initial: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (folderId: String?) -> Unit,
) {
    var choice by remember { mutableStateOf(initial) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<Pair<String?, String>>(null to "No folder") + vm.paletteFolders.map { it.id to it.name }).forEach { (id, name) ->
                    val selected = choice == id && newName.isBlank()
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected, onClick = { choice = id; newName = "" }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
                        Text(name)
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(40) },
                    label = { Text("Or a new folder") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (newName.isNotBlank()) vm.createFolder(newName).id else choice) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
