package com.collage.app.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.collage.app.model.ClipItem
import com.collage.app.model.CollageLayout
import com.collage.app.model.MediaProbe
import com.collage.app.model.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live preview of the collage at the output aspect ratio. Each filled cell is pannable/zoomable;
 * tapping an empty cell selects it and calls [onFillEmpty].
 */
@Composable
fun CollageCanvas(
    slots: List<Slot>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onTransform: (id: Long, scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onFillEmpty: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cells = CollageLayout.cells(slots.size, w, h)
        slots.forEachIndexed { i, slot ->
            val cell = cells[i]
            val l = cell.left.roundToInt()
            val t = cell.top.roundToInt()
            val cw = cell.right.roundToInt() - l
            val ch = cell.bottom.roundToInt() - t
            val cellModifier = Modifier
                .offset { IntOffset(l, t) }
                .size(with(density) { cw.toDp() }, with(density) { ch.toDp() })
            androidx.compose.runtime.key(slot.id) {
                val item = slot.clip
                if (item == null) {
                    EmptyCell(
                        selected = slot.id == selectedId,
                        onClick = { onSelect(slot.id); onFillEmpty(slot.id) },
                        modifier = cellModifier,
                    )
                } else {
                    CollageCell(
                        item = item,
                        selected = item.id == selectedId,
                        cellW = cw,
                        cellH = ch,
                        onSelect = { onSelect(item.id) },
                        onTransform = { s, x, y -> onTransform(item.id, s, x, y) },
                        modifier = cellModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCell(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .background(Color(0xFF1C1C1C))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val outline = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8A8A8A)
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            drawRoundRect(
                color = outline,
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        }
        Icon(Icons.Default.Add, contentDescription = "Add media to empty slot", tint = outline, modifier = Modifier.size(32.dp))
    }
}

private data class Guides(val vertical: Boolean = false, val horizontal: Boolean = false, val unitZoom: Boolean = false)

@Composable
private fun CollageCell(
    item: ClipItem,
    selected: Boolean,
    cellW: Int,
    cellH: Int,
    onSelect: () -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(item)
    var guides by remember { mutableStateOf<Guides?>(null) }

    val cover = CollageLayout.coverScale(cellW.toFloat(), cellH.toFloat(), item.width, item.height)
    val dispW = (item.width * cover * item.scale).roundToInt().coerceAtLeast(1)
    val dispH = (item.height * cover * item.scale).roundToInt().coerceAtLeast(1)

    Box(
        modifier
            .clipToBounds()
            .background(Color(0xFF1C1C1C))
            .pointerInput(item.id, cellW, cellH) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onSelect()
                    val start = current
                    // "Raw" values follow the fingers; snapping is applied on top so it never sticks.
                    var rawS = start.scale
                    var rawX = start.offsetX
                    var rawY = start.offsetY
                    var last = Guides(vertical = rawX == 0f, horizontal = rawY == 0f, unitZoom = rawS == 1f)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom == 1f && pan == Offset.Zero) continue
                        val fw = cellW.toFloat()
                        val fh = cellH.toFloat()

                        val newS = (rawS * zoom).coerceIn(CollageLayout.MIN_SCALE, CollageLayout.MAX_SCALE)
                        val z = newS / rawS
                        val c = event.calculateCentroid(useCurrent = false)
                        if (c != Offset.Unspecified) {
                            // Zoom about the pinch centroid.
                            val fx = (c.x - fw / 2f) / fw
                            val fy = (c.y - fh / 2f) / fh
                            rawX = fx + (rawX - fx) * z
                            rawY = fy + (rawY - fy) * z
                        }
                        rawS = newS
                        rawX += pan.x / fw
                        rawY += pan.y / fh
                        val (rmx, rmy) = CollageLayout.maxOffset(fw, fh, start.width, start.height, rawS)
                        rawX = rawX.coerceIn(-rmx, rmx)
                        rawY = rawY.coerceIn(-rmy, rmy)

                        val unit = abs(rawS - 1f) < CollageLayout.SCALE_SNAP
                        val s = if (unit) 1f else rawS
                        val (mx, my) = CollageLayout.maxOffset(fw, fh, start.width, start.height, s)
                        var x = rawX.coerceIn(-mx, mx)
                        var y = rawY.coerceIn(-my, my)
                        val snapX = abs(x) < CollageLayout.OFFSET_SNAP
                        val snapY = abs(y) < CollageLayout.OFFSET_SNAP
                        if (snapX) x = 0f
                        if (snapY) y = 0f

                        val g = Guides(
                            vertical = snapX && mx > CollageLayout.OFFSET_SNAP,
                            horizontal = snapY && my > CollageLayout.OFFSET_SNAP,
                            unitZoom = unit,
                        )
                        if ((g.vertical && !last.vertical) || (g.horizontal && !last.horizontal) ||
                            (g.unitZoom && !last.unitZoom)
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        last = g
                        guides = g
                        onTransform(s, x, y)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    guides = null
                }
            },
    ) {
        // Media laid out at its displayed size (may exceed the cell), centred plus offset.
        Box(
            Modifier.layout { measurable, _ ->
                val p = measurable.measure(Constraints.fixed(dispW, dispH))
                layout(cellW, cellH) {
                    p.place(
                        ((cellW - dispW) / 2f + item.offsetX * cellW).roundToInt(),
                        ((cellH - dispH) / 2f + item.offsetY * cellH).roundToInt(),
                    )
                }
            },
        ) {
            if (item.isVideo) VideoPreview(item) else ImagePreview(item, maxOf(dispW, dispH))
        }

        guides?.let { g ->
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                val color = Color(0xFFFFD54F)
                if (g.vertical) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 3f, pathEffect = dash)
                if (g.horizontal) drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f, pathEffect = dash)
            }
            Text(
                text = if (g.unitZoom) "1×" else "%.2f×".format(current.scale),
                color = if (g.unitZoom) Color.Black else Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(if (g.unitZoom) Color(0xFFFFD54F) else Color(0x99000000), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (item.isVideo && item.effectiveVolume == 0f) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (item.hasAudio) "Muted" else "No audio",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun ImagePreview(item: ClipItem, displaySize: Int) {
    val context = LocalContext.current
    // Decode once per uri at a resolution that stays sharp when zoomed in somewhat.
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { MediaProbe.loadBitmap(context, item.uri, 2048) }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    } else {
        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun VideoPreview(item: ClipItem) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    LaunchedEffect(player, item.effectiveVolume) { player.volume = item.effectiveVolume }
    LaunchedEffect(player, item.trimStartMs, item.trimEndMs) {
        player.setMediaItem(clippedItem(item))
        player.prepare()
        player.play()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = Modifier.fillMaxSize(),
    )
}

fun clippedItem(item: ClipItem): MediaItem =
    MediaItem.Builder()
        .setUri(item.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(item.trimStartMs)
                .setEndPositionMs(item.trimEndMs)
                .build(),
        )
        .build()
