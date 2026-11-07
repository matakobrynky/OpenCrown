package io.github.matakobrynky.opencrown.mk

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.matakobrynky.opencrown.data.HubModule
import io.github.matakobrynky.opencrown.data.HubType

/**
 * Routes channel values to the right transport:
 *  - H4.0 / H6.0 hubs: a Bluetooth (GATT) connection using Mould King's "hall" text protocol
 *  - classic 4.0 / 6.0 modules: BLE advertising telegrams
 */
object MkHub {
    enum class State { IDLE, CONNECTING, ACTIVE }

    data class ChannelRef(val module: HubModule, val channel: Int)

    /** Servo (position) settings for one port, in hub encoder ticks. */
    data class ServoCfg(val center: Int, val minus: Int, val plus: Int, val speedPct: Int)

    private const val CONNECT_MS = 1600L
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var adv: MkAdvertiser? = null

    private val mk6 = Array(3) { FloatArray(6) }
    private val mk4 = FloatArray(12)
    private val mk4oo = FloatArray(12)      // older on/off 4.0 boxes, 3 modules × 4
    private val gattHubs = HashMap<String, HallHub>()

    /** keyed by [key] */
    val states = mutableStateMapOf<String, State>()
    val battery = mutableStateMapOf<String, Int>()
    /** last reported motor positions in ticks, keyed by [posKey] */
    val positions = mutableStateMapOf<String, Int>()
    fun posKey(m: HubModule, port: Int) = key(m) + ":" + port
    /** true = smart (hall) motor detected on that port, false = PF motor / nothing; keyed by [posKey] */
    val smartMotor = mutableStateMapOf<String, Boolean>()
    var lastError by mutableStateOf<String?>(null)
    /** recent connection events, for troubleshooting */
    val log = androidx.compose.runtime.mutableStateListOf<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private fun addLog(s: String) { log.add(timeFmt.format(java.util.Date()) + "  " + s); if (log.size > 400) log.removeAt(0) }

    /** Everything useful for troubleshooting, as text for the clipboard. */
    fun logText(ctx: Context): String {
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        val hubs = io.github.matakobrynky.opencrown.data.Repo.modules.joinToString("\n") { "  ${it.name}: ${it.type.short} ${it.address ?: ""} stall=${it.stall} state=${state(it)}" }
        return "OpenCrown $ver, Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\nHubs:\n$hubs\n\n" + log.joinToString("\n")
    }

    fun init(ctx: Context) {
        if (adv != null) return
        app = ctx.applicationContext
        adv = MkAdvertiser(app).also { it.onError = { e -> lastError = e } }
    }

    fun key(m: HubModule) = when (m.type) {
        HubType.H6, HubType.H4 -> "BT_" + (m.address ?: m.id)
        HubType.MK4 -> "MK4"
        HubType.MK4OO -> "MK4OO"
        HubType.MK6 -> "MK6_${m.index}"
    }

    fun state(m: HubModule) = states[key(m)] ?: State.IDLE

    fun connect(module: HubModule) {
        lastError = null
        val m = fixHubType(module)
        val k = key(m)
        if (m.type.bluetoothHub) {
            val addr = m.address
            if (addr == null) { lastError = "Pick the hub first: edit \"${m.name}\" and tap Find hub."; return }
            gattHubs.remove(addr)?.close()
            var self: HallHub? = null
            val hub = HallHub(app, addr, m.type.channels,
                onState = { s, err ->
                    if (gattHubs[addr] === self) {   // ignore late events from a replaced connection
                        if (s == State.IDLE) {
                            states.remove(k); gattHubs.remove(addr)
                            smartMotor.keys.filter { it.startsWith("$k:") }.forEach { smartMotor.remove(it) }
                        } else states[k] = s
                        if (err != null) { lastError = "${m.name}: $err"; addLog("${m.name}: $err") }
                    }
                },
                onLog = { addLog("${m.name}: $it") },
                onPosition = { port, ticks -> positions[posKey(m, port)] = ticks },
                stallOn = {
                    when (io.github.matakobrynky.opencrown.data.Repo.module(m.id)?.stall ?: m.stall) {
                        io.github.matakobrynky.opencrown.data.StallMode.ON -> true
                        io.github.matakobrynky.opencrown.data.StallMode.KEEP -> null
                        else -> false
                    }
                })
            self = hub
            gattHubs[addr] = hub
            hub.connect()
            return
        }
        states[k] = State.CONNECTING
        val raw = when (m.type) {
            HubType.MK4 -> MkProtocol.mk4ConnectRaw()
            HubType.MK4OO -> MkProtocol.mk4OnOffConnectRaw()
            else -> MkProtocol.mk6ConnectRaw()
        }
        adv?.update(k, MouldKingCrypt.crypt(raw))
        main.postDelayed({
            if (states[k] == State.CONNECTING) { states[k] = State.ACTIVE; pushClassic(k) }
        }, CONNECT_MS)
    }

    /**
     * A 4-port hub must get 4-port messages: if the hub's Bluetooth name says it has a
     * different number of ports than the module is set to (e.g. a "YX-05…" hub set up as
     * H6.0), correct the saved module.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun fixHubType(m: HubModule): HubModule {
        if (!m.type.bluetoothHub || m.address == null) return m
        val name = try {
            app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(m.address)?.name
        } catch (_: Exception) { null } ?: BleScanner.found[m.address]?.name
        val t = BleScanner.typeForName(name) ?: return m
        if (t == m.type) return m
        val fixed = m.copy(type = t)
        io.github.matakobrynky.opencrown.data.Repo.upsertModule(fixed)
        addLog("${m.name}: \"$name\" is a ${t.channels}-port hub, switched from ${m.type.short} to ${t.short}")
        return fixed
    }

    fun disconnect(m: HubModule) {
        val k = key(m)
        states.remove(k); battery.remove(k)
        smartMotor.keys.filter { it.startsWith("$k:") }.forEach { smartMotor.remove(it) }
        if (m.type.bluetoothHub) { m.address?.let { gattHubs.remove(it)?.close() }; return }
        when (m.type) { HubType.MK4 -> mk4.fill(0f); HubType.MK4OO -> mk4oo.fill(0f); else -> mk6[m.index].fill(0f) }
        states[k] = State.ACTIVE; pushClassic(k); states.remove(k)
        main.postDelayed({ if (!states.containsKey(k)) adv?.remove(k) }, 400)
    }

    private class HubOut(n: Int) {
        val speeds = FloatArray(n)
        val targets = arrayOfNulls<Int>(n)
        val servoSpeeds = IntArray(n) { 32767 }
        val ends = arrayOfNulls<Pair<Int, Int>>(n)
    }

    /**
     * Replace all outputs: anything not listed goes to 0 (speed ports) / centre (servo ports
     * listed in [servo]). Servo mode only works on H4.0 / H6.0 hubs.
     */
    fun applyOutputs(
        outputs: Map<ChannelRef, Float>, servo: Map<ChannelRef, ServoCfg> = emptyMap(),
        /** speed ports with end stops on H hubs: (lower, upper) end in raw position units */
        ends: Map<ChannelRef, Pair<Int, Int>> = emptyMap(),
    ) {
        mk6.forEach { it.fill(0f) }
        mk4.fill(0f); mk4oo.fill(0f)
        val bt = HashMap<String, HubOut>()
        gattHubs.forEach { (a, h) -> bt[a] = HubOut(h.ports) }
        for (ref in outputs.keys + servo.keys) {
            val value = (outputs[ref] ?: 0f).coerceIn(-1f, 1f)
            val m = ref.module
            val c = ref.channel
            when (m.type) {
                HubType.H6, HubType.H4 -> m.address?.let { a -> bt[a]?.let { o ->
                    if (c in o.speeds.indices) {
                        val s = servo[ref]
                        if (s != null) {
                            o.targets[c] = HallProtocol.servoTarget(value, s.center, s.minus, s.plus)
                            o.servoSpeeds[c] = HallProtocol.servoSpeed(s.speedPct)
                        } else o.speeds[c] = value
                    }
                } }
                HubType.MK6 -> if (m.index in 0..2 && c in 0..5) mk6[m.index][c] = value
                HubType.MK4 -> if (m.index in 0..2 && c in 0..3) mk4[m.index * 4 + c] = value
                HubType.MK4OO -> if (m.index in 0..2 && c in 0..3) mk4oo[m.index * 4 + c] = value
            }
        }
        for ((ref, e) in ends) {
            val a = ref.module.address ?: continue
            val o = bt[a] ?: continue
            if (ref.channel in o.ends.indices && servo[ref] == null) o.ends[ref.channel] = e
        }
        for ((addr, o) in bt) gattHubs[addr]?.update(o.speeds, o.targets, o.servoSpeeds, o.ends)
        states.keys.filter { !it.startsWith("BT_") }.forEach { pushClassic(it) }
    }

    /** Re-send an H hub's stall-protection setting right away (after it was edited). */
    fun applyStall(m: HubModule) { m.address?.let { gattHubs[it]?.applyStall() } }

    fun isLinked(m: HubModule) = state(m) == State.ACTIVE && m.type.bluetoothHub

    /** Ask an H hub for a motor's current position (result lands in [positions]). */
    fun requestPosition(m: HubModule, port: Int) { m.address?.let { gattHubs[it]?.requestPosition(port) } }

    /** Make the motor's current position the hub's zero point. */
    fun zeroHere(m: HubModule, port: Int) {
        m.address?.let { gattHubs[it]?.zero(port) }
        positions[posKey(m, port)] = 0
    }

    fun stopAll() = applyOutputs(emptyMap())

    fun shutdown() {
        stopAll()
        main.postDelayed({
            adv?.stopAll()
            gattHubs.values.forEach { it.close() }; gattHubs.clear()
            states.clear()
        }, 300)
    }

    private fun pushClassic(k: String) {
        if (states[k] != State.ACTIVE) return
        val raw = when {
            k == "MK4" -> MkProtocol.mk4DataRaw(mk4)
            k == "MK4OO" -> MkProtocol.mk4OnOffDataRaw(mk4oo)
            else -> k.removePrefix("MK6_").toInt().let { MkProtocol.mk6DataRaw(it, mk6[it]) }
        }
        adv?.update(k, MouldKingCrypt.crypt(raw))
    }
}
