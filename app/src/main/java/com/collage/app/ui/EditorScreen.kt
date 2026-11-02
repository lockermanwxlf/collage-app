package com.collage.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.collage.app.CollageViewModel
import com.collage.app.ExportState
import com.collage.app.model.AspectOption
import com.collage.app.model.ClipItem
import com.collage.app.model.CollageLayout
import com.collage.app.model.Ratio
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: CollageViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.addUris(uris)
    }
    var showTrim by remember { mutableStateOf(false) }
    var showDuration by remember { mutableStateOf(false) }
    var showCustomRatio by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collage") },
                actions = {
                    ResolutionMenu(vm.resolution) { vm.resolution = it }
                    Button(
                        onClick = { vm.export() },
                        enabled = vm.items.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AspectBar(vm, onCustom = { showCustomRatio = true })

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = vm.ratio.value
                val boxRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
                val density = LocalDensity.current
                val (wPx, hPx) = if (ratio > boxRatio) {
                    constraints.maxWidth.toFloat() to constraints.maxWidth / ratio
                } else {
                    constraints.maxHeight * ratio to constraints.maxHeight.toFloat()
                }
                val canvasModifier = with(density) { Modifier.size(wPx.toDp(), hPx.toDp()) }
                CollageCanvas(
                    items = vm.items,
                    selectedId = vm.selectedId,
                    onSelect = { vm.selectedId = it },
                    onTransform = vm::setTransform,
                    modifier = canvasModifier,
                )
                if (vm.items.isEmpty()) {
                    Text(
                        if (vm.loading > 0) "Loading…" else "Add photos or videos to start",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    SelectionTools(vm, onTrim = { showTrim = true })
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add media")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showDuration = true }) {
                            Icon(Icons.Default.Timer, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text((if (vm.customDurationMs == null) "Auto · " else "") + formatMs(vm.durationMs))
                        }
                    }
                    val (ow, oh) = CollageLayout.outputSize(vm.ratio, vm.resolution)
                    Text(
                        "${vm.items.size} inputs · $ow×$oh · 30 fps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val selected = vm.selected
    if (showTrim && selected != null && selected.isVideo) {
        TrimDialog(
            item = selected,
            onDismiss = { showTrim = false },
            onConfirm = { s, e -> vm.setTrim(selected.id, s, e); showTrim = false },
        )
    }
    if (showDuration) {
        DurationDialog(
            autoMs = vm.autoDurationMs,
            customMs = vm.customDurationMs,
            onDismiss = { showDuration = false },
            onConfirm = { vm.customDurationMs = it; showDuration = false },
        )
    }
    if (showCustomRatio) {
        CustomRatioDialog(
            initial = vm.customRatio,
            onDismiss = { showCustomRatio = false },
            onConfirm = { vm.customRatio = it; vm.aspect = AspectOption.Custom; showCustomRatio = false },
        )
    }
    ExportDialog(vm)
}

@Composable
private fun AspectBar(vm: CollageViewModel, onCustom: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = vm.aspect == AspectOption.Screen,
            onClick = { vm.aspect = AspectOption.Screen },
            label = { Text("Screen ${vm.screenRatio.reduced()}") },
        )
        AspectOption.presets.forEach { p ->
            FilterChip(
                selected = vm.aspect == p,
                onClick = { vm.aspect = p },
                label = { Text(p.ratio.toString()) },
            )
        }
        FilterChip(
            selected = vm.aspect == AspectOption.Custom,
            onClick = onCustom,
            label = { Text(if (vm.aspect == AspectOption.Custom) "Custom ${vm.customRatio}" else "Custom…") },
        )
    }
}

@Composable
private fun SelectionTools(vm: CollageViewModel, onTrim: () -> Unit) {
    val item = vm.selected ?: return
    val index = vm.items.indexOf(item)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${index + 1} ${if (item.isVideo) "Video " + formatMs(item.trimmedMs) else "Image"} · %.2f×".format(item.scale),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { vm.move(item.id, -1) }, enabled = index > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move earlier")
        }
        IconButton(onClick = { vm.move(item.id, 1) }, enabled = index < vm.items.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move later")
        }
        IconButton(onClick = { vm.resetTransform(item.id) }) {
            Icon(Icons.Default.FilterCenterFocus, "Reset position and zoom")
        }
        if (item.isVideo) {
            IconButton(onClick = onTrim) { Icon(Icons.Default.ContentCut, "Trim") }
        }
        IconButton(onClick = { vm.remove(item.id) }) { Icon(Icons.Default.Delete, "Remove") }
    }
    if (item.isVideo) VolumeRow(vm, item)
}

@Composable
private fun VolumeRow(vm: CollageViewModel, item: ClipItem) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!item.hasAudio) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff, null,
                Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "This video has no audio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        IconButton(onClick = { vm.toggleMute(item.id) }) {
            Icon(
                when {
                    item.muted || item.volume == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                    item.volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                if (item.muted) "Unmute" else "Mute",
            )
        }
        Slider(
            value = if (item.muted) 0f else item.volume,
            onValueChange = { vm.setVolume(item.id, it) },
            modifier = Modifier.weight(1f),
        )
        Text(
            if (item.muted) "Muted" else "${(item.volume * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(56.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun ResolutionMenu(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("${value}p") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(720, 1080, 1440, 2160).forEach { r ->
                DropdownMenuItem(text = { Text("${r}p") }, onClick = { onChange(r); open = false })
            }
        }
    }
}

@Composable
private fun DurationDialog(autoMs: Long, customMs: Long?, onDismiss: () -> Unit, onConfirm: (Long?) -> Unit) {
    var auto by remember { mutableStateOf(customMs == null) }
    var seconds by remember { mutableStateOf(((customMs ?: autoMs) / 1000f).coerceIn(1f, 120f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Output length") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto")
                        Text(
                            "Longest trimmed video (${formatMs(autoMs)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                if (!auto) {
                    Text("%.1f s".format(seconds))
                    Slider(value = seconds, onValueChange = { seconds = (it * 2).roundToLong() / 2f }, valueRange = 1f..120f)
                }
                Text(
                    "Shorter videos loop until the collage ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (auto) null else (seconds * 1000).roundToLong()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CustomRatioDialog(initial: Ratio, onDismiss: () -> Unit, onConfirm: (Ratio) -> Unit) {
    var w by remember { mutableStateOf(initial.w.toString()) }
    var h by remember { mutableStateOf(initial.h.toString()) }
    val wi = w.toIntOrNull()?.takeIf { it in 1..10000 }
    val hi = h.toIntOrNull()?.takeIf { it in 1..10000 }
    val valid = wi != null && hi != null && wi.toFloat() / hi in 0.1f..10f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom aspect ratio") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = w, onValueChange = { w = it.filter(Char::isDigit).take(5) },
                    label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(":")
                OutlinedTextField(
                    value = h, onValueChange = { h = it.filter(Char::isDigit).take(5) },
                    label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(Ratio(wi!!, hi!!)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportDialog(vm: CollageViewModel) {
    val context = LocalContext.current
    when (val s = vm.exportState) {
        ExportState.Idle -> {}
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()}%")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text("Cancel") } },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("Saved") },
            text = { Text("Your collage was saved to Movies/Collage.") },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, s.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share collage"))
                }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(s.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(view) }
                    }) { Text("Open") }
                    TextButton(onClick = vm::dismissExport) { Text("Close") }
                }
            },
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("Export failed") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::dismissExport) { Text("OK") } },
        )
    }
}
