package com.xbertz.livecam

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Displays the live camera feed. The actual camera session lives in [StreamService] (so it
 * survives the screen turning off); this just hands the service a surface to render into
 * while it's on screen, and detaches it on dispose.
 */
@Composable
fun CameraPreview(
    onSurfaceProviderChanged: (Preview.SurfaceProvider?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onSurfaceProviderChanged(previewView.surfaceProvider)
        onDispose { onSurfaceProviderChanged(null) }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
