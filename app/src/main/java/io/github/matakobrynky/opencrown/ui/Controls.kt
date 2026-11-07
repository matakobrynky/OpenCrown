package io.github.matakobrynky.opencrown.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Track one finger from down to up, calling [onMove] with each position. */
private suspend fun AwaitPointerEventScope.trackFinger(
    onDown: (Offset) -> Unit, onMove: (Offset) -> Unit, onUp: () -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    down.consume()
    onDown(down.position)
    var id = down.id
    while (true) {
        val ev = awaitPointerEvent()
        val ch: PointerInputChange = ev.changes.firstOrNull { it.id == id } ?: break
        if (!ch.pressed) break
        onMove(ch.position)
        ch.consume()
    }
    onUp()
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), center, radius), radius, center)
}

private fun DrawScope.centeredText(tm: TextMeasurer, text: String, center: Offset, color: Color, size: Float) {
    if (text.isEmpty()) return
    val layout = tm.measure(text, TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

@Composable
fun JoystickControl(color: Color, springBack: Boolean, enabled: Boolean, resetToken: Int, onValue: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onValue)
    val spring by rememberUpdatedState(springBack)
    LaunchedEffect(resetToken, enabled) { knob = Offset.Zero; cb(0f, 0f) }

    Canvas(
        Modifier.fillMaxSize().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                fun upd(p: Offset) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = min(size.width, size.height) / 2f * 0.78f
                    var d = (p - c) / r
                    val len = d.getDistance()
                    if (len > 1f) d /= len
                    knob = d; cb(d.x, -d.y)
                }
                trackFinger({ active = true; upd(it) }, ::upd) {
                    active = false
                    if (spring) { knob = Offset.Zero; cb(0f, 0f) }
                }
            }
        }
    ) {
        val c = center
        val r = min(size.width, size.height) / 2f
        val travel = r * 0.78f
        drawCircle(ControlFill, r, c)
        drawCircle(color.copy(alpha = if (active) 0.9f else idleOutline()), r - 2f, c, style = Stroke(3f))
        drawCircle(color.copy(alpha = 0.12f), travel, c, style = Stroke(1.5f))
        drawLine(color.copy(alpha = 0.18f), Offset(c.x - travel, c.y), Offset(c.x + travel, c.y), 1.5f)
        drawLine(color.copy(alpha = 0.18f), Offset(c.x, c.y - travel), Offset(c.x, c.y + travel), 1.5f)
        val k = c + knob * travel
        val kr = r * 0.3f
        if (active) glow(color, k, kr * 2.2f)
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0.7f)), k, kr), kr, k)
        drawCircle(Color.White.copy(alpha = 0.35f), kr * 0.45f, k - Offset(kr * 0.25f, kr * 0.25f))
    }
}

/**
 * Slider. [zeroAt] is where it rests and outputs 0, as a fraction from its − end (bottom / left):
 * 0.5 for motors; for servos it follows the travel, so a servo with no − travel rests at the end
 * and uses the whole slider going one way.
 */
@Composable
fun SliderControl(
    color: Color, vertical: Boolean, springBack: Boolean, enabled: Boolean, resetToken: Int,
    zeroAt: Float = 0.5f, onValue: (Float) -> Unit,
) {
    val z = zeroAt.coerceIn(0f, 1f)
    var pos by remember { mutableFloatStateOf(z) }      // 0 = − end … 1 = + end
    var active by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onValue)
    val spring by rememberUpdatedState(springBack)
    val zero by rememberUpdatedState(z)
    val tm = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(resetToken, enabled, z) { pos = z; cb(0f) }
    val value = io.github.matakobrynky.opencrown.data.sliderValue(pos, z)

    Canvas(
        Modifier.fillMaxSize().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                fun upd(p: Offset) {
                    var np = if (vertical) {
                        val pad = size.width * 0.35f
                        1f - (p.y - pad) / (size.height - 2 * pad)
                    } else {
                        val pad = size.height * 0.35f
                        (p.x - pad) / (size.width - 2 * pad)
                    }.coerceIn(0f, 1f)
                    if (abs(np - zero) < 0.03f) np = zero // snap to the rest point
                    if ((np == zero) != (pos == zero)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    pos = np; cb(io.github.matakobrynky.opencrown.data.sliderValue(np, zero))
                }
                trackFinger({ active = true; upd(it) }, ::upd) {
                    active = false
                    if (spring) { pos = zero; cb(0f) }
                }
            }
        }
    ) {
        val thick = if (vertical) size.width else size.height
        val rad = CornerRadius(thick / 2f)
        drawRoundRect(ControlFill, cornerRadius = rad)
        drawRoundRect(color.copy(alpha = if (active) 0.9f else idleOutline()), cornerRadius = rad, style = Stroke(3f))
        val pad = thick * 0.35f
        val inset = thick * 0.18f
        if (vertical) {
            val top = pad; val bottom = size.height - pad
            val rest = bottom - z * (bottom - top)
            val y = bottom - pos * (bottom - top)
            drawLine(color.copy(alpha = 0.25f), Offset(inset, rest), Offset(size.width - inset, rest), 2f)
            drawRoundRect(
                color.copy(alpha = 0.35f), Offset(inset, min(y, rest)), Size(size.width - 2 * inset, abs(y - rest)),
                CornerRadius(8f),
            )
            val th = thick * 0.42f
            if (active) glow(color, Offset(size.width / 2, y), thick * 0.9f)
            drawRoundRect(color, Offset(inset * 0.6f, y - th / 2), Size(size.width - inset * 1.2f, th), CornerRadius(th / 2))
            centeredText(tm, "${(value * 100).roundToInt()}", Offset(size.width / 2, y), Color.Black, 12f)
        } else {
            val l = pad; val r = size.width - pad
            val rest = l + z * (r - l)
            val x = l + pos * (r - l)
            drawLine(color.copy(alpha = 0.25f), Offset(rest, inset), Offset(rest, size.height - inset), 2f)
            drawRoundRect(
                color.copy(alpha = 0.35f), Offset(min(x, rest), inset), Size(abs(x - rest), size.height - 2 * inset),
                CornerRadius(8f),
            )
            val tw = thick * 0.42f
            if (active) glow(color, Offset(x, size.height / 2), thick * 0.9f)
            drawRoundRect(color, Offset(x - tw / 2, inset * 0.6f), Size(tw, size.height - inset * 1.2f), CornerRadius(tw / 2))
            centeredText(tm, "${(value * 100).roundToInt()}", Offset(x, size.height / 2), Color.Black, 12f)
        }
    }
}

@Composable
fun ButtonControl(
    color: Color, label: String, toggle: Boolean, enabled: Boolean, resetToken: Int, onValue: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onValue)
    val haptic = LocalHapticFeedback.current
    val tm = rememberTextMeasurer()
    LaunchedEffect(resetToken, enabled) { on = false; pressed = false; cb(false) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(enabled, toggle) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    trackFinger({
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (toggle) { on = !on; cb(on) } else cb(true)
                    }, {}) {
                        pressed = false
                        if (!toggle) cb(false)
                    }
                }
            }
        ) {
            val lit = if (toggle) on else pressed
            val r = min(size.width, size.height) / 2f
            val rad = CornerRadius(r * 0.45f)
            if (lit) glow(color, center, r * 1.3f)
            drawRoundRect(if (lit) color else ControlFill, cornerRadius = rad)
            drawRoundRect(color.copy(alpha = if (lit) 1f else idleOutline() + 0.1f), cornerRadius = rad, style = Stroke(3f))
            if (toggle) {
                val dot = r * 0.12f
                drawCircle(if (on) Color.Black.copy(alpha = 0.6f) else color.copy(alpha = 0.4f), dot, Offset(center.x, size.height * 0.2f))
            }
            centeredText(tm, label.ifEmpty { if (toggle) "ON/OFF" else "PUSH" }, center, if (lit) Color.Black else color, 13f)
        }
    }
}

/** Outline strength of a control that isn't being touched (stronger on the light canvas). */
private fun idleOutline() = if (Ui.scheme.dark) 0.45f else 0.75f
