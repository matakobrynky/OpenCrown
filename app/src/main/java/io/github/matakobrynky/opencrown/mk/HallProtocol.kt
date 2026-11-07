package io.github.matakobrynky.opencrown.mk

import kotlin.math.abs

/**
 * Mould King H4.0 / H6.0 ("hall") hub protocol.
 *
 * Every message is ASCII text:  "T" + hex2(data.length) + command + data + "W"
 *   connect     : cmd "1", data "AABB"          → T041AABBW
 *   disconnect  : cmd "2"                        → T002W
 *   motor speed : cmd "4", per port: limitFlag(1 hex) + speed(4 hex)
 *                 speed = |v| * 32767, bit 15 set for reverse
 *   heartbeat   : cmd "C"                        → T00CW
 * Replies from the hub use the same framing.
 */
object HallProtocol {
    const val SERVICE_TAG = "ae3a"
    const val WRITE_TAG = "ae3b"
    const val NOTIFY_TAG = "ae3c"

    fun frame(cmd: String, data: String = ""): String =
        "T" + "%02X".format(data.length and 0xFF) + cmd + data + "W"

    fun connect() = frame("1", "AABB")
    fun disconnect() = frame("2")
    fun heartbeat() = frame("C")

    /** cmd "F": stall ("rotation blocking") protection; MK Plus sends "1" after every connect. */
    fun stallProtection(on: Boolean) = frame("F", if (on) "1" else "0")

    /**
     * Heartbeat reply data = 2 hex digits, one bit per port; a SET bit means
     * "no smart (hall) motor on this port" (MK Plus: AxleStatus.notConnected).
     */
    fun smartPorts(heartbeatData: String, ports: Int): BooleanArray? {
        val v = heartbeatData.take(2).toIntOrNull(16) ?: return null
        return BooleanArray(ports) { (v shr it) and 1 == 0 }
    }

    /**
     * One port's speed field, -1..1. The first digit is MK Plus' per-port "limit" flag
     * (_speedCanLimit): 1 = the hub stops this motor at the end points set with [endPoints].
     */
    fun speedField(v: Float, limited: Boolean = false): String {
        val c = v.coerceIn(-1f, 1f)
        val mag = (abs(c) * 32767f).toInt().coerceIn(0, 32767)
        val raw = if (c >= 0f) mag else mag + 0x8000
        return (if (limited) "1" else "0") + "%04X".format(raw)
    }

    /** Speed command for all ports (4 for H4.0, 6 for H6.0). */
    fun speed(values: FloatArray, limited: BooleanArray? = null): String =
        frame("4", values.indices.joinToString("") { speedField(values[it], limited?.getOrNull(it) == true) })

    /**
     * cmd "9": end points of one port, in raw position units (MK Plus setEndPoint): port mask,
     * then the upper end and the lower end, each as sign + 8 hex digits. Like MK Plus, values
     * closer than 1000 units to zero count as zero.
     */
    fun endPoints(port: Int, a: Int, b: Int): String {
        fun z(v: Int) = if (abs(v) < 1000) 0 else v
        val hi = maxOf(z(a), z(b)); val lo = minOf(z(a), z(b))
        fun f(v: Int) = (if (v < 0) "-" else "+") + "%08X".format(abs(v.toLong()))
        return frame("9", "%02X".format(mask(port)) + f(hi) + f(lo))
    }

    // ---------------- position (servo) mode ----------------

    /** Port bitmask as used by the hub: A = 1, B = 2, C = 4 … */
    fun mask(port: Int) = 1 shl port

    /**
     * One port's field in the angle command: speed(4 hex) + |angle|(8 hex).
     * Bit 15 of the speed marks a non-negative angle. Speed 0 + angle 0 ("800000000000")
     * means "leave this port alone".
     */
    fun angleField(angle: Int, speed: Int): String {
        var s = if (angle >= 0) speed else -speed
        s = s.coerceIn(-32767, 32767)
        val sp = if (s < 0) -s else s + 0x8000
        return "%04X".format(sp.coerceAtMost(0xFFFF)) + "%08X".format(abs(angle.toLong()))
    }

    /** cmd "3": move servo ports to absolute positions. null entries are left alone. */
    fun angle(targets: Array<Int?>, speeds: IntArray): String =
        frame("3", targets.indices.joinToString("") { i ->
            val t = targets[i]
            if (t == null) angleField(0, 0) else angleField(t, speeds[i])
        })

    /** cmd "8": make the current position of these ports the new zero. */
    fun setZero(port: Int) = frame("8", "%02X".format(mask(port)))

    /** cmd "A": ask for the current position of a port. Reply data: port, '+'/'-', 8 hex. */
    fun readPosition(port: Int) = frame("A", "%02X".format(mask(port)))

    /** Parses the data of an "A" reply into (portDigit, position). */
    fun parsePosition(data: String): Pair<Int, Int>? {
        if (data.length < 10) return null
        val p = data.substring(0, 1).toIntOrNull(16) ?: return null
        val v = data.substring(2, 10).toLongOrNull(16) ?: return null
        val signed = if (data[1] == '-') -v else v
        return p to signed.toInt()
    }

    /** Servo speed percent → hub speed units (same rounding as MK Plus). */
    fun servoSpeed(pct: Int) = (pct.coerceIn(1, 100) * 32767 + 99) / 100

    /** Hub positions are raw units; MK Plus displays them divided by this. */
    const val DISPLAY_DIV = 10000

    /** Control value -1..1 → absolute target (raw units), anchored on the centre. */
    fun servoTarget(v: Float, center: Int, minus: Int, plus: Int): Int {
        val c = v.coerceIn(-1f, 1f)
        return center + if (c >= 0f) Math.round(c * plus) else Math.round(c * minus)
    }

    data class Reply(val cmd: Char, val data: String)

    /** Pulls complete "T..W" frames out of a text buffer, leaving any partial frame in it. */
    fun extract(buf: StringBuilder): List<Reply> {
        val out = ArrayList<Reply>()
        while (true) {
            val t = buf.indexOf("T")
            if (t < 0) { buf.setLength(0); break }
            if (t > 0) buf.delete(0, t)
            if (buf.length < 4) break
            val len = buf.substring(1, 3).toIntOrNull(16)
            if (len == null) { buf.deleteCharAt(0); continue }
            val end = 3 + 1 + len
            if (buf.length <= end) break
            if (buf[end] != 'W') { buf.deleteCharAt(0); continue }
            out.add(Reply(buf[3], buf.substring(4, end)))
            buf.delete(0, end + 1)
        }
        return out
    }
}
