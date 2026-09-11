package com.example.arviewersync.sensor

/**
 * Per-axis exponential low-pass filter with deadzone.
 *
 *   out = out + alpha * (in - out)
 *
 * The deadzone suppresses tiny sensor noise so the value freezes when the
 * device is held still, eliminating residual "drift" in the 3D view.
 *
 * Yaw uses shortest-path interpolation so crossing the ±180° boundary does
 * not cause a 360° swing.
 */
class LowPassFilter(
    private val alpha: Float = 0.25f,
    private val deadzoneDeg: Float = 0.15f
) {
    private var yawOut: Float = 0f
    private var pitchOut: Float = 0f
    private var rollOut: Float = 0f
    private var initialised: Boolean = false

    /** Update internal state with a new raw sample (degrees). */
    fun update(rawYaw: Float, rawPitch: Float, rawRoll: Float) {
        if (!initialised) {
            // Seed with the first sample to avoid a long ramp-up from 0.
            yawOut = EulerUtil.wrapDeg(rawYaw)
            pitchOut = rawPitch
            rollOut = rawRoll
            initialised = true
            return
        }
        // Yaw uses shortest-path delta to handle wrap-around correctly.
        val dy = EulerUtil.shortestDeltaDeg(yawOut, rawYaw)
        val dp = rawPitch - pitchOut
        val dr = rawRoll - rollOut

        // Deadzone: if all three deltas are below threshold, hold the output.
        val k = if (kotlin.math.abs(dy) < deadzoneDeg &&
            kotlin.math.abs(dp) < deadzoneDeg &&
            kotlin.math.abs(dr) < deadzoneDeg) {
            0f
        } else {
            alpha
        }

        yawOut = EulerUtil.wrapDeg(yawOut + dy * k)
        pitchOut += dp * k
        rollOut += dr * k
    }

    fun yaw(): Float = yawOut
    fun pitch(): Float = pitchOut
    fun roll(): Float = rollOut

    fun reset() {
        initialised = false
    }
}
