package io.github.matakobrynky.opencrown.mk

import java.util.UUID

/** LEGO Wireless Protocol 3.0 (Powered Up) constants and message builders. */
object Lwp {
    val SERVICE: UUID = UUID.fromString("00001623-1212-efde-1623-785feabcd123")
    val CHARACTERISTIC: UUID = UUID.fromString("00001624-1212-efde-1623-785feabcd123")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val LEGO_COMPANY_ID = 0x0397

    /** Port Output Command → WriteDirectModeData, mode 0 (power), -100..100. */
    fun motorPower(port: Int, pct: Int): ByteArray {
        val p = pct.coerceIn(-100, 100)
        return byteArrayOf(0x08, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x51, 0x00, p.toByte())
    }

    /** Hub Properties → enable battery voltage updates. */
    fun enableBatteryUpdates() = byteArrayOf(0x05, 0x00, 0x01, 0x06, 0x02)

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** Human-readable decode of the most common hub → phone messages. */
    fun describe(b: ByteArray): String {
        if (b.size < 3) return "raw ${hex(b)}"
        return when (b[2].toInt() and 0xFF) {
            0x01 -> if (b.size >= 6 && b[3].toInt() == 0x06) "battery ${b[5].toInt() and 0xFF}%" else "hub property ${hex(b)}"
            0x04 -> if (b.size >= 5) {
                val port = b[3].toInt() and 0xFF
                when (b[4].toInt()) {
                    0 -> "port $port: device detached"
                    1 -> if (b.size >= 7) "port $port: device attached, type 0x%02X%02X".format(b[6].toInt() and 0xFF, b[5].toInt() and 0xFF) else "port $port attached"
                    2 -> "virtual port $port attached"
                    else -> "attached I/O ${hex(b)}"
                }
            } else "attached I/O ${hex(b)}"
            0x05 -> "error ${hex(b)}"
            0x82 -> "port ${b.getOrNull(3)?.toInt()?.and(0xFF)} command feedback"
            else -> "msg 0x%02X: %s".format(b[2].toInt() and 0xFF, hex(b))
        }
    }
}
