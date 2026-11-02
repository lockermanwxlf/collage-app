package com.collage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.collage.app.CollageViewModel
import com.collage.app.Screen
import com.collage.app.model.Template
import java.io.File

/** Pending action that would replace the collage currently being edited. */
private sealed interface Replace {
    data object New : Replace
    data class Branch(val template: Template) : Replace
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: CollageViewModel) {
    var confirmReplace by remember { mutableStateOf<Replace?>(null) }
    var confirmDelete by remember { mutableStateOf<Template?>(null) }
    val run = { r: Replace ->
        when (r) {
            Replace.New -> vm.newCollage()
            is Replace.Branch -> vm.branchFrom(r.template)
        }
    }
    val request = { r: Replace -> if (vm.slots.isNotEmpty()) confirmReplace = r else run(r) }

    val snackbar = rememberMessageSnackbar(vm)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Collage") },
                actions = {
                    TextButton(onClick = { vm.screen = Screen.Palette }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Palette, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Palette")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { request(Replace.New) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New collage")
                    }
                    if (vm.slots.isNotEmpty()) {
                        FilledTonalButton(onClick = { vm.screen = Screen.Editor }) {
                            Text("Continue editing")
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Templates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.templates.isEmpty()) "No templates yet. In the editor, tap the bookmark to save a collage, " +
                            "with some slots left empty, as a template."
                        else "Tap a template to start a new collage from it. The template stays as it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(vm.templates, key = { it.id }) { t ->
                TemplateCard(
                    template = t,
                    thumbnail = vm.thumbnailFile(t),
                    enabled = !vm.branching,
                    onOpen = { request(Replace.Branch(t)) },
                    onDelete = { confirmDelete = t },
                )
            }
        }
        if (vm.branching) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    confirmReplace?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text("Discard current collage?") },
            text = { Text("The collage you're editing will be replaced. Save it as a template first to keep it.") },
            confirmButton = { TextButton(onClick = { confirmReplace = null; run(r) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${t.name}”?") },
            text = { Text("Collages already started from this template aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.deleteTemplate(t) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TemplateCard(template: Template, thumbnail: File, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            FileImage(thumbnail, ContentScale.Fit, Modifier.fillMaxSize())
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(template.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = template.slots.size
                val empty = template.emptyCount
                Text(
                    "$n ${if (n == 1) "slot" else "slots"}" + if (empty > 0) " · $empty empty" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete template") }
        }
    }
}
