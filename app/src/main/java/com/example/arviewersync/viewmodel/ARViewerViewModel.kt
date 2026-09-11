package com.example.arviewersync.viewmodel

import androidx.camera.core.CameraSelector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arviewersync.sensor.OrientationEuler
import com.example.arviewersync.sensor.OrientationProvider
import com.example.arviewersync.viewer.ThreeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Holds UI state and orchestrates the data flow:
 *
 *   OrientationProvider -> ViewModel -> ThreeBridge (60 Hz JS push)
 *
 * The ViewModel is created in [MainActivity] and outlives configuration
 * changes (manifest configChanges also suppresses Activity recreation).
 */
class ARViewerViewModel(
    private val orientationProvider: OrientationProvider,
    private val bridge: ThreeBridge,
    private val isFrontCameraSink: MutableStateFlow<Boolean>
) : ViewModel() {

    private val _cameraFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val cameraFacing: StateFlow<Int> = _cameraFacing.asStateFlow()

    init {
        isFrontCameraSink.value = false
    }

    private val _rollCompensationEnabled = MutableStateFlow(true)
    val rollCompensationEnabled: StateFlow<Boolean> = _rollCompensationEnabled.asStateFlow()

    private val _fov = MutableStateFlow(75f)
    val fov: StateFlow<Float> = _fov.asStateFlow()

    /** Latest smoothed orientation for the HUD. */
    val orientation: StateFlow<OrientationEuler?> =
        orientationProvider.orientation.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val bridgeReady: StateFlow<Boolean> = bridge.ready
    val fps: StateFlow<Int> = bridge.fps
    val modelUrl: StateFlow<String?> = bridge.modelUrl
    val lastError: StateFlow<String?> = bridge.lastError

    init {
        // Pipe sensor -> JS bridge. Use distinctUntilChanged to drop no-op
        // updates; the provider already throttles, but this is a cheap guard.
        orientationProvider.orientation
            .distinctUntilChanged()
            .onEach { o ->
                if (o != null && bridge.ready.value) {
                    // When roll compensation is ON, send 0 as the roll; yaw & pitch always follow.
                    val roll = if (_rollCompensationEnabled.value) 0f else o.rollDeg
                    bridge.pushOrientation(o.yawDeg, o.pitchDeg, roll, _rollCompensationEnabled.value)
                }
            }
            .launchIn(viewModelScope)
    }

    /** Mirror-yaw predicate the provider uses to flip yaw for the front camera. */
    fun isFrontCameraActive(): Boolean = _cameraFacing.value == CameraSelector.LENS_FACING_FRONT

    fun flipCamera() {
        _cameraFacing.value =
            if (_cameraFacing.value == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT
            else
                CameraSelector.LENS_FACING_BACK
        // Keep the provider's yaw-mirror logic in sync with the active camera.
        isFrontCameraSink.value = (_cameraFacing.value == CameraSelector.LENS_FACING_FRONT)
    }

    fun toggleRollCompensation() {
        _rollCompensationEnabled.value = !_rollCompensationEnabled.value
    }

    fun setFov(deg: Float) {
        val clamped = deg.coerceIn(20f, 110f)
        _fov.value = clamped
        bridge.setFov(clamped)
    }

    fun resetView() {
        _fov.value = 75f
        bridge.resetView()
        bridge.setFov(75f)
    }

    fun loadGltfUrl(url: String) {
        bridge.loadGltf(url)
    }

    override fun onCleared() {
        super.onCleared()
        // The OrientationProvider's lifecycle is bound separately to the Activity.
    }
}
