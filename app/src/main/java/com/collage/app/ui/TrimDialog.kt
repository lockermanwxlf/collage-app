package com.collage.app.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.collage.app.model.ClipItem
import kotlinx.coroutines.delay

private const val MIN_TRIM_MS = 200L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimDialog(item: ClipItem, onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val context = LocalContext.current
    var range by remember { mutableStateOf(item.trimStartMs.toFloat()..item.trimEndMs.toFloat()) }
    var scrubbing by remember { mutableStateOf(false) }
    var lastStart by remember { mutableStateOf(range.start) }
    var positionMs by remember { mutableStateOf(item.trimStartMs) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = item.effectiveVolume
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
            seekTo(item.trimStartMs)
            play()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // Loop playback inside the selected range while not scrubbing.
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                val pos = player.currentPosition
                positionMs = pos
                if (pos >= range.endInclusive || pos < range.start - 50 || player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(range.start.toLong())
                    player.play()
                }
            }
            delay(30)
        }
    }

    val aspect = item.width.toFloat() / item.height
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { TextureView(it).also { tv -> player.setVideoTextureView(tv) } },
                        modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f),
                    )
                }
                RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        val start = r.start
                        val end = maxOf(r.endInclusive, start + MIN_TRIM_MS).coerceAtMost(item.durationMs.toFloat())
                        val s = minOf(start, end - MIN_TRIM_MS).coerceAtLeast(0f)
                        // Show the frame under whichever thumb moved.
                        val moved = if (s != lastStart) s else end
                        lastStart = s
                        range = s..end
                        if (!scrubbing) {
                            scrubbing = true
                            player.pause()
                            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                        }
                        player.seekTo(moved.toLong())
                        positionMs = moved.toLong()
                    },
                    onValueChangeFinished = {
                        scrubbing = false
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(range.start.toLong())
                        player.play()
                    },
                    valueRange = 0f..item.durationMs.toFloat().coerceAtLeast(1f),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Start ${formatMs(range.start.toLong())}", style = MaterialTheme.typography.bodySmall)
                    Text(formatMs(positionMs), style = MaterialTheme.typography.bodySmall)
                    Text("End ${formatMs(range.endInclusive.toLong())}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Length ${formatMs((range.endInclusive - range.start).toLong())}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(range.start.toLong(), range.endInclusive.toLong()) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatMs(ms: Long): String {
    val totalTenths = ms / 100
    val tenths = totalTenths % 10
    val secs = (totalTenths / 10) % 60
    val mins = totalTenths / 600
    return "%d:%02d.%d".format(mins, secs, tenths)
}
