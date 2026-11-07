package io.github.matakobrynky.opencrown.mk

/**
 * Mould King BLE-advertising "encryption" (nRF24-style whitening + CRC16),
 * ported from J0EK3R/mkconnect-python (MouldKingCrypt.py),
 * MIT License, Copyright (c) 2024 J0EK3R — see THIRD_PARTY_NOTICES.md.
 * Produces the 24-byte manufacturer-data payload (company id 0xFFF0).
 */
object MouldKingCrypt {
    private val ADDRESS = intArrayOf(0xC1, 0xC2, 0xC3, 0xC4, 0xC5)

    fun crypt(raw: IntArray): ByteArray {
        val a = ADDRESS
        val n = raw.size
        val target = IntArray(a.size + n + 20)
        target[15] = 0x71
        target[16] = 0x0F
        target[17] = 0x55
        for (i in a.indices) target[18 + i] = a[a.size - 1 - i]
        for (i in 0 until n) target[18 + a.size + i] = raw[i]
        for (i in 15 until a.size + 18) target[i] = revertByte(target[i])

        val cs = checksum(a, raw)
        target[a.size + 18 + n] = cs and 0xFF
        target[a.size + 18 + n + 1] = (cs shr 8) and 0xFF

        val tail = target.copyOfRange(18, target.size)
        cryptArray(tail, magic(63))
        System.arraycopy(tail, 0, target, 18, tail.size)
        cryptArray(target, magic(37))

        val out = ByteArray(24)
        val len = a.size + n + 5
        for (i in 0 until len) out[i] = target[15 + i].toByte()
        for (i in len until 24) out[i] = (i + 1).toByte()
        return out
    }

    private fun magic(m: Int): IntArray {
        val arr = IntArray(7)
        arr[0] = 1
        for (i in 1 until 7) arr[i] = (m shr (6 - i)) and 1
        return arr
    }

    private fun revertByte(v: Int): Int {
        var r = 0
        for (b in 0 until 8) if ((1 shl b) and v != 0) r = r or (1 shl (7 - b))
        return r
    }

    private fun revertInt(v: Int): Int {
        var r = 0
        for (b in 0 until 16) if ((1 shl b) and v != 0) r = r or (1 shl (15 - b))
        return r and 0xFFFF
    }

    private fun shift(a: IntArray): Int {
        val r1 = a[3] xor a[6]
        a[3] = a[2]; a[2] = a[1]; a[1] = a[0]; a[0] = a[6]
        a[6] = a[5]; a[5] = a[4]; a[4] = r1
        return a[0]
    }

    private fun cryptArray(bytes: IntArray, magic: IntArray) {
        for (i in bytes.indices) {
            val cur = bytes[i]
            var res = 0
            for (b in 0 until 8) res += (((cur shr b) and 1) xor shift(magic)) shl b
            bytes[i] = res and 0xFF
        }
    }

    private fun checksum(first: IntArray, second: IntArray): Int {
        var r = 0xFFFF
        fun round() {
            for (b in 0 until 8) {
                val top = r and 0x8000
                r = (r shl 1) and 0xFFFF
                if (top != 0) r = r xor 0x1021
            }
        }
        for (i in first.indices) {
            r = (r xor (first[first.size - 1 - i] shl 8)) and 0xFFFF
            round()
        }
        for (c in second) {
            r = ((revertByte(c) shl 8) xor r) and 0xFFFF
            round()
        }
        return revertInt(r) xor 0xFFFF
    }
}
