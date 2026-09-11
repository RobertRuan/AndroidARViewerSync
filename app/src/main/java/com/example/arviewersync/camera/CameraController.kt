package com.example.arviewersync.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Thin wrapper around CameraX that binds a [Preview] use case to a
 * [PreviewView] and supports switching between back / front cameras.
 *
 * Camera permission must be granted before calling [start]. Use
 * [hasCameraPermission] to check, and request via the Composable-level
 * rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()).
 */
class CameraController(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var currentFacing: Int = CameraSelector.LENS_FACING_BACK

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    /** Wire the controller to a [PreviewView] (call once from the Compose AndroidView factory). */
    fun attachPreviewView(view: PreviewView) {
        previewView = view
        view.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        // Set the mirror hint: only mirror for the front camera (selfie intuition).
        view.mirrorHint = PreviewView.MirrorHint.MIRROR_ONLY_FRONT_CAMERA
    }

    /**
     * Bind a Preview use case. Safe to call repeatedly (re-binds).
     * @param lifecycleOwner usually the Activity (LocalActivity.current in Compose)
     * @param facing CameraSelector.LENS_FACING_BACK or LENS_FACING_FRONT
     */
    fun start(lifecycleOwner: LifecycleOwner, facing: Int = currentFacing) {
        val pv = previewView ?: return
        if (!hasCameraPermission()) return

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val selector = CameraSelector.Builder()
                    .requireLensFacing(facing)
                    .build()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = pv.surfaceProvider
                }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
                currentFacing = facing
            } catch (_: Exception) {
                // SurfaceProvider may not be ready yet on first composition; retry on next frame.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Switch between back and front cameras. Returns the new facing. */
    fun switchCamera(lifecycleOwner: LifecycleOwner): Int {
        val next = if (currentFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        start(lifecycleOwner, next)
        return next
    }

    fun currentFacing(): Int = currentFacing

    fun shutdown() {
        cameraProvider?.unbindAll()
        executor.shutdown()
    }
}
