package io.github.matakobrynky.opencrown.mk

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Connects to any BLE device and lists its services, so unknown hubs can be identified. */
@SuppressLint("MissingPermission")
object GattInspector {
    data class Char(val service: UUID, val uuid: UUID, val props: Int) {
        val propText get() = buildList {
            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-resp")
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }.joinToString(", ")
        val writable get() = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    var status by mutableStateOf("idle")
    var address by mutableStateOf<String?>(null)
    val chars = mutableStateListOf<Char>()
    val log = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val pendingNotify = ArrayDeque<BluetoothGattCharacteristic>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun add(s: String) { main.post { log.add("${time.format(Date())}  $s"); if (log.size > 300) log.removeAt(0) } }

    fun open(ctx: Context, addr: String) {
        close()
        address = addr; chars.clear(); log.clear(); status = "connecting…"
        val dev = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(addr) ?: return
        gatt = dev.connectGatt(ctx.applicationContext, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; status = "idle"
    }

    @Suppress("DEPRECATION")
    fun write(c: Char, value: ByteArray) {
        val g = gatt ?: return
        val ch = g.getService(c.service)?.getCharacteristic(c.uuid) ?: return
        val type = if (c.props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
        else { ch.writeType = type; ch.value = value; g.writeCharacteristic(ch) }
        add("→ ${short(c.uuid)}  ${Lwp.hex(value)}${if (ok) "" else "  (FAILED)"}")
    }

    fun short(u: UUID) = u.toString().let { if (it.endsWith("-0000-1000-8000-00805f9b34fb")) it.substring(4, 8) else it }

    @Suppress("DEPRECATION")
    private fun nextNotify() {
        val g = gatt ?: return
        val c = pendingNotify.removeFirstOrNull() ?: run { status = "connected"; return }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Lwp.CCCD) ?: run { nextNotify(); return }
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { d.value = v; g.writeDescriptor(d) }
        if (!ok) nextNotify()
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) {
                add("connected (status $st), discovering services…")
                main.post { status = "discovering…" }
                main.postDelayed({ g.discoverServices() }, 300)
            } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                add("disconnected (status $st)")
                main.post { status = "disconnected" }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                chars.clear()
                var reads = 0
                for (s in g.services) {
                    add("service ${short(s.uuid)}")
                    for (c in s.characteristics) {
                        val ch = Char(s.uuid, c.uuid, c.properties)
                        chars.add(ch)
                        add("   char ${short(c.uuid)}  [${ch.propText}]")
                        if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                            pendingNotify.addLast(c)
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) main.postDelayed({ try { g.readCharacteristic(c) } catch (_: Exception) {} }, 3000L + 250L * reads++)
                    }
                }
                nextNotify()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { main.post { nextNotify() } }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) got(c.uuid, c.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) = got(c.uuid, v)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (Build.VERSION.SDK_INT < 33) add("read ${short(c.uuid)} = ${Lwp.hex(c.value ?: byteArrayOf())} \"${printable(c.value)}\"")
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, st: Int) {
            add("read ${short(c.uuid)} = ${Lwp.hex(v)} \"${printable(v)}\"")
        }
    }

    private fun printable(v: ByteArray?) = v?.map { val c = it.toInt().toChar(); if (c in ' '..'~') c else '.' }?.joinToString("") ?: ""

    private fun got(u: UUID, v: ByteArray) {
        val decoded = if (u == Lwp.CHARACTERISTIC) "   (${Lwp.describe(v)})" else ""
        add("← ${short(u)}  ${Lwp.hex(v)}$decoded")
    }
}
