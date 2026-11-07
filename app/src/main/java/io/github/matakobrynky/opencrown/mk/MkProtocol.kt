package io.github.matakobrynky.opencrown.mk

import kotlin.math.abs
import kotlin.math.min

/** Telegram layouts for Mould King Module 6.0 and Module 4.0 (BLE advertising). */
object MkProtocol {
    const val MANUFACTURER_ID = 0xFFF0
    private const val DEADZONE = 0.02f

    private val MK6_CONNECT = intArrayOf(0x6D, 0x7B, 0xA7, 0x80, 0x80, 0x80, 0x80, 0x92)
    private val MK4_CONNECT = intArrayOf(0xAD, 0x7B, 0xA7, 0x80, 0x80, 0x80, 0x4F, 0x52)

    fun mk6ConnectRaw() = MK6_CONNECT.copyOf()
    fun mk4ConnectRaw() = MK4_CONNECT.copyOf()

    /** MK 6.0: one telegram per module (index 0..2), six byte channels A..F. */
    fun mk6DataRaw(index: Int, values: FloatArray): IntArray {
        require(index in 0..2 && values.size == 6)
        val t = intArrayOf(0x61 + index, 0x7B, 0xA7, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x9E - index)
        for (c in 0 until 6) t[3 + c] = byteValue(values[c])
        return t
    }

    /** MK 4.0: one telegram addresses all three modules, 12 nibble channels (4 per module). */
    fun mk4DataRaw(values: FloatArray): IntArray {
        require(values.size == 12)
        val t = intArrayOf(0x7D, 0x7B, 0xA7, 0x88, 0x88, 0x88, 0x88, 0x88, 0x88, 0x82)
        for (b in 0 until 6) {
            t[3 + b] = (nibbleValue(values[b * 2]) shl 4) or nibbleValue(values[b * 2 + 1])
        }
        return t
    }

    // ---- older 4.0 battery box: on/off per channel (MK Plus "BluetoothDataBaseV40") ----
    private val MK4_ONOFF_CONNECT = intArrayOf(0xAA, 0x00, 0x00, 0x80, 0x80, 0x80, 0x00, 0x55)
    fun mk4OnOffConnectRaw() = MK4_ONOFF_CONNECT.copyOf()

    /** Channel is switched on beyond this (MK Plus uses ±2 on its ±10 scale). */
    const val ONOFF_THRESHOLD = 0.2f

    /**
     * One module's byte: two bits per channel (forward, reverse):
     * bit1/0 = A, bit7/6 = B, bit5/4 = C, bit3/2 = D.
     */
    fun onOffByte(a: Float, b: Float, c: Float, d: Float): Int {
        fun fwd(v: Float) = if (v > ONOFF_THRESHOLD) 1 else 0
        fun rev(v: Float) = if (v < -ONOFF_THRESHOLD) 1 else 0
        return rev(a) or (fwd(a) shl 1) or (rev(d) shl 2) or (fwd(d) shl 3) or
            (rev(c) shl 4) or (fwd(c) shl 5) or (rev(b) shl 6) or (fwd(b) shl 7)
    }

    /** All three on/off modules in one telegram: 77 00 00 m1 m2 m3 00 88. */
    fun mk4OnOffDataRaw(values: FloatArray): IntArray {
        require(values.size == 12)
        fun m(i: Int) = onOffByte(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3])
        return intArrayOf(0x77, 0x00, 0x00, m(0), m(1), m(2), 0x00, 0x88)
    }

    fun byteValue(v: Float): Int = when {
        abs(v) < DEADZONE -> 0x80
        v < 0 -> (0x80 - min(-v * 0x80, 128f)).toInt()
        else -> (0x80 + min(v * 0x7F, 127f)).toInt()
    }

    fun nibbleValue(v: Float): Int = when {
        abs(v) < DEADZONE -> 0x08
        v < 0 -> min(-v * 7f, 7f).toInt()
        else -> 0x08 + min(v * 7f, 7f).toInt()
    }
}
