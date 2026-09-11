package com.example.arviewersync.sensor

import kotlin.math.PI
import kotlin.math.abs

/**
 * Helpers for Euler-angle conversion & smoothing.
 *
 * The Android [SensorManager.getOrientation] returns radians in the order
 *   values[0] = azimuth (yaw, around Z)
 *   values[1] = pitch (around X)
 *   values[2] = roll (around Y)
 * All angles here are kept as **degrees** in the [-180, 180] range to make the
 * JS bridge payload compact and debug HUD readable.
 */
object EulerUtil {

    private const val RAD_TO_DEG = 180f / PI.toFloat()

    /** Convert radians [-PI, PI] to degrees [-180, 180]. */
    fun radToDeg(rad: Float): Float = rad * RAD_TO_DEG

    /** Wrap a degree value into [-180, 180]. */
    fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Shortest-path signed difference (b - a) in degrees, wrapped to [-180, 180].
     * Use this for low-pass smoothing on yaw so that crossing +/-180° does
     * not produce a 360° jump.
     */
    fun shortestDeltaDeg(a: Float, b: Float): Float {
        val d = wrapDeg(b - a)
        return d
    }

    /** True if |a - b| (shortest path) is below [thresholdDeg]. */
    fun withinDeg(a: Float, b: Float, thresholdDeg: Float): Boolean =
        abs(shortestDeltaDeg(a, b)) < thresholdDeg
}
