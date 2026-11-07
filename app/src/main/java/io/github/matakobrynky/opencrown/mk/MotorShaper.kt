package io.github.matakobrynky.opencrown.mk

import io.github.matakobrynky.opencrown.data.Binding
import kotlin.math.abs

/**
 * Turns a control's value into the motor output for one binding in motor (speed) mode, over time.
 *
 * Simple setup: output = value × power.
 * Advanced setup, optionally:
 *  - a separate top speed for reverse,
 *  - smooth acceleration: the output moves towards the target at "full scale per [Binding.accelMs]".
 */
class MotorShaper {
    private var cur = 0f

    /** Last output, −1…1. */
    var output = 0f
        private set

    fun reset() { cur = 0f; output = 0f }

    /** [value] is the control's value −1…1 (already reversed if needed); [dtMs] the time since the last step. */
    fun step(b: Binding, value: Float, dtMs: Float): Float {
        val target = target(b, value)
        cur = if (b.advanced && b.accel && b.accelMs > 0) approach(cur, target, dtMs / b.accelMs) else target
        output = cur
        return output
    }

    /** True while the output is still ramping towards the control's value. */
    fun busy(b: Binding, value: Float) = abs(cur - target(b, value)) > 1e-4f

    private fun target(b: Binding, value: Float): Float {
        val v = value.coerceIn(-1f, 1f)
        val max = if (b.advanced && b.splitMax && v < 0f) b.maxRevPct else b.maxPct
        return v * max / 100f
    }

    private fun approach(from: Float, to: Float, step: Float) =
        if (from < to) minOf(from + step, to) else maxOf(from - step, to)
}
