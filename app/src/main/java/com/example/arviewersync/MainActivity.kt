package com.example.arviewersync

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.arviewersync.camera.CameraController
import com.example.arviewersync.sensor.OrientationProvider
import com.example.arviewersync.ui.ARViewerScreen
import com.example.arviewersync.ui.theme.ARViewerTheme
import com.example.arviewersync.viewer.ThreeBridge
import com.example.arviewersync.viewmodel.ARViewerViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var orientationProvider: OrientationProvider
    private lateinit var cameraController: CameraController
    private lateinit var bridge: ThreeBridge
    private lateinit var vm: ARViewerViewModel

    /** Shared sink: provider reads it to decide whether to mirror yaw;
     *  the VM writes it whenever the active camera changes. */
    private val isFrontCameraSink = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while the activity is visible (AR use-case).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        // Construct collaborators owned by this Activity.
        bridge = ThreeBridge()
        cameraController = CameraController(this)
        orientationProvider = OrientationProvider(
            context = this,
            mirrorYawWhenFrontCamera = { isFrontCameraSink.value }
        )
        // Bind sensor lifecycle to this Activity (register on resume, unregister on pause).
        lifecycle.addObserver(orientationProvider)

        vm = ARViewerViewModel(
            orientationProvider = orientationProvider,
            bridge = bridge,
            isFrontCameraSink = isFrontCameraSink
        )

        setContent {
            ARViewerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ARViewerScreen(
                        vm = vm,
                        cameraController = cameraController,
                        bridge = bridge
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.shutdown()
    }
}
