package com.collage.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.collage.app.CollageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.io.File

/** A snackbar host that shows each of the view model's messages once. */
@Composable
fun rememberMessageSnackbar(vm: CollageViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        snapshotFlow { vm.message }.filterNotNull().collect { m ->
            vm.message = null
            host.showSnackbar(m)
        }
    }
    return host
}

/** An image file decoded off the main thread; shows nothing until it's loaded or if it can't be read. */
@Composable
fun FileImage(file: File, contentScale: ContentScale, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = modifier) }
}
