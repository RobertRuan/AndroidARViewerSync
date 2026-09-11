package com.example.arviewersync.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Immutable, smoothed orientation payload delivered to consumers.
 * All angles are in degrees, yaw in [-180, 180], pitch/roll in [-90, 90].
 */
data class OrientationEuler(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float
)

/**
 * Registers [Sensor.TYPE_GAME_ROTATION_VECTOR] (no magnetic-north dependence,
 * so it works without compass calibration) and publishes a smoothed Euler
 * orientation via a StateFlow.
 *
 * Throttles publication to ≤60 Hz to avoid burning CPU on sensor noise.
 *
 * Lifecycle-aware: sensors are registered on resume and unregistered on pause.
 *
 * @param mirrorYawWhenFrontCamera when true, the published yaw is negated so
 *   the 3D view's rotation matches a mirrored front-camera preview (standard
 *   AR front-camera behavior).
 */
class OrientationProvider(
    private val context: Context,
    private val mirrorYawWhenFrontCamera: () -> Boolean = { false }
) : DefaultLifecycleObserver, SensorEventListener {

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val filter = LowPassFilter(alpha = 0.25f, deadzoneDeg = 0.15f)

    private val _orientation = MutableStateFlow<OrientationEuler?>(null)
    val orientation: StateFlow<OrientationEuler?> = _orientation

    private var lastEmitNanos: Long = 0L
    private val minEmitIntervalNanos: Long = 16_000_000L   // 60 Hz cap

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Exposed for the manifest check in MainActivity
    val isSensorAvailable: Boolean get() = rotationSensor != null

    override fun onResume(owner: LifecycleOwner) {
        rotationSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        sm.unregisterListener(this)
        filter.reset()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return

        // 1. rotation vector -> 3x3 rotation matrix (device frame)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 2. remap to display frame so that rotating the device to landscape
        //    does NOT cause a 90° jump in the 3D view.
        val axisX: Int
        val axisY: Int
        when (displayRotation()) {
            Surface.ROTATION_0    -> { axisX = SensorManager.AXIS_X;      axisY = SensorManager.AXIS_Y }
            Surface.ROTATION_90   -> { axisX = SensorManager.AXIS_Y;      axisY = SensorManager.AXIS_MINUS_X }
            Surface.ROTATION_180  -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
            Surface.ROTATION_270  -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
            else                  -> { axisX = SensorManager.AXIS_X;      axisY = SensorManager.AXIS_Y }
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)

        // 3. matrix -> [azimuth, pitch, roll] radians
        SensorManager.getOrientation(remappedMatrix, orientationAngles)

        // 4. degrees + smoothing
        val rawYaw = EulerUtil.radToDeg(orientationAngles[0])
        val rawPitch = EulerUtil.radToDeg(orientationAngles[1])
        val rawRoll = EulerUtil.radToDeg(orientationAngles[2])

        filter.update(rawYaw, rawPitch, rawRoll)

        // 5. throttle to ≤60 Hz
        val now = event.timestamp
        if (now - lastEmitNanos < minEmitIntervalNanos) return
        lastEmitNanos = now

        var yaw = filter.yaw()
        if (mirrorYawWhenFrontCamera()) {
            yaw = -yaw
        }
        _orientation.value = OrientationEuler(
            yawDeg = EulerUtil.wrapDeg(yaw),
            pitchDeg = filter.pitch(),
            rollDeg = filter.roll()
        )
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }
}
