package io.github.matakobrynky.opencrown.mk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Finds nearby Bluetooth LE devices, with everything they advertise. */
@SuppressLint("MissingPermission")
object BleScanner {
    data class Found(
        val address: String,
        val name: String?,
        val rssi: Int,
        val serviceUuids: List<String>,
        val manufacturer: Map<Int, String>,
        val connectable: Boolean,
        val lastSeen: Long,
    ) {
        val isPoweredUp get() = serviceUuids.any { it.startsWith("00001623") } || manufacturer.containsKey(Lwp.LEGO_COMPANY_ID)
        /** Mould King H-series hub type, from the advertised name (see [typeForName]) or service */
        val hallType: io.github.matakobrynky.opencrown.data.HubType? get() = typeForName(name) ?: when {
            serviceUuids.any { it.lowercase().contains("ae3a") || it.lowercase().startsWith("0000af30") } -> io.github.matakobrynky.opencrown.data.HubType.H6
            else -> null
        }
        val looksLikeHub get() = hallType != null || isPoweredUp ||
            (name ?: "").let { n -> listOf("yx-", "mk", "mould", "hub").any { n.lowercase().contains(it) } }
    }

    /**
     * Port count by name, as MK Plus decides it (BleDeviceAll):
     *   YX-01, YX-05 → 4-port hub (BleDevice01 / BleDevice012)
     *   YX-02, YX-06 → 6-port hub (BleDevice02 / BleDevice022)
     * YX-03 / YX-04 are other products with a different protocol.
     */
    fun typeForName(name: String?): io.github.matakobrynky.opencrown.data.HubType? {
        val n = (name ?: "").uppercase()
        return when {
            n.startsWith("YX-01") || n.startsWith("YX-05") -> io.github.matakobrynky.opencrown.data.HubType.H4
            n.startsWith("YX-02") || n.startsWith("YX-06") -> io.github.matakobrynky.opencrown.data.HubType.H6
            else -> null
        }
    }

    val found = mutableStateMapOf<String, Found>()
    var scanning by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    private var cb: ScanCallback? = null

    fun start(ctx: Context) {
        if (scanning) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) { error = "Bluetooth is off"; return }
        error = null
        found.clear()
        val c = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) = add(r)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::add)
            override fun onScanFailed(code: Int) { error = "Scan failed (code $code)"; scanning = false }
        }
        cb = c
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), c)
            scanning = true
        } catch (e: SecurityException) { error = "Allow \"Nearby devices\" permission to scan" }
    }

    fun stop(ctx: Context) {
        val c = cb ?: return
        try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {}
        cb = null; scanning = false
    }

    private fun add(r: ScanResult) {
        val rec = r.scanRecord
        val mfr = HashMap<Int, String>()
        rec?.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) mfr[sa.keyAt(i)] = Lwp.hex(sa.valueAt(i)) }
        val name = rec?.deviceName ?: try { r.device.name } catch (_: SecurityException) { null }
        found[r.device.address] = Found(
            r.device.address, name, r.rssi,
            rec?.serviceUuids?.map { it.uuid.toString() } ?: emptyList(),
            mfr, r.isConnectable, System.currentTimeMillis(),
        )
    }
}
