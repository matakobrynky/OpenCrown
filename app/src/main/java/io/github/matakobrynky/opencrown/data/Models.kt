package io.github.matakobrynky.opencrown.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class HubType(val label: String, val short: String, val channels: Int, val bluetoothHub: Boolean) {
    H6("H6.0 hub", "H6.0", 6, true),
    H4("H4.0 hub", "H4.0", 4, true),
    MK6("Classic 6.0 module", "6.0", 6, false),
    MK4("Classic 4.0 (speed control)", "4.0", 4, false),
    MK4OO("Classic 4.0 (older, on/off)", "4.0", 4, false),
}

/**
 * H-hub stall ("rotation blocking") protection. Hub-wide and remembered by the hub.
 * While it's on the hub keeps cutting motors (PF ones especially) → stutter.
 * AUTO and OFF both switch it off at every connect; KEEP sends nothing.
 */
@Serializable
enum class StallMode(val label: String) {
    AUTO("Off (recommended)"),
    ON("On"),
    OFF("Off"),
    KEEP("Don't change"),
}

@Serializable
data class HubModule(
    val id: String = newId(),
    val name: String,
    val type: HubType = HubType.H6,
    val index: Int = 0,           // classic modules only: 0..2
    val address: String? = null,  // H-series hubs: Bluetooth address
    val stall: StallMode = StallMode.AUTO,
)

@Serializable
enum class OutputMode(val label: String) {
    MOTOR("Motor (speed)"),
    SERVO("Servo (position)"),
}

/**
 * Mould King hall motors: hub position units per output-shaft turn.
 * MK Plus shows positions as raw / 10000 and documents 10 turns = 395 (M/L) or 590 (XL).
 */
@Serializable
enum class MotorSize(val label: String, val rawPerTurn: Float) {
    ML("M / L motor", 395_000f),
    XL("XL motor", 590_000f),
}

/**
 * How far a servo may travel: LOW for steering (a few turns, set in degrees),
 * HIGH for linear actuators and winches (up to 1000 turns, set in turns).
 */
@Serializable
enum class ServoRange(val label: String, val hint: String, val maxTravelDeg: Float, val maxCenterDeg: Float) {
    LOW("Low", "steering · up to 3 turns", 3 * 360f, 5 * 360f),
    HIGH("High", "actuators · up to 1000 turns", 1000 * 360f, 1000 * 360f),
}

@Serializable
data class Binding(
    val moduleId: String? = null,
    val channel: Int = 0,
    val invert: Boolean = false,
    val maxPct: Int = 100,
    // ---- servo (position) mode, H4.0 / H6.0 smart motors only ----
    val mode: OutputMode = OutputMode.MOTOR,
    val motorSize: MotorSize = MotorSize.ML,
    /** centre position in degrees, relative to the hub's zero point */
    val servoCenterDeg: Float = 0f,
    /** travel from centre in degrees, towards negative and positive */
    val servoMinusDeg: Float = 90f,
    val servoPlusDeg: Float = 90f,
    /** same travel both ways */
    val servoLinked: Boolean = true,
    /** move speed, 1..100 % */
    val servoSpeed: Int = 100,
    val servoRange: ServoRange = ServoRange.LOW,
    // ---- motor (speed) mode, "Advanced" setup ----
    val advanced: Boolean = false,
    /** separate top speed when running backwards ([maxPct] is then forwards only) */
    val splitMax: Boolean = false,
    val maxRevPct: Int = 100,
    /** smooth acceleration: speed ramps instead of jumping */
    val accel: Boolean = false,
    /** ramp time from stop to full speed */
    val accelMs: Int = 800,
    /**
     * Motor mode on H4.0 / H6.0 with a smart motor: the hub itself stops the motor at its end
     * points ([servoMinusDeg] below and [servoPlusDeg] above the hub's zero), like MK Plus'
     * 17075 crane boom. Shares motor size and range with servo mode.
     */
    val endStops: Boolean = false,
) {
    fun rawToDeg(raw: Int) = raw * 360f / motorSize.rawPerTurn
    fun degToRaw(deg: Float) = Math.round(deg.toDouble() * motorSize.rawPerTurn / 360.0).toInt()
    val minusDeg get() = servoMinusDeg.coerceAtLeast(0f)
    val plusDeg get() = (if (servoLinked) servoMinusDeg else servoPlusDeg).coerceAtLeast(0f)
    /** End points for end-stop mode, raw units: (lower, upper), measured from the hub's zero. */
    fun endPointsRaw() = -degToRaw(minusDeg) to degToRaw(plusDeg)

    fun servoCfg() = io.github.matakobrynky.opencrown.mk.MkHub.ServoCfg(degToRaw(servoCenterDeg), degToRaw(minusDeg), degToRaw(plusDeg), servoSpeed)

    /**
     * Where the centre sits along a servo slider, from its − end (0) to its + end (1), so the
     * whole slider maps evenly onto the travel: with no − travel it rests at the − end.
     */
    /**
     * Same idea for a speed slider with different forward / reverse top speeds: it rests where
     * stop lies between full reverse and full forward, so equal slider moves give equal speed changes.
     */
    fun motorZeroAt(): Float {
        if (!advanced || !splitMax) return 0.5f
        val span = (maxPct + maxRevPct).toFloat()
        if (span <= 0f) return 0.5f
        val z = maxRevPct / span
        return if (invert) 1f - z else z
    }

    fun servoZeroAt(): Float {
        val span = minusDeg + plusDeg
        if (span <= 0f) return 0.5f
        val z = minusDeg / span
        return if (invert) 1f - z else z
    }
}

/**
 * Slider position (0 = − end … 1 = + end) → output value −1…1, with 0 at [zeroAt].
 * Together with the servo mapping (− side scaled by − travel, + side by + travel) this makes
 * equal slider distances equal motor distances.
 */
fun sliderValue(pos: Float, zeroAt: Float): Float = when {
    pos >= zeroAt -> if (zeroAt >= 1f) 0f else ((pos - zeroAt) / (1f - zeroAt)).coerceIn(0f, 1f)
    else -> if (zeroAt <= 0f) 0f else ((pos - zeroAt) / zeroAt).coerceIn(-1f, 0f)
}

@Serializable
enum class WidgetType(val label: String) {
    JOYSTICK("Joystick"),
    SLIDER_V("Vertical slider"),
    SLIDER_H("Horizontal slider"),
    BUTTON("Push button"),
    TOGGLE("Toggle switch"),
}

@Serializable
data class Widget(
    val id: String = newId(),
    val type: WidgetType,
    /** position & size as fractions of the control canvas */
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val label: String = "",
    val color: Int = 0,
    val primary: Binding = Binding(),     // X axis / main output
    val secondary: Binding = Binding(),   // Y axis (joystick only)
    val springBack: Boolean = true,
    val buttonPct: Int = 100,             // output of button/toggle when active (-100..100)
)

@Serializable
data class Profile(
    val id: String = newId(),
    val name: String,
    val color: Int = 0,
    val widgets: List<Widget> = emptyList(),
)

@Serializable
data class AppData(
    val modules: List<HubModule> = emptyList(),
    val profiles: List<Profile> = emptyList(),
)

fun newId(): String = UUID.randomUUID().toString().take(8)

fun channelName(c: Int) = ('A' + c).toString()
