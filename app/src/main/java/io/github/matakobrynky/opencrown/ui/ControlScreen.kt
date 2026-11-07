package io.github.matakobrynky.opencrown.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.matakobrynky.opencrown.data.*
import io.github.matakobrynky.opencrown.mk.MkHub
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ControlScreen(profileId: String, startEditing: Boolean, status: BtStatus, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember(profileId) { Repo.profiles.first { it.id == profileId } }
    val widgets = remember(profileId) { mutableStateListOf<Widget>().apply { addAll(initial.widgets) } }
    var editing by remember { mutableStateOf(startEditing) }
    var editWidget by remember { mutableStateOf<Widget?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var resetToken by remember { mutableIntStateOf(0) }
    val outputs = remember { mutableStateMapOf<String, Float>() }

    fun persist() {
        Repo.profiles.firstOrNull { it.id == profileId }?.let { Repo.upsertProfile(it.copy(widgets = widgets.toList())) }
    }

    // Landscape, full screen, screen stays on while driving.
    DisposableEffect(Unit) {
        val act = ctx as Activity
        val prev = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ic = WindowCompat.getInsetsController(act.window, act.window.decorView)
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            MkHub.stopAll()
            persist()
            act.requestedOrientation = prev
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ic.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Mix every control's output into module channels and send. Runs as a short loop so that
    // smooth acceleration can change the output over time; the hubs only get
    // a message when the mixed result actually changes.
    val shapers = remember { HashMap<String, io.github.matakobrynky.opencrown.mk.MotorShaper>() }
    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        var lastMix: Map<MkHub.ChannelRef, Float>? = null
        var lastServo: Map<MkHub.ChannelRef, MkHub.ServoCfg>? = null
        var lastEnds: Map<MkHub.ChannelRef, Pair<Int, Int>>? = null
        while (true) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000f).coerceIn(0f, 100f); last = now
            val out = outputs.toMap(); val ws = widgets.toList()
            val mix = HashMap<MkHub.ChannelRef, Float>()
            val servo = HashMap<MkHub.ChannelRef, MkHub.ServoCfg>()
            val live = HashSet<String>()
            for (w in ws) {
                listOf(w.primary to "p", w.secondary to "s").forEach { (b, axis) ->
                    val key = "${w.id}:$axis"
                    val v = out[key] ?: return@forEach          // untouched (or STOP): nothing to send
                    val m = Repo.module(b.moduleId) ?: return@forEach
                    if (b.channel >= m.type.channels) return@forEach
                    val ref = MkHub.ChannelRef(m, b.channel)
                    val isServo = b.mode == OutputMode.SERVO && m.type.bluetoothHub
                    val signed = if (b.invert) -v else v
                    val scaled = if (isServo) signed else {
                        live += key
                        shapers.getOrPut(key) { io.github.matakobrynky.opencrown.mk.MotorShaper() }.step(b, signed, dt)
                    }
                    if (isServo) servo[ref] = b.servoCfg()
                    mix[ref] = (mix[ref] ?: 0f) + scaled
                }
            }
            // end stops are known from the start, even before a control is touched
            val ends = HashMap<MkHub.ChannelRef, Pair<Int, Int>>()
            for (w in ws) for (b in listOf(w.primary, w.secondary)) {
                if (!b.endStops || b.mode != OutputMode.MOTOR) continue
                val m = Repo.module(b.moduleId) ?: continue
                if (!m.type.bluetoothHub || b.channel >= m.type.channels) continue
                ends[MkHub.ChannelRef(m, b.channel)] = b.endPointsRaw()
            }
            // controls that went back to "untouched" (STOP, editing) start from standstill next time
            shapers.keys.retainAll(live)
            if (mix != lastMix || servo != lastServo || ends != lastEnds) {
                MkHub.applyOutputs(mix, servo, ends)
                lastMix = mix; lastServo = servo; lastEnds = ends
            }
            kotlinx.coroutines.delay(16)
        }
    }

    val usedModules = widgets.flatMap { listOf(it.primary.moduleId, it.secondary.moduleId) }
        .mapNotNull { Repo.module(it) }.distinctBy { MkHub.key(it) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ---------- top bar ----------
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(initial.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (editing) Text("Drag to move · pull a corner or pinch to resize · tap to set up",
                    color = TextDim, fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp)
                else usedModules.forEach { m -> ModuleChip(m) }
                status.problem?.let {
                    Text("⚠ Bluetooth", color = Danger, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { status.fix() }.padding(8.dp, 6.dp))
                }
            }
            if (editing) {
                Box {
                    AddPill({ addMenu = true }, "Add control")
                    DropdownMenu(addMenu, { addMenu = false }, containerColor = Surface2) {
                        WidgetType.entries.forEach { t ->
                            DropdownMenuItem({ Text(t.label) }, {
                                addMenu = false
                                val w = newWidget(t, widgets.size)
                                widgets.add(w); editWidget = w
                            }, leadingIcon = { Icon(iconFor(t), null) })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button({ editing = false; persist() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1C222D))) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Done", fontWeight = FontWeight.SemiBold)
                }
            } else {
                IconButton({ editing = true; outputs.clear(); shapers.clear(); resetToken++ }) { Icon(Icons.Outlined.DashboardCustomize, "Edit layout", tint = TextDim) }
                Spacer(Modifier.width(6.dp))
                Button(
                    { outputs.clear(); shapers.clear(); resetToken++; MkHub.stopAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) { Icon(Icons.Filled.Stop, null); Text("STOP", fontWeight = FontWeight.Black) }
            }
        }

        // ---------- canvas ----------
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(CanvasBg)
                .drawBehind {
                    val step = GRID.toPx()
                    val dot = if (editing) Accent.copy(alpha = 0.35f) else GridDot
                    var x = step
                    while (x < size.width) {
                        var y = step
                        while (y < size.height) { drawCircle(dot, 1.6f, Offset(x, y)); y += step }
                        x += step
                    }
                }
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }

            val gridPx = with(density) { GRID.toPx() } / 2f      // snap to half a grid step
            val minPx = with(density) { MIN_SIDE.toPx() }

            if (widgets.isEmpty()) {
                Text("Tap \"Add control\" to build your layout", color = TextDim, modifier = Modifier.align(Alignment.Center))
            }

            widgets.forEach { w ->
                key(w.id) {
                    WidgetHost(
                        w, wPx, hPx, editing, resetToken,
                        onOutput = { axis, v -> outputs["${w.id}:$axis"] = v },
                        gridPx = gridPx, minPx = minPx,
                        onRect = { r ->
                            val i = widgets.indexOfFirst { it.id == w.id }
                            if (i >= 0) widgets[i] = widgets[i].withRect(r, wPx, hPx)
                        },
                        onEdit = { editWidget = widgets.firstOrNull { it.id == w.id } },
                    )
                }
            }
        }
    }

    editWidget?.let { w ->
        WidgetEditorDialog(
            w,
            onDismiss = { editWidget = null },
            onDelete = { widgets.removeAll { it.id == w.id }; editWidget = null; persist() },
            onSave = { nw -> val i = widgets.indexOfFirst { it.id == nw.id }; if (i >= 0) widgets[i] = nw; editWidget = null; persist() },
        )
    }
}

@Composable
private fun ModuleChip(m: HubModule) {
    val st = MkHub.state(m)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Surface2)
            .clickable { if (st == MkHub.State.IDLE) MkHub.connect(m) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(st); Spacer(Modifier.width(6.dp))
        Text(
            if (st == MkHub.State.IDLE) "${m.name} · tap to connect" else m.name,
            fontSize = 12.sp, maxLines = 1, color = if (st == MkHub.State.IDLE) TextDim else Ink,
        )
    }
}

fun iconFor(t: WidgetType) = when (t) {
    WidgetType.JOYSTICK -> Icons.Filled.Gamepad
    WidgetType.SLIDER_V -> Icons.Filled.Height
    WidgetType.SLIDER_H -> Icons.Filled.SwapHoriz
    WidgetType.BUTTON -> Icons.Filled.RadioButtonChecked
    WidgetType.TOGGLE -> Icons.Filled.ToggleOn
}

private fun newWidget(t: WidgetType, n: Int): Widget {
    val mod = Repo.modules.firstOrNull()?.id
    val (w, h) = when (t) {
        WidgetType.JOYSTICK -> 0.2f to 0.45f
        WidgetType.SLIDER_V -> 0.11f to 0.6f
        WidgetType.SLIDER_H -> 0.35f to 0.22f
        WidgetType.BUTTON, WidgetType.TOGGLE -> 0.13f to 0.22f
    }
    val ch = n % 6
    return Widget(
        type = t, x = 0.5f - w / 2, y = 0.5f - h / 2, w = w, h = h, color = n,
        label = when (t) { WidgetType.BUTTON -> "Push"; WidgetType.TOGGLE -> "Switch"; else -> "" },
        primary = Binding(mod, ch), secondary = Binding(mod, (ch + 1) % 6),
        springBack = true,
    )
}

/** Grid of the driving canvas; editing snaps to half of it. */
private val GRID = 28.dp
private val MIN_SIDE = 56.dp

/** A widget's box on the canvas, in pixels. */
data class PxRect(val l: Float, val t: Float, val w: Float, val h: Float) {
    val r get() = l + w
    val b get() = t + h
}

private fun Widget.rectPx(W: Float, H: Float): PxRect {
    var ww = w * W; var hh = h * H
    if (type == WidgetType.JOYSTICK) { val s = minOf(ww, hh); ww = s; hh = s }
    return PxRect(x * W, y * H, ww, hh)
}

private fun Widget.withRect(r: PxRect, W: Float, H: Float) = copy(x = r.l / W, y = r.t / H, w = r.w / W, h = r.h / H)

/** Keep a box inside the canvas and at least [min] on each side (square for joysticks). */
private fun fit(r: PxRect, W: Float, H: Float, min: Float, square: Boolean): PxRect {
    var w = r.w.coerceIn(min, W); var h = r.h.coerceIn(min, H)
    if (square) { val s = minOf(maxOf(w, h), W, H); w = s; h = s }
    return PxRect(r.l.coerceIn(0f, W - w), r.t.coerceIn(0f, H - h), w, h)
}

private fun snap(v: Float, g: Float) = Math.round(v / g) * g

/** Snap position and size to the grid. */
private fun snapRect(r: PxRect, g: Float, W: Float, H: Float, min: Float, square: Boolean): PxRect {
    val w = maxOf(snap(r.w, g), min); val h = maxOf(snap(r.h, g), min)
    return fit(PxRect(snap(r.l, g), snap(r.t, g), w, h), W, H, min, square)
}

@Composable
private fun WidgetHost(
    w: Widget, wPx: Float, hPx: Float, editing: Boolean, resetToken: Int,
    onOutput: (String, Float) -> Unit,
    gridPx: Float, minPx: Float,
    onRect: (PxRect) -> Unit,
    onEdit: () -> Unit,
) {
    val density = LocalDensity.current
    val color = paletteColor(w.color)
    val cur by rememberUpdatedState(w)
    val setRect by rememberUpdatedState(onRect)
    val edit by rememberUpdatedState(onEdit)
    val square = w.type == WidgetType.JOYSTICK
    val box = w.rectPx(wPx, hPx)
    val labelBelow = w.label.isNotEmpty() && w.type != WidgetType.BUTTON && w.type != WidgetType.TOGGLE
    var active by remember { mutableStateOf(false) }   // being moved / resized

    // snap the box where the finger left it (the widget list may not have recomposed yet)
    fun finish(last: PxRect) { setRect(snapRect(last, gridPx, wPx, hPx, minPx, square)); active = false }

    Box(
        Modifier.offset { IntOffset(box.l.roundToInt(), box.t.roundToInt()) }
            .size(with(density) { box.w.toDp() }, with(density) { box.h.toDp() + if (labelBelow) 18.dp else 0.dp })
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(with(density) { box.w.toDp() }, with(density) { box.h.toDp() })) {
                when (w.type) {
                    WidgetType.JOYSTICK -> JoystickControl(color, w.springBack, !editing, resetToken) { x, y ->
                        onOutput("p", x); onOutput("s", y)
                    }
                    WidgetType.SLIDER_V, WidgetType.SLIDER_H -> {
                        // servo sliders rest where the centre lies in the travel (see the editor's travel bar);
                        // speed sliders with different forward / reverse speeds rest in proportion too
                        val b = w.primary
                        val servo = b.mode == OutputMode.SERVO && Repo.module(b.moduleId)?.type?.bluetoothHub == true
                        SliderControl(color, w.type == WidgetType.SLIDER_V, w.springBack, !editing, resetToken,
                            zeroAt = if (servo) b.servoZeroAt() else b.motorZeroAt()) { onOutput("p", it) }
                    }
                    WidgetType.BUTTON, WidgetType.TOGGLE ->
                        ButtonControl(color, w.label, w.type == WidgetType.TOGGLE, !editing, resetToken) { on ->
                            onOutput("p", if (on) w.buttonPct / 100f else 0f)
                        }
                }
            }
            if (labelBelow) {
                Text(w.label.uppercase(), color = color.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 1.5.sp)
            }
        }

        if (editing) {
            Box(
                Modifier.fillMaxSize()
                    .background(Accent.copy(alpha = if (active) 0.14f else 0.05f), RoundedCornerShape(12.dp))
                    .drawBehind {
                        drawRoundRect(
                            Accent, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        )
                    }
                    // one finger: move · two fingers: pinch to resize · tap: settings
                    .pointerInput(w.id, wPx, hPx) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val start = cur.rectPx(wPx, hPx)
                            var pan = Offset.Zero; var zoom = 1f; var moved = false; var last = start
                            do {
                                val ev = awaitPointerEvent()
                                pan += ev.calculatePan(); zoom *= ev.calculateZoom()
                                if (!moved && (pan.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.04f)) { moved = true; active = true }
                                if (moved) {
                                    val nw = start.w * zoom; val nh = start.h * zoom
                                    val cx = start.l + start.w / 2 + pan.x; val cy = start.t + start.h / 2 + pan.y
                                    last = fit(PxRect(cx - nw / 2, cy - nh / 2, nw, nh), wPx, hPx, minPx, square)
                                    setRect(last)
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                            if (moved) finish(last) else edit()
                        }
                    }
            ) {
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(Accent).padding(5.dp, 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = Color.Black, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(bindingSummary(w), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                // corner handles: drag a corner, the opposite one stays put
                for ((ax, ay) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                    val align = when (ax to ay) {
                        0 to 0 -> Alignment.TopStart; 1 to 0 -> Alignment.TopEnd
                        0 to 1 -> Alignment.BottomStart; else -> Alignment.BottomEnd
                    }
                    Box(
                        Modifier.align(align).size(34.dp)
                            .pointerInput(w.id, ax, ay, wPx, hPx) {
                                var start = PxRect(0f, 0f, 0f, 0f); var total = Offset.Zero; var last = start
                                detectDragGestures(
                                    onDragStart = { start = cur.rectPx(wPx, hPx); last = start; total = Offset.Zero; active = true },
                                    onDragEnd = { finish(last) }, onDragCancel = { finish(last) },
                                ) { ch, d ->
                                    ch.consume(); total += d
                                    var l = start.l; var t = start.t; var r = start.r; var b = start.b
                                    if (ax == 0) l = (l + total.x).coerceIn(0f, r - minPx) else r = (r + total.x).coerceIn(l + minPx, wPx)
                                    if (ay == 0) t = (t + total.y).coerceIn(0f, b - minPx) else b = (b + total.y).coerceIn(t + minPx, hPx)
                                    if (square) {
                                        // keep it round: grow by the bigger change, anchored at the opposite corner
                                        val lim = minOf(if (ax == 0) start.r else wPx - start.l, if (ay == 0) start.b else hPx - start.t)
                                        val s = maxOf(r - l, b - t).coerceIn(minPx, lim)
                                        if (ax == 0) l = start.r - s else r = start.l + s
                                        if (ay == 0) t = start.b - s else b = start.t + s
                                    }
                                    last = PxRect(l, t, r - l, b - t)
                                    setRect(last)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Accent).border(2.dp, CanvasBg, CircleShape))
                    }
                }
            }
        }
    }
}

private fun bindingSummary(w: Widget): String {
    fun b(x: Binding): String {
        val m = Repo.module(x.moduleId) ?: return "—"
        return m.name.take(6) + " " + channelName(x.channel) + if (x.mode == OutputMode.SERVO && m.type.bluetoothHub) " ⦟" else ""
    }
    return if (w.type == WidgetType.JOYSTICK) "X ${b(w.primary)} Y ${b(w.secondary)}" else b(w.primary)
}
