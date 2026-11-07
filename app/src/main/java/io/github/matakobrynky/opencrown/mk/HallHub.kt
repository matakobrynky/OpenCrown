package io.github.matakobrynky.opencrown.mk

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Bluetooth connection to one Mould King H4.0 / H6.0 hub.
 *
 * Sequence (as the official app does it): connect GATT → discover → MTU 512 →
 * enable notifications on …ae3c → send the connect message every second until the hub
 * answers → send speed ("4") / position ("3") messages on …ae3b when controls change,
 * plus a heartbeat ("C") every second.
 */
@SuppressLint("MissingPermission")
class HallHub(
    private val context: Context,
    val address: String,
    val ports: Int,
    private val onState: (MkHub.State, String?) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onPosition: (port: Int, ticks: Int) -> Unit = { _, _ -> },
    /** stall protection to send at connect: true = on, false = off, null = leave the hub alone */
    private val stallOn: () -> Boolean? = { false },
) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private var notifyCh: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var closed = false
    private var linked = false          // hub acknowledged our connect message
    private var ready = false           // GATT side set up
    private var connectTries = 0

    private val values = FloatArray(ports)              // speed-mode ports, -1..1
    private val targets = arrayOfNulls<Int>(ports)       // servo-mode ports: target ticks
    private val servoSpeeds = IntArray(ports)
    private val limited = BooleanArray(ports)               // speed ports the hub stops at their end points
    private val ends = arrayOfNulls<Pair<Int, Int>>(ports)    // those end points (raw), sent with cmd "9"
    private val lastEnds = arrayOfNulls<Pair<Int, Int>>(ports)
    private var lastSpeedSent: String? = null
    private var lastAngleSent: String? = null
    private var lastSendTime = 0L
    private val pendingPos = ArrayDeque<Int>()   // ports we asked for, in order

    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private val rx = StringBuilder()
    private var lastHeartbeat: String? = null

    fun connect() {
        val dev = try { context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (dev == null) { onState(MkHub.State.IDLE, "Bad hub address $address"); return }
        onState(MkHub.State.CONNECTING, null)
        log("connecting to $address")
        gatt = if (Build.VERSION.SDK_INT >= 26) dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
               else dev.connectGatt(context, false, cb)
        main.postDelayed(timeout, 20000)
    }

    /**
     * Set every port at once. A port with a non-null [target] is in servo mode and is
     * driven to that position at [speed] (hub units); the rest run at [speeds] -1..1.
     * A speed port with [ends] runs with end stops: the hub stops it at those positions by itself
     * (MK Plus "limit" flag + cmd "9", as its 17075 crane does for the boom).
     */
    fun update(speeds: FloatArray, targets: Array<Int?>, servoSpeeds: IntArray, ends: Array<Pair<Int, Int>?> = arrayOfNulls(ports)) {
        main.post {
            for (p in 0 until ports) {
                val t = targets.getOrNull(p)
                this.targets[p] = t
                this.values[p] = if (t != null) 0f else speeds.getOrElse(p) { 0f }
                this.servoSpeeds[p] = servoSpeeds.getOrElse(p) { 32767 }
                val e = if (t == null) ends.getOrNull(p) else null
                this.ends[p] = e
                this.limited[p] = e != null
            }
            flush(false)
        }
    }

    /** Send the stall-protection setting now (when connected). */
    fun applyStall() { main.post { if (linked) sendStall() } }

    private fun sendStall() {
        val on = stallOn() ?: return
        val msg = HallProtocol.stallProtection(on)
        enqueue(msg); log("→ $msg (stall protection ${if (on) "on" else "off"})")
    }

    /** Make the current position of [port] the hub's zero (official "置0"). */
    fun zero(port: Int) {
        if (port !in 0 until ports) return
        main.post { if (linked) { enqueue(HallProtocol.setZero(port)); log("→ ${HallProtocol.setZero(port)}") } }
    }

    /** Ask the hub where [port] is; the answer arrives through onPosition. */
    fun requestPosition(port: Int) {
        if (port !in 0 until ports) return
        main.post {
            if (!linked) return@post
            while (pendingPos.size >= 4) pendingPos.removeFirst()   // hub never answered those
            pendingPos.addLast(port); enqueue(HallProtocol.readPosition(port))
        }
    }

    fun close() {
        if (closed) return
        if (ready) {
            // stop every motor and say goodbye before hanging up
            values.fill(0f); targets.fill(null)
            enqueue(HallProtocol.speed(values)); enqueue(HallProtocol.disconnect())
            main.postDelayed({ hangUp() }, 300)
        } else hangUp()
        closed = true
    }

    private fun hangUp() {
        main.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; ready = false; linked = false
    }

    private val timeout = Runnable {
        if (!linked && !closed) {
            close()
            onState(MkHub.State.IDLE, "Hub didn't answer. Switch it on, close MK Plus, and try again.")
        }
    }

    // ---------- sending ----------

    /**
     * Send what changed. Speed ports go in a "4" message (servo ports as 0, like MK Plus);
     * servo ports go in a "3" message (other ports "left alone"), sent only when a target moves.
     */
    private fun flush(force: Boolean) {
        if (!linked || closed) return
        val now = System.currentTimeMillis()
        // don't flood the hub: at most one round every 40 ms, newest values win
        if (!force && now - lastSendTime < 40) {
            main.removeCallbacks(pendingFlush); main.postDelayed(pendingFlush, 40); return
        }
        // end points first, so the hub knows them before a limited port starts moving
        var endsSent = false
        for (p in 0 until ports) {
            val e = ends[p] ?: continue
            if (e == lastEnds[p]) continue
            val msg = HallProtocol.endPoints(p, e.first, e.second)
            enqueue(msg); log("→ $msg (end stops, port ${'A' + p})")
            lastEnds[p] = e; endsSent = true
        }
        val speedMsg = HallProtocol.speed(values, limited)
        val hasServo = targets.any { it != null }
        val angleMsg = if (hasServo) HallProtocol.angle(targets, servoSpeeds) else null
        val speedChanged = speedMsg != lastSpeedSent
        val angleChanged = angleMsg != null && angleMsg != lastAngleSent
        if (!force && !speedChanged && !angleChanged && !endsSent) return
        lastSendTime = now
        val anySpeed = values.any { it != 0f }
        if (speedChanged || (force && (anySpeed || !hasServo))) {
            lastSpeedSent = speedMsg; enqueue(speedMsg); log("→ $speedMsg" + if (speedChanged) "" else " (repeat)")
        }
        // Position message only when a servo target changes (MK Plus does the same). Re-sending it
        // after every speed message stopped the speed-mode ports: they only moved between messages.
        if (angleMsg != null && angleChanged) {
            lastAngleSent = angleMsg; enqueue(angleMsg); log("→ $angleMsg" + if (angleChanged) "" else " (repeat)")
        }
    }
    private val pendingFlush = Runnable { flush(false) }

    /** Heartbeat every second, as the official app does; keeps running motors alive too. */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (closed || !linked) return
            enqueue(HallProtocol.heartbeat())
            if (values.any { it != 0f }) flush(true)
            main.postDelayed(this, 1000)
        }
    }

    private fun enqueue(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val chunk = (mtu - 3).coerceAtLeast(20)
        var i = 0
        while (i < bytes.size) { queue.addLast(bytes.copyOfRange(i, minOf(bytes.size, i + chunk))); i += chunk }
        main.post { pump() }
    }

    private fun pump() {
        if (writing || !ready) return
        val v = queue.removeFirstOrNull() ?: return
        writing = true
        if (!write(v)) writing = false
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 300)
    }
    private val watchdog = Runnable { if (writing) { writing = false; pump() } }

    @Suppress("DEPRECATION")
    private fun write(v: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeCh ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; c.value = v; g.writeCharacteristic(c) }
        } catch (e: Exception) { Log.w(TAG, "write", e); false }
    }

    // ---------- handshake ----------

    private val connectRetry = object : Runnable {
        override fun run() {
            if (closed || linked) return
            connectTries++
            enqueue(HallProtocol.connect())
            log("→ ${HallProtocol.connect()} (try $connectTries)")
            if (connectTries >= 4) {
                // Some firmware never answers; drive anyway after 4 tries.
                becomeLinked("no reply to connect, continuing")
            } else main.postDelayed(this, 1000)
        }
    }

    private fun becomeLinked(why: String) {
        if (linked) return
        linked = true
        main.removeCallbacks(timeout)
        log("linked ($why), $ports ports")
        enqueue(HallProtocol.heartbeat())
        sendStall()
        onState(MkHub.State.ACTIVE, null)
        lastSpeedSent = null; lastAngleSent = null; lastEnds.fill(null)
        flush(true)
        main.postDelayed(keepAlive, 1000)
    }

    private fun onReply(r: HallProtocol.Reply) {
        // log every reply; heartbeat replies only when they change
        if (r.cmd != 'C' || r.data != lastHeartbeat) log("← T${r.cmd}${r.data}W")
        if (r.cmd == 'C') lastHeartbeat = r.data
        if (!linked) becomeLinked("hub replied")
        if (r.cmd == 'A') {
            val (digit, ticks) = HallProtocol.parsePosition(r.data) ?: return
            // the hub echoes the port either as index or as bitmask; we only ask one at a time
            val port = pendingPos.removeFirstOrNull() ?: digit.takeIf { it < ports } ?: return
            onPosition(port, ticks)
        }
    }

    // ---------- GATT callbacks ----------

    private fun find(g: BluetoothGatt) {
        val svc = g.services.firstOrNull { it.uuid.toString().lowercase().contains(HallProtocol.SERVICE_TAG) }
        val all = g.services.flatMap { it.characteristics }
        writeCh = (svc?.characteristics ?: all).firstOrNull { it.uuid.toString().lowercase().contains(HallProtocol.WRITE_TAG) }
        notifyCh = (svc?.characteristics ?: all).firstOrNull { it.uuid.toString().lowercase().contains(HallProtocol.NOTIFY_TAG) }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt) {
        val c = notifyCh ?: run { startHandshake(); return }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Lwp.CCCD) ?: run { startHandshake(); return }
        val ok = if (Build.VERSION.SDK_INT >= 33)
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        if (!ok) startHandshake()
    }

    private fun startHandshake() {
        if (ready) return
        ready = true
        main.post(connectRetry)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (closed) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> { log("GATT connected"); main.postDelayed({ g.discoverServices() }, 400) }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val was = linked
                        hangUp(); closed = true
                        onState(MkHub.State.IDLE, if (was) "Hub disconnected" else "Couldn't connect (status $status). Move closer and try again.")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (closed) return@post
                find(g)
                if (writeCh == null) {
                    val list = g.services.joinToString { GattInspector.short(it.uuid) }
                    close()
                    onState(MkHub.State.IDLE, "This isn't an H4.0/H6.0 hub (no ae3b channel; services: $list).")
                    return@post
                }
                log("found write ${GattInspector.short(writeCh!!.uuid)}, notify ${notifyCh?.let { GattInspector.short(it.uuid) }}")
                if (!g.requestMtu(512)) enableNotify(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post { if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu; log("MTU $mtu"); enableNotify(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { startHandshake() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { writing = false; main.removeCallbacks(watchdog); pump() }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) received(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = received(value)
    }

    private fun received(v: ByteArray) {
        main.post {
            rx.append(String(v, Charsets.US_ASCII))
            HallProtocol.extract(rx).forEach(::onReply)
            if (rx.length > 512) rx.setLength(0)
        }
    }

    private fun log(s: String) { Log.d(TAG, s); onLog(s) }

    companion object { private const val TAG = "HallHub" }
}
