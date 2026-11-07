package io.github.matakobrynky.opencrown.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matakobrynky.opencrown.mk.BleScanner
import io.github.matakobrynky.opencrown.mk.GattInspector

/** Sorted scan results, hub-looking devices first. */
fun sortedDevices(showAll: Boolean) = BleScanner.found.values
    .filter { showAll || it.looksLikeHub || it.name != null }
    .sortedWith(compareByDescending<BleScanner.Found> { it.looksLikeHub }.thenByDescending { it.rssi })

@Composable
fun DeviceRow(d: BleScanner.Found, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name ?: "(no name)", fontWeight = FontWeight.SemiBold, color = if (d.name == null) TextDim else Ink)
                val badge = d.hallType?.label?.uppercase() ?: if (d.isPoweredUp) "POWERED UP" else null
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ok).padding(4.dp, 1.dp))
                }
            }
            Text(d.address, color = TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text("${d.rssi} dBm", color = if (d.rssi > -70) Ok else if (d.rssi > -85) Orange else TextDim, fontSize = 12.sp)
    }
}

@Composable
fun InspectorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<BleScanner.Found?>(null) }
    var showAll by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        BleScanner.start(ctx)
        onDispose { BleScanner.stop(ctx); GattInspector.close() }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
            IconButton({ if (selected != null) { GattInspector.close(); selected = null; BleScanner.start(ctx) } else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(if (selected == null) "Bluetooth inspector" else (selected?.name ?: "Device"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.weight(1f))
            if (selected != null) {
                Button({ copyReport(ctx, selected!!) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Text(" Copy report") }
            }
        }

        val sel = selected
        if (sel == null) {
            Text("Switch your H4.0/H6.0 hub on and keep it close. Tap it to see what it exposes, then tap Copy report and paste it to me.",
                color = TextDim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (BleScanner.scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  ${BleScanner.found.size} devices nearby", color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Show all", fontSize = 13.sp); Switch(showAll, { showAll = it })
            }
            BleScanner.error?.let { Text(it, color = Danger) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(sortedDevices(showAll), key = { it.address }) { d ->
                    DeviceRow(d) { BleScanner.stop(ctx); selected = d; GattInspector.open(ctx, d.address) }
                }
            }
        } else {
            DeviceDetail(sel)
        }
    }
}

@Composable
private fun DeviceDetail(d: BleScanner.Found) {
    var target by remember { mutableStateOf<GattInspector.Char?>(null) }
    var hex by remember { mutableStateOf("T041AABBW") }
    val listState = rememberLazyListState()
    LaunchedEffect(GattInspector.log.size) { if (GattInspector.log.isNotEmpty()) listState.scrollToItem(GattInspector.log.size - 1) }

    Text("Status: ${GattInspector.status}", color = TextDim, fontSize = 13.sp)
    Text(advertText(d), color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(6.dp))

    val writable = GattInspector.chars.filter { it.writable }
    if (writable.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(10.dp)) {
            Text("SEND BYTES (advanced)", style = MaterialTheme.typography.labelSmall, color = TextDim)
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                writable.take(4).forEach { c ->
                    FilterChip(target == c, { target = c }, label = { Text(GattInspector.short(c.uuid).take(8), fontSize = 11.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(hex, { hex = it }, singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                Spacer(Modifier.width(8.dp))
                Button({
                    val c = target ?: writable.first()
                    val t = hex.trim()
                    val bytes = if (t.startsWith("T") && t.endsWith("W")) t.toByteArray(Charsets.US_ASCII) else parseHex(t)
                    bytes?.let { GattInspector.write(c, it) }
                }) { Text("Send") }
            }
            Text("Text like T041AABBW is sent as an H-hub message (pick the ae3b channel); anything else is read as hex bytes.", color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(ConsoleBg).padding(8.dp),
    ) {
        items(GattInspector.log) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = when {
                "← " in line -> Cyan; "→ " in line -> Orange; "service" in line -> Color(0xFFE9EEF5); else -> Color(0xFF8A94A6)
            })
        }
    }
}

private fun parseHex(s: String): ByteArray? = try {
    s.replace(",", " ").split(" ").filter { it.isNotBlank() }.map { it.removePrefix("0x").toInt(16).toByte() }.toByteArray()
} catch (_: Exception) { null }

private fun advertText(d: BleScanner.Found) = buildString {
    append("addr ${d.address}  rssi ${d.rssi}  connectable ${d.connectable}")
    if (d.serviceUuids.isNotEmpty()) append("\nadv services: ${d.serviceUuids.joinToString()}")
    d.manufacturer.forEach { (k, v) -> append("\nmanufacturer 0x%04X: %s".format(k, v)) }
}

private fun copyReport(ctx: Context, d: BleScanner.Found) {
    val text = buildString {
        appendLine("OpenCrown BLE report")
        appendLine("name: ${d.name}")
        appendLine(advertText(d))
        appendLine("--- characteristics ---")
        GattInspector.chars.forEach { appendLine("${GattInspector.short(it.service)} / ${GattInspector.short(it.uuid)} [${it.propText}]") }
        appendLine("--- log ---")
        GattInspector.log.forEach { appendLine(it) }
    }
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BLE report", text))
    Toast.makeText(ctx, "Report copied — paste it in your message", Toast.LENGTH_LONG).show()
}

/** Scan-and-pick dialog used when adding an H4.0/H6.0 hub. */
@Composable
fun HubPickerDialog(onDismiss: () -> Unit, onPick: (BleScanner.Found) -> Unit) {
    val ctx = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { BleScanner.start(ctx); onDispose { BleScanner.stop(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface2,
        title = { Text("Find your hub") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text("Switch the hub on. H4.0 / H6.0 hubs show up as YX-01 / YX-02 and are marked in green.", color = TextDim, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (BleScanner.scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f)); Text("Show all", fontSize = 13.sp); Switch(showAll, { showAll = it })
                }
                BleScanner.error?.let { Text(it, color = Danger) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sortedDevices(showAll), key = { it.address }) { d -> DeviceRow(d) { onPick(d) } }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

