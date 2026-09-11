package com.example.arviewersync.ui

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.arviewersync.camera.CameraController
import com.example.arviewersync.camera.CameraPreviewView
import com.example.arviewersync.viewer.ThreeBridge
import com.example.arviewersync.viewer.ThreeViewerWebView
import com.example.arviewersync.viewmodel.ARViewerViewModel

/**
 * Root overlay layout (AR-style):
 *   layer 0: full-screen Camera preview (background)
 *   layer 1: full-screen transparent Three.js WebView (overlay)
 *   layer 2: HUD chips (top)
 *   layer 3: control bar (bottom)
 *
 * Camera permission is requested on first launch if not granted.
 */
@Composable
fun ARViewerScreen(
    vm: ARViewerViewModel,
    cameraController: CameraController,
    bridge: ThreeBridge
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    val cameraFacing by vm.cameraFacing.collectAsState()
    val fov by vm.fov.collectAsState()

    // Camera permission state
    var hasCameraPermission by remember(cameraController) {
        mutableStateOf(cameraController.hasCameraPermission())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted && activity != null) {
            cameraController.start(activity, CameraSelector.LENS_FACING_BACK)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // (Re)start the camera whenever the facing flips OR permission was granted.
    LaunchedEffect(cameraFacing, hasCameraPermission) {
        if (hasCameraPermission && activity != null) {
            cameraController.start(activity, cameraFacing)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 0: live camera preview
        if (hasCameraPermission) {
            CameraPreviewView(controller = cameraController)
        }

        // Layer 1: transparent Three.js overlay
        ThreeViewerWebView(
            bridge = bridge,
            currentFov = fov,
            onFovChange = { newFov -> vm.setFov(newFov) }
        )

        // Layer 2: HUD on top
        HudOverlay(vm = vm)

        // Layer 3: bottom controls
        ControlBar(vm = vm)
    }
}
