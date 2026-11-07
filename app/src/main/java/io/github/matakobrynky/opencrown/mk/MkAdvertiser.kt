package io.github.matakobrynky.opencrown.mk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Broadcasts Mould King telegrams for the classic 4.0 / 6.0 modules — the same way MK Plus does:
 * legacy advertising, connectable, low latency, high TX power, manufacturer id 0xFFF0.
 *
 * Being connectable matters: Android then puts the 3-byte "flags" block in front of the
 * manufacturer data, so the hidden RF packet starts at byte 15 of the advertising PDU,
 * which is exactly where MouldKingCrypt's whitening expects it.
 *
 * One advertiser per slot (each MK6 module, the shared MK4 slot…). Phones that can't run
 * several at once fall back to a single advertiser that rotates through the slots.
 * Data changes are applied by stop + start, like the official app.
 */
@SuppressLint("MissingPermission")
class MkAdvertiser(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)

    var onError: ((String) -> Unit)? = null

    private val data = LinkedHashMap<String, ByteArray>()
    private val slots = HashMap<String, Slot>()
    private var multiplex = false
    private var muxIndex = 0

    private val settings: AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .setTimeout(0)
        .build()

    private fun buildData(p: ByteArray) = AdvertiseData.Builder()
        .addManufacturerData(MkProtocol.MANUFACTURER_ID, p)
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .build()

    private inner class Slot(val key: String) {
        var running: ByteArray? = null
        var latest: ByteArray? = null
        var lastStart = 0L
        var stopped = false

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
            override fun onStartFailure(errorCode: Int) {
                main.post { failed(this@Slot, errorCode) }
            }
        }

        fun push(p: ByteArray) {
            latest = p
            apply()
        }

        fun apply() {
            if (stopped) return
            val p = latest ?: return
            if (running contentEquals p) return
            val now = System.currentTimeMillis()
            if (now - lastStart < 40) {         // coalesce rapid changes
                main.removeCallbacks(reapply); main.postDelayed(reapply, 40); return
            }
            val adv = manager?.adapter?.bluetoothLeAdvertiser
            if (adv == null) { onError?.invoke("Bluetooth is off or LE advertising is not supported"); return }
            try {
                if (running != null) adv.stopAdvertising(callback)
                adv.startAdvertising(settings, buildData(p), callback)
                running = p
                lastStart = now
            } catch (e: Exception) {
                Log.w(TAG, "advertise", e)
                onError?.invoke("Advertising failed: ${e.message}")
            }
        }
        private val reapply = Runnable { apply() }

        fun stop() {
            stopped = true
            main.removeCallbacks(reapply)
            try { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) } catch (_: Exception) {}
            running = null
        }
    }

    private fun failed(s: Slot, code: Int) {
        Log.w(TAG, "start failed for ${s.key}: $code")
        s.running = null
        if (code == AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS && !multiplex) enterMultiplex()
        else if (code != AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) onError?.invoke("Bluetooth advertising failed (code $code)")
    }

    private fun enterMultiplex() {
        multiplex = true
        slots.values.forEach { it.stop() }
        slots.clear()
        main.removeCallbacks(muxTick)
        main.post(muxTick)
    }

    private val muxTick = object : Runnable {
        override fun run() {
            if (!multiplex) return
            val keys = data.keys.toList()
            if (keys.isNotEmpty()) {
                muxIndex = (muxIndex + 1) % keys.size
                val s = slots.getOrPut("mux") { Slot("mux") }
                data[keys[muxIndex]]?.let { s.push(it) }
            }
            main.postDelayed(this, 120)
        }
    }

    fun update(key: String, payload: ByteArray) {
        data[key] = payload
        if (multiplex) {
            if (data.size == 1) slots.getOrPut("mux") { Slot("mux") }.push(payload)
            return
        }
        slots.getOrPut(key) { Slot(key) }.push(payload)
    }

    fun remove(key: String) {
        data.remove(key)
        if (!multiplex) slots.remove(key)?.stop()
        else if (data.isEmpty()) slots.remove("mux")?.stop()
    }

    fun stopAll() {
        main.removeCallbacks(muxTick)
        data.clear()
        slots.values.forEach { it.stop() }
        slots.clear()
        multiplex = false
    }

    companion object { private const val TAG = "MkAdvertiser" }
}
