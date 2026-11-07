package io.github.matakobrynky.opencrown.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import io.github.matakobrynky.opencrown.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matakobrynky.opencrown.data.*
import io.github.matakobrynky.opencrown.mk.MkHub

data class BtStatus(
    val supported: Boolean,
    val permissionsGranted: Boolean,
    val enabled: Boolean,
    val advertisingSupported: Boolean,
    val fix: () -> Unit,
) {
    val problem: String?
        get() = when {
            !supported -> "This phone has no Bluetooth LE."
            !permissionsGranted -> "Allow \"Nearby devices\" so the app can talk to your modules."
            !enabled -> "Bluetooth is off."
            else -> null
        }
    val fixable get() = supported && (!permissionsGranted || !enabled)
}

@Composable
fun HomeScreen(status: BtStatus, onInspector: () -> Unit = {}, onOpen: (String, Boolean) -> Unit) {
    var editModule by remember { mutableStateOf<HubModule?>(null) }
    var renameProfile by remember { mutableStateOf<Profile?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(onInspector) }
        status.problem?.let { msg -> item { ProblemBanner(msg, status.fixable, status.fix) } }
        MkHub.lastError?.let { e -> item { ErrorWithLog(e) } }

        item { SectionTitle("HUBS") { editModule = HubModule(name = "Hub ${Repo.modules.size + 1}") } }
        if (Repo.modules.isEmpty()) item { EmptyHint("Tap Add to find your H4.0 / H6.0 hub.") }
        Repo.modules.forEach { m ->
            item(key = "m" + m.id) {
                ModuleCard(m, onEdit = { editModule = m }, onDelete = {
                    confirmDelete = "Delete hub \"${m.name}\"?" to { Repo.deleteModule(m.id) }
                })
            }
        }
        item { HowToConnect() }

        item {
            SectionTitle("PROFILES") {
                val p = Repo.defaultProfile("Profile ${Repo.profiles.size + 1}", Repo.modules.firstOrNull()?.id, Repo.profiles.size)
                Repo.upsertProfile(p); onOpen(p.id, true)
            }
        }
        if (Repo.profiles.isEmpty()) item { EmptyHint("Create a profile and lay out your own controls.") }
        Repo.profiles.chunked(2).forEach { row ->
            item(key = "p" + row.first().id) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { p ->
                        ProfileCard(
                            p, Modifier.weight(1f),
                            onOpen = { onOpen(p.id, false) },
                            onEdit = { onOpen(p.id, true) },
                            onRename = { renameProfile = p },
                            onDuplicate = { Repo.duplicate(p) },
                            onDelete = { confirmDelete = "Delete profile \"${p.name}\"?" to { Repo.deleteProfile(p.id) } },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item { VersionLabel() }
        item { Spacer(Modifier.height(16.dp)) }
    }

    editModule?.let { m -> ModuleDialog(m, onDismiss = { editModule = null }) { Repo.upsertModule(it); MkHub.applyStall(it); editModule = null } }
    renameProfile?.let { p ->
        TextInputDialog("Rename profile", p.name, onDismiss = { renameProfile = null }) {
            Repo.upsertProfile(p.copy(name = it)); renameProfile = null
        }
    }
    confirmDelete?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(msg) },
            confirmButton = { TextButton({ action(); confirmDelete = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
            containerColor = Surface2,
        )
    }
}

@Composable
private fun Header(onInspector: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var themeMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Image(
            painterResource(R.drawable.wordmark), "OpenCrown",
            Modifier.weight(1f).height(22.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        )
        Spacer(Modifier.width(4.dp))
        Box {
            IconButton({ themeMenu = true }) {
                Icon(if (Ui.scheme.dark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, "Theme", tint = TextDim)
            }
            DropdownMenu(themeMenu, { themeMenu = false }, containerColor = Surface2) {
                ThemeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        { Text(mode.label, color = if (Ui.mode == mode) Accent else Ink) },
                        { themeMenu = false; Ui.setMode(ctx, mode) },
                        leadingIcon = {
                            Icon(when (mode) {
                                ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                ThemeMode.DARK -> Icons.Outlined.DarkMode
                            }, null, tint = if (Ui.mode == mode) Accent else TextDim)
                        },
                    )
                }
            }
        }
        IconButton(onInspector) { Icon(Icons.Filled.BluetoothSearching, "Bluetooth inspector", tint = TextDim) }
    }
}

@Composable
private fun VersionLabel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null } ?: "" }
    Text(v, color = TextDim.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun SectionTitle(text: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = TextDim, modifier = Modifier.weight(1f))
        AddPill(onAdd)
    }
}

@Composable
fun AddPill(onClick: () -> Unit, label: String = "Add") {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(Pill).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = Accent)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = TextDim, modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp))
}

@Composable
fun ProblemBanner(msg: String, fixable: Boolean, fix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Danger.copy(alpha = 0.12f))
            .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.BluetoothDisabled, null, tint = Danger)
        Spacer(Modifier.width(10.dp))
        Text(msg, Modifier.weight(1f), fontSize = 14.sp)
        if (fixable) TextButton(fix) { Text("Fix", color = Danger, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun StateDot(state: MkHub.State) {
    val c = when (state) { MkHub.State.ACTIVE -> Linked; MkHub.State.CONNECTING -> Orange; else -> Line }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
private fun ModuleCard(m: HubModule, onEdit: () -> Unit, onDelete: () -> Unit) {
    val state = MkHub.state(m)
    var menu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, if (state == MkHub.State.ACTIVE) Linked.copy(alpha = if (Ui.scheme.dark) 0.6f else 1f) else Line),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(if (Ui.scheme.dark) Surface2 else Bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.type.short, color = Accent, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Accent, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(state); Spacer(Modifier.width(6.dp))
                    Text(
                        (if (m.type.bluetoothHub) m.type.label else "${m.type.label} #${m.index + 1}") + " · " + when (state) {
                            MkHub.State.ACTIVE -> "connected" + motorSummary(m)
                            MkHub.State.CONNECTING -> "connecting…"; else -> if (m.type.bluetoothHub && m.address == null) "no hub picked" else "idle"
                        },
                        color = TextDim, fontSize = 12.sp,
                    )
                }
            }
            when (state) {
                MkHub.State.IDLE -> Button(
                    { if (m.type.bluetoothHub && m.address == null) onEdit() else MkHub.connect(m) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1C222D)),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) { Text("Connect", fontWeight = FontWeight.SemiBold) }
                MkHub.State.CONNECTING -> CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 3.dp)
                MkHub.State.ACTIVE -> OutlinedButton(
                    { MkHub.disconnect(m) }, contentPadding = PaddingValues(horizontal = 14.dp),
                    border = BorderStroke(1.dp, Accent), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                ) { Text("Disconnect") }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = TextDim) }
                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                    DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text("Reconnect") }, { menu = false; MkHub.disconnect(m); MkHub.connect(m) }, leadingIcon = { Icon(Icons.Filled.Bluetooth, null) })
                    DropdownMenuItem({ Text("Copy log") }, {
                        menu = false
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("OpenCrown log", MkHub.logText(ctx)))
                        android.widget.Toast.makeText(ctx, "Log copied, paste it in a message", android.widget.Toast.LENGTH_SHORT).show()
                    }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                    DropdownMenuItem({ Text("Delete", color = Danger) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Danger) })
                }
            }
        }
    }
}

@Composable
private fun HowToConnect() {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Soft)
            .clickable { open = !open }.padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("How to connect a hub", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = TextDim)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Switch the H4.0 / H6.0 hub on. Fully close MK Plus — a hub only talks to one phone at a time.\n" +
                    "2. Tap Add, then Find hub. Your hub appears as YX-01 (H4.0) or YX-02 (H6.0).\n" +
                    "3. Tap Connect. Ports A–D (H4.0) or A–F (H6.0) follow the hub's port order.\n" +
                    "4. If something misbehaves, tap ⋮ on the hub → Copy log.",
                color = TextDim, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun ProfileCard(
    p: Profile, modifier: Modifier,
    onOpen: () -> Unit, onEdit: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
) {
    val color = paletteColor(p.color)
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(20.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(92.dp)
                .background(Brush.linearGradient(listOf(lerp(CanvasBg, color, if (Ui.scheme.dark) 0.32f else 0.22f), CanvasBg)))
        ) {
            MiniLayout(p, color, Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 36.dp))
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Ink) }
                DropdownMenu(menu, { menu = false }, containerColor = Surface2) {
                    DropdownMenuItem({ Text("Edit layout") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Outlined.DashboardCustomize, null) })
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; onDuplicate() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    DropdownMenuItem({ Text("Delete", color = Danger) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Danger) })
                }
            }
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, color = Ink)
                Text(if (p.widgets.size == 1) "1 control" else "${p.widgets.size} controls", color = TextDim, fontSize = 13.sp)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Drive", tint = Color.Black)
            }
        }
    }
}

/** Tiny wireframe preview of the profile's layout. */
@Composable
private fun MiniLayout(p: Profile, color: Color, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val w = maxWidth; val h = maxHeight
        p.widgets.forEach { wd ->
            val shape = if (wd.type == WidgetType.JOYSTICK) CircleShape else RoundedCornerShape(4.dp)
            Box(
                Modifier.offset(w * wd.x, h * wd.y)
                    .size(if (wd.type == WidgetType.JOYSTICK) minOf(w * wd.w, h * wd.h) else w * wd.w, if (wd.type == WidgetType.JOYSTICK) minOf(w * wd.w, h * wd.h) else h * wd.h)
                    .border(1.dp, paletteColor(wd.color).copy(alpha = 0.8f), shape)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleDialog(initial: HubModule, onDismiss: () -> Unit, onSave: (HubModule) -> Unit) {
    var m by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(if (Repo.module(initial.id) == null) "Add hub" else "Edit hub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(m.name, { m = m.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                Text("TYPE", style = MaterialTheme.typography.labelSmall, color = TextDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubType.entries.forEach { t ->
                        FilterChip(m.type == t, { m = m.copy(type = t) }, label = { Text("${t.label} (${t.channels} ch)") })
                    }
                }
                if (m.type.bluetoothHub) {
                    Text("BLUETOOTH HUB", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.address ?: "None picked yet", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            color = if (m.address == null) TextDim else Ink, modifier = Modifier.weight(1f))
                        Button({ picking = true }) { Icon(Icons.Filled.BluetoothSearching, null, Modifier.size(16.dp)); Text(" Find hub") }
                    }
                    Text("STALL PROTECTION", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(StallMode.AUTO, StallMode.ON, StallMode.KEEP).forEach { s ->
                            val sel = m.stall == s || (s == StallMode.AUTO && m.stall == StallMode.OFF)
                            FilterChip(sel, { m = m.copy(stall = s) }, label = { Text(s.label) })
                        }
                    }
                    Text(
                        when (m.stall) {
                            StallMode.AUTO, StallMode.OFF -> "Sent at every connect, and right away when you tap Save. The hub won't cut a blocked motor, so don't leave one stalled."
                            StallMode.ON -> "What MK Plus sends at every connect. The hub cuts motors it thinks are blocked; PF motors (no sensor) get cut almost immediately."
                            StallMode.KEEP -> "Sends nothing (like version 1.3). The hub keeps whatever it was last told, e.g. by MK Plus."
                        },
                        color = TextDim, fontSize = 12.sp,
                    )
                } else {
                    Text("MODULE NUMBER", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..2).forEach { i -> FilterChip(m.index == i, { m = m.copy(index = i) }, label = { Text("#${i + 1}") }) }
                    }
                    Text(
                        "Classic modules have the older Power Functions plugs (no pins). " +
                            if (m.type == HubType.MK4 || m.type == HubType.MK4OO)
                                "There are two kinds of 4.0 box: if one doesn't react, try the other 4.0 type."
                            else "",
                        color = TextDim, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onSave(m.copy(name = m.name.ifBlank { "Hub" })) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
    if (picking) HubPickerDialog(onDismiss = { picking = false }) { d ->
        m = m.copy(address = d.address, type = d.hallType ?: m.type,
            name = if (m.name.startsWith("Hub ") && d.name != null) (d.hallType?.label ?: d.name) else m.name)
        picking = false
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface2,
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ onSave(t.ifBlank { initial }) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorWithLog(msg: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Danger.copy(alpha = 0.12f))
            .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BluetoothDisabled, null, tint = Danger)
            Spacer(Modifier.width(10.dp))
            Text(msg, Modifier.weight(1f), fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton({
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("OpenCrown log", MkHub.logText(ctx)))
                android.widget.Toast.makeText(ctx, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("Copy log", color = Danger) }
            TextButton({ MkHub.lastError = null }) { Text("Dismiss", color = TextDim) }
        }
    }
}

/** " · smart motor on A, C" from the hub's heartbeat, or "" until known. */
private fun motorSummary(m: HubModule): String {
    if (!m.type.bluetoothHub) return ""
    val known = (0 until m.type.channels).mapNotNull { p -> MkHub.smartMotor[MkHub.posKey(m, p)]?.let { p to it } }
    if (known.isEmpty()) return ""
    val smart = known.filter { it.second }.map { channelName(it.first) }
    return if (smart.isEmpty()) " · no smart motors" else " · smart: " + smart.joinToString(" ")
}
