package com.example.arviewersync.camera

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen CameraX PreviewView wrapped in a Compose [AndroidView].
 *
 * The [CameraController] is constructed once per composition using the current
 * Context and remembered. Pass the [lifecycleOwner] to start() from outside.
 */
@Composable
fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                controller.attachPreviewView(this)
            }
        },
        modifier = modifier
    )
}
