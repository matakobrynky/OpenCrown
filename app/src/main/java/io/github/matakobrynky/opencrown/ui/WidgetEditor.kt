package io.github.matakobrynky.opencrown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.matakobrynky.opencrown.data.*
import io.github.matakobrynky.opencrown.mk.HallProtocol
import io.github.matakobrynky.opencrown.mk.MkHub
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetEditorDialog(initial: Widget, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Widget) -> Unit) {
    var w by remember { mutableStateOf(initial) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp), color = Surface2,
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.92f).fillMaxHeight(0.92f),
        ) {
            Column {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(w.type), null, tint = paletteColor(w.color))
                    Spacer(Modifier.width(10.dp))
                    Text(w.type.label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onDelete) { Icon(Icons.Outlined.Delete, null, tint = Danger); Text(" Delete", color = Danger) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(w.label, { w = w.copy(label = it.take(14)) }, label = { Text("Label") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Palette.indices.forEach { i ->
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).background(Palette[i])
                                        .border(if (w.color % Palette.size == i) 3.dp else 0.dp, Ink, CircleShape)
                                        .clickable { w = w.copy(color = i) }
                                )
                            }
                        }
                    }

                    if (w.type == WidgetType.JOYSTICK) {
                        BindingEditor("X AXIS  (left ↔ right)", w.primary) { w = w.copy(primary = it) }
                        BindingEditor("Y AXIS  (down ↕ up)", w.secondary) { w = w.copy(secondary = it) }
                    } else {
                        BindingEditor("OUTPUT", w.primary) { w = w.copy(primary = it) }
                    }

                    when (w.type) {
                        WidgetType.JOYSTICK, WidgetType.SLIDER_H, WidgetType.SLIDER_V ->
                            SwitchRow("Spring back to centre on release",
                                if (w.springBack) "Like a steering wheel or a self-centering throttle" else "Stays where you leave it — good for cruise speed",
                                w.springBack) { w = w.copy(springBack = it) }
                        WidgetType.BUTTON, WidgetType.TOGGLE -> {
                            Label("OUTPUT WHEN ${if (w.type == WidgetType.BUTTON) "PRESSED" else "ON"}:  ${w.buttonPct}%")
                            Slider(
                                w.buttonPct.toFloat(), { w = w.copy(buttonPct = (it / 5).roundToInt() * 5) },
                                valueRange = -100f..100f,
                            )
                            Text("Negative values run the motor backwards (servo: move towards the − end).", color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button({ onSave(w) }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelSmall, color = TextDim)

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = TextDim, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

/**
 * A section that can be minimised by tapping its header. Collapsed, it shows [summary] instead
 * of its content. Which sections are minimised is remembered.
 */
@Composable
private fun Collapsible(
    key: String, title: String, summary: String, background: Color, spacing: androidx.compose.ui.unit.Dp,
    trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val open = key !in Ui.collapsed
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background).animateContentSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).clickable { Ui.toggleCollapsed(ctx, key) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(title)
            if (!open && summary.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(summary, color = Ink, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            trailing()
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                if (open) "Minimise" else "Expand", tint = TextDim, modifier = Modifier.padding(start = 6.dp).size(22.dp),
            )
        }
        if (open) content()
    }
}

/** One-line description of a binding, for a minimised section. */
private fun bindingSummaryLine(b: Binding): String {
    val m = Repo.module(b.moduleId) ?: return "not connected"
    val port = "port ${channelName(b.channel)}"
    val what = if (m.type.bluetoothHub && b.mode == OutputMode.SERVO) {
        "servo " + fmtAngle(b.servoCenterDeg - b.minusDeg, b.servoRange) + " … " + fmtAngle(b.servoCenterDeg + b.plusDeg, b.servoRange)
    } else motorSummary(b)
    return "${m.name} · $port · $what" + if (b.invert) " · reversed" else ""
}

@Composable
private fun BindingEditor(title: String, b: Binding, onChange: (Binding) -> Unit) {
    Collapsible("bind:$title", title, bindingSummaryLine(b), Surface1, 8.dp) { BindingBody(b, onChange) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.BindingBody(b: Binding, onChange: (Binding) -> Unit) {
    run {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(b.moduleId == null, { onChange(b.copy(moduleId = null)) }, label = { Text("None") })
            Repo.modules.forEach { m ->
                FilterChip(b.moduleId == m.id, { onChange(b.copy(moduleId = m.id, channel = b.channel.coerceAtMost(m.type.channels - 1))) },
                    label = { Text("${m.name} (${m.type.short})") })
            }
        }
        val mod = Repo.module(b.moduleId)
        if (mod == null) {
            Text(if (Repo.modules.isEmpty()) "Add a module on the home screen first." else "Not connected to any motor.", color = TextDim, fontSize = 12.sp)
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Port", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until mod.type.channels).forEach { c ->
                    FilterChip(b.channel == c, { onChange(b.copy(channel = c)) }, label = { Text(channelName(c), fontWeight = FontWeight.Bold) })
                }
            }
        }
        val servoCapable = mod.type.bluetoothHub
        val detected = if (servoCapable && MkHub.isLinked(mod)) MkHub.smartMotor[MkHub.posKey(mod, b.channel)] else null
        if (detected != null) {
            Text(
                if (detected) "Port ${channelName(b.channel)}: smart motor detected" else "Port ${channelName(b.channel)}: PF motor (via adapter) or nothing plugged in",
                color = if (detected) Ok else TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 64.dp),
            )
        }
        if (servoCapable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mode", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                Segmented(OutputMode.entries, b.mode, { it.label }, Modifier.weight(1f)) { onChange(b.copy(mode = it)) }
            }
        }
        if (servoCapable && b.mode == OutputMode.SERVO) {
            if (detected == false) {
                Text(
                    "Servo mode needs a Mould King smart (hall) motor. A PF motor on this port won't move in servo mode — switch to Motor (speed).",
                    color = Danger, fontSize = 12.sp,
                )
            }
            ServoSettings(mod, b, onChange)
        } else {
            MotorSettings(b, onChange)
            if (servoCapable) EndStopsCard(mod, b, onChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reverse direction", modifier = Modifier.weight(1f))
            Switch(b.invert, { onChange(b.copy(invert = it)) })
        }
    }
}

private fun motorSummary(b: Binding): String {
    val parts = mutableListOf(if (b.advanced && b.splitMax) "motor ${b.maxPct}% / rev ${b.maxRevPct}%" else "motor ${b.maxPct}%")
    if (b.advanced && b.accel) parts += "smooth ${"%.1f".format(b.accelMs / 1000f)} s"
    if (b.endStops && Repo.module(b.moduleId)?.type?.bluetoothHub == true)
        parts += "stops ${fmtAngle(-b.minusDeg, b.servoRange)} … ${fmtAngle(b.plusDeg, b.servoRange)}"
    return parts.joinToString(" · ")
}

/** One labelled slider row: label, slider, value. */
@Composable
private fun ValueSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String,
    modifier: Modifier = Modifier, onValue: (Float) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text(text, modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = Ink, fontSize = 14.sp)
    }
}

/** Motor (speed) mode: Simple = one power limit; Advanced = separate reverse speed, smooth acceleration. */
@Composable
private fun MotorSettings(b: Binding, onChange: (Binding) -> Unit) {
    val latest by rememberUpdatedState(b)
    fun pct(v: Float) = ((v / 5).roundToInt() * 5).coerceIn(5, 100)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Setup", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Segmented(listOf(false, true), b.advanced, { if (it) "Advanced" else "Simple" }, Modifier.weight(1f)) {
            onChange(latest.copy(advanced = it))
        }
    }
    if (!b.advanced) {
        ValueSlider("Power", b.maxPct.toFloat(), 10f..100f, "${b.maxPct}%") { onChange(latest.copy(maxPct = pct(it).coerceAtLeast(10))) }
        return
    }

    // --- top speed ---
    ServoCard("TOP SPEED", if (b.splitMax) "forward ${b.maxPct}% · reverse ${b.maxRevPct}%" else "${b.maxPct}% both ways") {
        ValueSlider(if (b.splitMax) "Forward" else "Both ways", b.maxPct.toFloat(), 5f..100f, "${b.maxPct}%") {
            onChange(latest.copy(maxPct = pct(it)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Different speed in reverse", modifier = Modifier.weight(1f), fontSize = 14.sp, color = Ink)
            Switch(b.splitMax, { onChange(latest.copy(splitMax = it, maxRevPct = if (it) latest.maxRevPct else latest.maxPct)) })
        }
        if (b.splitMax) {
            ValueSlider("Reverse", b.maxRevPct.toFloat(), 5f..100f, "${b.maxRevPct}%") {
                onChange(latest.copy(maxRevPct = pct(it)))
            }
            Text("A slider on this output rests where stop lies between full reverse and full forward " +
                "(${(b.motorZeroAt() * 100).roundToInt()}% of the way from the reverse end), so every bit of travel changes the speed equally.",
                color = TextDim, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }

    // --- acceleration ---
    ServoCard("ACCELERATION", if (b.accel) "smooth · ${"%.1f".format(b.accelMs / 1000f)} s to full speed" else "instant",
        trailing = { Switch(b.accel, { onChange(latest.copy(accel = it)) }, Modifier.padding(end = 4.dp)) }) {
        if (!b.accel) {
            Text("Off: the motor follows the control instantly. Turn on to make speed build up and die down smoothly, like a heavy vehicle.",
                color = TextDim, fontSize = 12.sp, lineHeight = 15.sp)
        } else {
            ValueSlider("Ramp", b.accelMs.toFloat(), 100f..5000f, "${"%.1f".format(b.accelMs / 1000f)} s") {
                onChange(latest.copy(accelMs = ((it / 100).roundToInt() * 100).coerceIn(100, 5000)))
            }
            Text("Time to go from stop to full speed (and back). STOP still stops at once.",
                color = TextDim, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

/**
 * Motor mode with end stops (H hubs, smart motor): the motor runs at the control's speed, and the
 * hub stops it by itself at the − and + end — how MK Plus drives the 17075 crane's boom. Because
 * it stays a speed command, it runs at the same time as any other motor on the hub.
 */
@Composable
private fun EndStopsCard(mod: HubModule, b: Binding, onChange: (Binding) -> Unit) {
    val latest by rememberUpdatedState(b)
    val linked = MkHub.isLinked(mod)
    val pos = MkHub.positions[MkHub.posKey(mod, b.channel)]
    val posDeg = pos?.let { b.rawToDeg(it) }
    val range = b.servoRange
    LaunchedEffect(mod.id, b.channel, linked, b.endStops) {
        while (linked && b.endStops) { MkHub.requestPosition(mod, b.channel); kotlinx.coroutines.delay(400) }
    }
    ServoCard(
        "END STOPS",
        if (b.endStops) fmtAngle(-b.minusDeg, range) + " … " + fmtAngle(b.plusDeg, range) else "off",
        trailing = { Switch(b.endStops, { onChange(latest.copy(endStops = it, servoLinked = false)) }, Modifier.padding(end = 4.dp)) },
    ) {
        if (!b.endStops) {
            Text("Off. Turn on to make the hub stop this motor by itself at two set positions — like a crane boom or " +
                "an actuator that must not over-travel. It still runs as a normal motor, so other motors keep running at the same time. " +
                "Needs a Mould King smart (hall) motor.",
                color = TextDim, fontSize = 12.sp, lineHeight = 15.sp)
            return@ServoCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Motor", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Segmented(MotorSize.entries, b.motorSize, { it.label }, Modifier.weight(1f)) { onChange(latest.copy(motorSize = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Range", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Segmented(ServoRange.entries, range, { it.label }, Modifier.weight(1f)) { r ->
                onChange(latest.copy(servoRange = r,
                    servoMinusDeg = latest.servoMinusDeg.coerceAtMost(r.maxTravelDeg),
                    servoPlusDeg = latest.plusDeg.coerceAtMost(r.maxTravelDeg), servoLinked = false))
            }
        }
        HorizontalDivider(color = Line)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Position", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Text(
                when { !linked -> "connect the hub to read it"; posDeg == null -> "reading…"; else -> fmtAngle(posDeg, range) },
                color = if (linked && posDeg != null) Accent else TextDim, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
        }
        val can = linked && posDeg != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                { MkHub.zeroHere(mod, b.channel) }, enabled = linked, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1C222D)),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("Zero here", maxLines = 1) }
            OutlinedButton({ posDeg?.let { onChange(latest.copy(servoMinusDeg = kotlin.math.abs(minOf(it, 0f)).coerceAtMost(range.maxTravelDeg), servoPlusDeg = latest.plusDeg, servoLinked = false)) } },
                enabled = can, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("− end here", maxLines = 1) }
            OutlinedButton({ posDeg?.let { onChange(latest.copy(servoPlusDeg = maxOf(it, 0f).coerceAtMost(range.maxTravelDeg), servoMinusDeg = latest.minusDeg, servoLinked = false)) } },
                enabled = can, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("+ end here", maxLines = 1) }
        }
        Text("Drive the motor to where it should stop (with this control, before turning end stops on, or by hand) and tap a button. " +
            "\"Zero here\" makes the current spot 0; the ends are measured from it.",
            color = TextDim, fontSize = 11.sp, lineHeight = 14.sp)
        AngleSlider("− end", b.minusDeg, range.maxTravelDeg, range) { onChange(latest.copy(servoMinusDeg = it, servoPlusDeg = latest.plusDeg, servoLinked = false)) }
        AngleSlider("+ end", b.plusDeg, range.maxTravelDeg, range) { onChange(latest.copy(servoPlusDeg = it, servoMinusDeg = latest.minusDeg, servoLinked = false)) }
        Text("The motor stops at ${fmtAngle(-b.minusDeg, range)} and ${fmtAngle(b.plusDeg, range)} from zero, whatever the control asks. " +
            "Ends closer to zero than about 1° (MK Plus: 1000 units) count as zero.",
            color = TextDim, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

/** Equal-width segmented choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        items.forEachIndexed { i, it ->
            SegmentedButton(
                selected = it == selected, onClick = { onSelect(it) },
                shape = SegmentedButtonDefaults.itemShape(i, items.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Accent, activeContentColor = Color(0xFF1C222D),
                    inactiveContainerColor = Color.Transparent, inactiveContentColor = Ink,
                    activeBorderColor = Accent, inactiveBorderColor = Line,
                ),
                icon = {},
            ) { Text(label(it), maxLines = 1, fontSize = 14.sp) }
        }
    }
}

/** A titled, minimisable card inside the servo settings. */
@Composable
private fun ServoCard(title: String, summary: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) =
    Collapsible("servo:$title", title, summary, Surface2, 10.dp, trailing, content)

/** Angle text for the chosen range: degrees for steering, turns for actuators. */
private fun fmtAngle(deg: Float, range: ServoRange, signed: Boolean = true): String {
    val sign = if (signed && deg > 0.05f) "+" else if (deg < -0.05f) "−" else ""
    val a = kotlin.math.abs(deg)
    return when (range) {
        ServoRange.LOW -> "$sign${a.roundToInt()}°"
        ServoRange.HIGH -> {
            val t = a / 360f
            sign + (if (t < 10f) "%.1f".format(t) else "%.0f".format(t)) + if (t == 1f) " turn" else " turns"
        }
    }
}

/** Servo (position) setup — MK Plus "Axle settings": zero, ± limits, move speed, in three cards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServoSettings(mod: HubModule, b: Binding, onChange: (Binding) -> Unit) {
    val linked = MkHub.isLinked(mod)
    val pos = MkHub.positions[MkHub.posKey(mod, b.channel)]
    val latest by rememberUpdatedState(b)
    val range = b.servoRange
    val posDeg = pos?.let { b.rawToDeg(it) }

    // live position, like "当前位移" in MK Plus
    LaunchedEffect(mod.id, b.channel, linked) {
        while (linked) { MkHub.requestPosition(mod, b.channel); kotlinx.coroutines.delay(400) }
    }

    // ---------- 1. what is plugged in, and how far it may go ----------
    ServoCard("SETUP", "${b.motorSize.label} · ${range.label} range") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Motor", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Segmented(MotorSize.entries, b.motorSize, { it.label }, Modifier.weight(1f)) { onChange(latest.copy(motorSize = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Range", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Segmented(ServoRange.entries, range, { it.label }, Modifier.weight(1f)) { r ->
                // keep the settings inside what the new range allows
                onChange(latest.copy(
                    servoRange = r,
                    servoCenterDeg = latest.servoCenterDeg.coerceIn(-r.maxCenterDeg, r.maxCenterDeg),
                    servoMinusDeg = latest.servoMinusDeg.coerceAtMost(r.maxTravelDeg),
                    servoPlusDeg = latest.servoPlusDeg.coerceAtMost(r.maxTravelDeg),
                ))
            }
        }
        Text(range.hint.replaceFirstChar { it.uppercase() }, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 64.dp))
    }

    // ---------- 2. centre and ends ----------
    ServoCard("POSITION & LIMITS",
        fmtAngle(b.servoCenterDeg - b.minusDeg, range) + " … " + fmtAngle(b.servoCenterDeg + b.plusDeg, range), trailing = {
        Text(
            when { !linked -> "hub not connected"; posDeg == null -> "reading…"; else -> "now " + fmtAngle(posDeg, range) },
            color = if (linked && posDeg != null) Accent else TextDim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }) {
        // calibration from the motor's real position: a tidy 2 × 2 grid
        val can = linked && pos != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                { MkHub.zeroHere(mod, b.channel); onChange(latest.copy(servoCenterDeg = 0f)) }, enabled = linked, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1C222D)),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Zero here", maxLines = 1) }
            OutlinedButton({ posDeg?.let { onChange(latest.copy(servoCenterDeg = it.coerceIn(-range.maxCenterDeg, range.maxCenterDeg))) } },
                enabled = can, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Centre here", maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                posDeg?.let { val d = kotlin.math.abs(it - latest.servoCenterDeg).coerceAtMost(range.maxTravelDeg)
                    onChange(latest.copy(servoMinusDeg = d, servoPlusDeg = latest.plusDeg, servoLinked = false)) }
            }, enabled = can, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("− end here", maxLines = 1) }
            OutlinedButton({
                posDeg?.let { val d = kotlin.math.abs(it - latest.servoCenterDeg).coerceAtMost(range.maxTravelDeg)
                    onChange(latest.copy(servoPlusDeg = d, servoMinusDeg = latest.minusDeg, servoLinked = false)) }
            }, enabled = can, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+ end here", maxLines = 1) }
        }
        Text(
            if (linked) "Turn the output by hand (or with Test below) and tap a button, or set the values below."
            else "Connect the hub to set these from the motor's real position, or set the values below.",
            color = TextDim, fontSize = 11.sp, lineHeight = 14.sp,
        )
        HorizontalDivider(color = Line)

        AngleSlider("Centre", b.servoCenterDeg, range.maxCenterDeg, range, signed = true) { onChange(latest.copy(servoCenterDeg = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Same travel both ways", modifier = Modifier.weight(1f), fontSize = 14.sp, color = Ink)
            Switch(b.servoLinked, { on -> onChange(latest.copy(servoLinked = on, servoPlusDeg = latest.servoMinusDeg)) })
        }
        if (b.servoLinked) {
            AngleSlider("Travel ±", b.servoMinusDeg, range.maxTravelDeg, range) { onChange(latest.copy(servoMinusDeg = it)) }
        } else {
            AngleSlider("Travel −", b.servoMinusDeg, range.maxTravelDeg, range) { onChange(latest.copy(servoMinusDeg = it)) }
            AngleSlider("Travel +", b.servoPlusDeg, range.maxTravelDeg, range) { onChange(latest.copy(servoPlusDeg = it)) }
        }
        TravelBar(b)
    }

    // ---------- 3. how it moves ----------
    ServoCard("SPEED & TEST", "${b.servoSpeed}%") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Slider(b.servoSpeed.toFloat(), { onChange(latest.copy(servoSpeed = (it / 5).roundToInt().coerceAtLeast(1) * 5)) },
                valueRange = 5f..100f, modifier = Modifier.weight(1f))
            Text("${b.servoSpeed}%", modifier = Modifier.width(48.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        ServoTester(mod, b, linked)
    }
}

/** The whole travel, end to end, with the centre marked. */
@Composable
private fun TravelBar(b: Binding) {
    val r = b.servoRange
    val lo = b.servoCenterDeg - b.minusDeg
    val hi = b.servoCenterDeg + b.plusDeg
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fmtAngle(lo, r), fontSize = 12.sp, color = TextDim, modifier = Modifier.widthIn(min = 56.dp))
        Box(Modifier.weight(1f).height(18.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Accent.copy(alpha = 0.35f)))
            val span = (b.minusDeg + b.plusDeg).coerceAtLeast(0.001f)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.offset(x = maxWidth * (b.minusDeg / span) - 2.dp).size(4.dp, 16.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
            }
        }
        Text(fmtAngle(hi, r), fontSize = 12.sp, color = TextDim, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 56.dp))
    }
    Text("Full travel ${fmtAngle(hi - lo, r, signed = false)} · centre ${fmtAngle(b.servoCenterDeg, r)}",
        color = TextDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

/**
 * Slider for an angle. Low range: degrees, linear. High range: turns, with a squared scale so
 * small values (a few turns) stay easy to set while 1000 turns is still reachable.
 * Tap the value to type it exactly.
 */
@Composable
private fun AngleSlider(label: String, value: Float, max: Float, range: ServoRange, signed: Boolean = false, onValue: (Float) -> Unit) {
    val high = range == ServoRange.HIGH
    val v = value.coerceIn(if (signed) -max else 0f, max)
    var typing by remember { mutableStateOf(false) }
    fun clamp(d: Float) = d.coerceIn(if (signed) -max else 0f, max)
    fun round(d: Float): Float = if (!high) (d / 5f).roundToInt() * 5f else {
        val t = kotlin.math.abs(d) / 360f
        val step = when { t < 10f -> 0.1f; t < 100f -> 1f; else -> 5f }
        kotlin.math.sign(d) * (t / step).roundToInt() * step * 360f
    }
    val toPos = { d: Float -> val f = d / max; if (high) kotlin.math.sign(f) * kotlin.math.sqrt(kotlin.math.abs(f)) else f }
    val fromPos = { p: Float -> (if (high) kotlin.math.sign(p) * p * p else p) * max }
    val step = if (high) (if (kotlin.math.abs(v) < 10 * 360f) 36f else 360f) else 1f

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(72.dp))
            Text(
                fmtAngle(v, range, signed) + if (!high) "  · ${"%.2f".format(kotlin.math.abs(v) / 360f)} turns" else "",
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { typing = true }.padding(vertical = 4.dp),
            )
            IconButton({ onValue(clamp(v - step)) }, Modifier.size(36.dp)) { Text("−", fontSize = 20.sp, color = Accent) }
            IconButton({ onValue(clamp(v + step)) }, Modifier.size(36.dp)) { Text("+", fontSize = 20.sp, color = Accent) }
        }
        Slider(toPos(v), { onValue(clamp(round(fromPos(it)))) }, valueRange = (if (signed) -1f else 0f)..1f)
    }
    if (typing) {
        val unit = if (high) "turns" else "degrees"
        val shown = if (high) "%.1f".format(v / 360f) else v.roundToInt().toString()
        TextInputDialog("$label ($unit)", shown, onDismiss = { typing = false }) { t ->
            t.replace(',', '.').replace('−', '-').toFloatOrNull()?.let { onValue(clamp(if (high) it * 360f else it)) }
            typing = false
        }
    }
}

/** Drag to move the servo live with the current settings; springs back to centre. */
@Composable
private fun ServoTester(mod: HubModule, b: Binding, linked: Boolean) {
    // same mapping as a servo slider on the driving screen: rests at the centre's spot in the travel
    val z = b.servoZeroAt()
    var p by remember(z) { mutableFloatStateOf(z) }
    val v = sliderValue(p, z)
    val ref = MkHub.ChannelRef(mod, b.channel)
    fun send(x: Float) = MkHub.applyOutputs(mapOf(ref to (if (b.invert) -x else x)), mapOf(ref to b.servoCfg()))
    DisposableEffect(mod.id, b.channel) { onDispose { MkHub.stopAll() } }
    val x = if (b.invert) -v else v
    val targetDeg = b.servoCenterDeg + if (x >= 0f) x * b.plusDeg else x * b.minusDeg
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Test", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Text(
                if (linked) "drag to move · target ${fmtAngle(targetDeg, b.servoRange)}" else "connect the hub to test",
                color = TextDim, fontSize = 12.sp,
            )
        }
        Slider(
            p, { p = it; if (linked) send(sliderValue(it, z)) },
            onValueChangeFinished = { p = z; if (linked) send(0f) },
            valueRange = 0f..1f, enabled = linked,
        )
    }
}
