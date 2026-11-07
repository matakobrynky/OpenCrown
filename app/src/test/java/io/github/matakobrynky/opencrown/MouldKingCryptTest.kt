package io.github.matakobrynky.opencrown

import io.github.matakobrynky.opencrown.mk.MkProtocol
import io.github.matakobrynky.opencrown.mk.MouldKingCrypt
import org.junit.Assert.assertEquals
import org.junit.Test

class MouldKingCryptTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun connectTelegramMatchesPublishedCapture() {
        // Captured reference from J0EK3R/mkconnect-python README
        val expected = "6d b6 43 cf 7e 8f 47 11 88 66 59 38 d1 7a aa 26 49 5e 13 14 15 16 17 18"
        assertEquals(expected, hex(MouldKingCrypt.crypt(MkProtocol.mk6ConnectRaw())))
    }

    @Test
    fun channelMapping() {
        assertEquals(0x80, MkProtocol.byteValue(0f))
        assertEquals(0xFF, MkProtocol.byteValue(1f))
        assertEquals(0x00, MkProtocol.byteValue(-1f))
        assertEquals(0x08, MkProtocol.nibbleValue(0f))
        assertEquals(0x0F, MkProtocol.nibbleValue(1f))
        assertEquals(0x07, MkProtocol.nibbleValue(-1f))
        val raw = MkProtocol.mk6DataRaw(1, floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f))
        assertEquals(listOf(0x62, 0x7B, 0xA7, 0xFF, 0x80, 0x80, 0x80, 0x80, 0x00, 0x9D), raw.toList())
        assertEquals(24, MouldKingCrypt.crypt(raw).size)
    }
}

class LwpTest {
    @Test
    fun motorPowerCommand() {
        assertEquals("08 00 81 00 11 51 00 32", io.github.matakobrynky.opencrown.mk.Lwp.hex(io.github.matakobrynky.opencrown.mk.Lwp.motorPower(0, 50)))
        assertEquals("08 00 81 05 11 51 00 9C", io.github.matakobrynky.opencrown.mk.Lwp.hex(io.github.matakobrynky.opencrown.mk.Lwp.motorPower(5, -100)))
        assertEquals("port 1: device attached, type 0x0030",
            io.github.matakobrynky.opencrown.mk.Lwp.describe(byteArrayOf(0x0F, 0, 0x04, 0x01, 0x01, 0x30, 0x00)))
    }
}

class HallProtocolTest {
    private val P = io.github.matakobrynky.opencrown.mk.HallProtocol

    @Test
    fun framesMatchMkPlus() {
        assertEquals("T041AABBW", P.connect())
        assertEquals("T002W", P.disconnect())
        assertEquals("T00CW", P.heartbeat())
        assertEquals("T1E4" + "00000".repeat(6) + "W", P.speed(FloatArray(6)))
        assertEquals("T144" + "00000".repeat(4) + "W", P.speed(FloatArray(4)))
    }

    @Test
    fun speedEncoding() {
        assertEquals("07FFF", P.speedField(1f))
        assertEquals("0FFFF", P.speedField(-1f))
        assertEquals("0BFFF", P.speedField(-0.5f))   // 16383 + 0x8000
        assertEquals("03FFF", P.speedField(0.5f))
        assertEquals("T1E4" + "07FFF" + "00000".repeat(4) + "0FFFF" + "W",
            P.speed(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f)))
    }

    @Test
    fun parsesReplies() {
        val b = StringBuilder("xxT041AABBWT00")
        val r = P.extract(b)
        assertEquals(1, r.size); assertEquals('1', r[0].cmd); assertEquals("AABB", r[0].data)
        assertEquals("T00", b.toString())
        b.append("CW")
        assertEquals('C', P.extract(b).single().cmd)
    }
}

class HallServoTest {
    private val P = io.github.matakobrynky.opencrown.mk.HallProtocol

    @Test
    fun angleFieldsMatchMkPlus() {
        assertEquals("FFFF0000000A", P.angleField(10, 32767))   // positive angle → bit 15 set
        assertEquals("7FFF0000000A", P.angleField(-10, 32767))  // negative angle → magnitude only
        assertEquals("800000000000", P.angleField(0, 0))        // "leave this port alone"
        assertEquals("BFFF00000190", P.angleField(400, 16383))
    }

    @Test
    fun angleFrame() {
        val h4 = P.angle(arrayOf(null, 20, null, null), intArrayOf(0, 32767, 0, 0))
        assertEquals("T303" + "800000000000" + "FFFF00000014" + "800000000000".repeat(2) + "W", h4)
        val h6 = P.angle(arrayOfNulls(6), IntArray(6))
        assertEquals("T483" + "800000000000".repeat(6) + "W", h6)
    }

    @Test
    fun zeroAndPosition() {
        assertEquals("T02801W", P.setZero(0))
        assertEquals("T02804W", P.setZero(2))
        assertEquals("T02A02W", P.readPosition(1))
        assertEquals(1 to 42, P.parsePosition("1+0000002A"))
        assertEquals(0 to -42, P.parsePosition("0-0000002A"))
    }

    @Test
    fun servoMapping() {
        assertEquals(15, P.servoTarget(0.5f, 5, 10, 20))
        assertEquals(0, P.servoTarget(-0.5f, 5, 10, 20))
        assertEquals(20, P.servoTarget(1f, 0, 10, 20))
        assertEquals(-10, P.servoTarget(-1f, 0, 10, 20))
        assertEquals(7, P.servoTarget(0f, 7, 10, 20))
        assertEquals(32767, P.servoSpeed(100))
        assertEquals(16384, P.servoSpeed(50))          // ceil like MK Plus
    }

    @Test
    fun degreesToHubUnits() {
        // MK Plus: 10 turns of an M/L motor = 395 display units = 3 950 000 raw
        val b = io.github.matakobrynky.opencrown.data.Binding(mode = io.github.matakobrynky.opencrown.data.OutputMode.SERVO,
            servoCenterDeg = 0f, servoMinusDeg = 90f)
        val cfg = b.servoCfg()
        assertEquals(98750, cfg.plus)                    // quarter turn
        assertEquals(98750, cfg.minus)
        val xl = b.copy(motorSize = io.github.matakobrynky.opencrown.data.MotorSize.XL).servoCfg()
        assertEquals(147500, xl.plus)
        assertEquals(360f, b.rawToDeg(395000), 0.001f)
        // a full deflection now produces a target well above MK Plus' 1000-unit minimum
        assertEquals("FFFF000181BE", P.angleField(P.servoTarget(1f, cfg.center, cfg.minus, cfg.plus), 32767))
    }

    @Test
    fun highRangeFitsTheHub() {
        // 1000 turns of an XL motor each way, centred 1000 turns out: still a valid 8-hex angle
        val b = io.github.matakobrynky.opencrown.data.Binding(mode = io.github.matakobrynky.opencrown.data.OutputMode.SERVO,
            motorSize = io.github.matakobrynky.opencrown.data.MotorSize.XL, servoRange = io.github.matakobrynky.opencrown.data.ServoRange.HIGH,
            servoCenterDeg = 360_000f, servoMinusDeg = 360_000f, servoLinked = true)
        val cfg = b.servoCfg()
        assertEquals(590_000_000, cfg.plus)
        val t = P.servoTarget(1f, cfg.center, cfg.minus, cfg.plus)
        assertEquals(1_180_000_000, t)
        assertEquals("FFFF" + "%08X".format(t), P.angleField(t, 32767))
        assertEquals(1000f, b.rawToDeg(cfg.plus) / 360f, 0.01f)
    }

    @Test
    fun servoSliderIsLinearOverTheTravel() {
        fun target(b: io.github.matakobrynky.opencrown.data.Binding, pos: Float): Float {
            var v = io.github.matakobrynky.opencrown.data.sliderValue(pos, b.servoZeroAt())
            if (b.invert) v = -v
            val c = b.servoCfg()
            return b.rawToDeg(P.servoTarget(v, c.center, c.minus, c.plus)) / 360f
        }
        // no backward travel: rests at the − end, whole slider = 0 → 10 turns
        val oneWay = io.github.matakobrynky.opencrown.data.Binding(mode = io.github.matakobrynky.opencrown.data.OutputMode.SERVO,
            servoRange = io.github.matakobrynky.opencrown.data.ServoRange.HIGH, servoMinusDeg = 0f, servoPlusDeg = 3600f, servoLinked = false)
        assertEquals(0f, oneWay.servoZeroAt(), 0f)
        assertEquals(0f, target(oneWay, 0f), 0.01f)
        assertEquals(5f, target(oneWay, 0.5f), 0.01f)
        assertEquals(10f, target(oneWay, 1f), 0.01f)
        // the screenshot case: −5.4 … +10 turns, evenly spread along the slider
        val b = oneWay.copy(servoMinusDeg = 5.4f * 360f)
        for (p in listOf(0f, 0.2f, 0.351f, 0.7f, 1f)) assertEquals(-5.4f + p * 15.4f, target(b, p), 0.02f)
        // reversed: still even, ends swapped
        val r = b.copy(invert = true)
        for (p in listOf(0f, 0.3f, 1f)) assertEquals(10f - p * 15.4f, target(r, p), 0.02f)
    }

    @Test
    fun motorDetection() {
        assertEquals("T01F1W", P.stallProtection(true))
        assertEquals("T01F0W", P.stallProtection(false))
        // heartbeat "05": bits 0 and 2 set → A and C have no smart motor
        assertEquals(listOf(false, true, false, true, true, true), P.smartPorts("05", 6)!!.toList())
        assertEquals(listOf(true, true, true, true), P.smartPorts("00", 4)!!.toList())
        assertEquals(null, P.smartPorts("", 4))
    }
}

class HubNameTest {
    @Test
    fun portCountFromNameLikeMkPlus() {
        val S = io.github.matakobrynky.opencrown.mk.BleScanner
        val H4 = io.github.matakobrynky.opencrown.data.HubType.H4
        val H6 = io.github.matakobrynky.opencrown.data.HubType.H6
        assertEquals(H4, S.typeForName("YX-05AB12"))   // 4-port, like YX-01
        assertEquals(H4, S.typeForName("YX-01X3X6"))
        assertEquals(H6, S.typeForName("YX-02X154"))
        assertEquals(H6, S.typeForName("YX-06abc"))
        assertEquals(null, S.typeForName("YX-03"))
        assertEquals("T144" + "07FFF" + "00000".repeat(3) + "W",
            io.github.matakobrynky.opencrown.mk.HallProtocol.speed(floatArrayOf(1f, 0f, 0f, 0f)))
    }
}

class ClassicOnOffTest {
    private val P = io.github.matakobrynky.opencrown.mk.MkProtocol

    @Test
    fun matchesMkPlusV40() {
        assertEquals(listOf(0xAA, 0, 0, 0x80, 0x80, 0x80, 0, 0x55), P.mk4OnOffConnectRaw().toList())
        // module 1: A forward, B reverse; module 2: D forward; module 3: C reverse
        val v = FloatArray(12)
        v[0] = 1f; v[1] = -1f; v[7] = 0.8f; v[10] = -0.5f
        val raw = P.mk4OnOffDataRaw(v)
        assertEquals(listOf(0x77, 0, 0, 0x42, 0x08, 0x10, 0, 0x88), raw.toList())
        assertEquals(0, P.onOffByte(0.1f, -0.1f, 0f, 0.19f))            // below threshold = off
        assertEquals(24, io.github.matakobrynky.opencrown.mk.MouldKingCrypt.crypt(raw).size)
    }
}


class MotorShaperTest {
    private val base = io.github.matakobrynky.opencrown.data.Binding(moduleId = "m", channel = 0, maxPct = 80)

    @Test
    fun simpleIsUnchanged() {
        val s = io.github.matakobrynky.opencrown.mk.MotorShaper()
        assertEquals(0.4f, s.step(base, 0.5f, 16f), 1e-4f)
        assertEquals(-0.8f, s.step(base, -1f, 16f), 1e-4f)
        assertEquals(false, s.busy(base, -1f))
    }

    @Test
    fun separateReverseSpeed() {
        val b = base.copy(advanced = true, splitMax = true, maxPct = 100, maxRevPct = 40)
        val s = io.github.matakobrynky.opencrown.mk.MotorShaper()
        assertEquals(1f, s.step(b, 1f, 16f), 1e-4f)
        assertEquals(-0.4f, s.step(b, -1f, 16f), 1e-4f)
        assertEquals(-0.2f, s.step(b, -0.5f, 16f), 1e-4f)
    }

    @Test
    fun accelerationRampsBothWays() {
        val b = base.copy(advanced = true, maxPct = 100, accel = true, accelMs = 1000)
        val s = io.github.matakobrynky.opencrown.mk.MotorShaper()
        repeat(25) { s.step(b, 1f, 20f) }                  // 0.5 s
        assertEquals(0.5f, s.output, 1e-3f)
        assertEquals(true, s.busy(b, 1f))
        repeat(25) { s.step(b, 1f, 20f) }                  // 1.0 s: full speed
        assertEquals(1f, s.output, 1e-3f)
        assertEquals(false, s.busy(b, 1f))
        repeat(50) { s.step(b, -1f, 20f) }                 // halfway through reversing = standstill
        assertEquals(0f, s.output, 1e-3f)
        s.reset(); assertEquals(0f, s.output, 0f)
    }

    @Test
    fun splitSpeedSliderIsLinear() {
        // forward 100 %, reverse 45 %: rests 45/145 of the way up; equal moves = equal speed changes
        val b = base.copy(advanced = true, splitMax = true, maxPct = 100, maxRevPct = 45)
        val z = b.motorZeroAt()
        assertEquals(45f / 145f, z, 1e-4f)
        val s = io.github.matakobrynky.opencrown.mk.MotorShaper()
        fun speed(bb: io.github.matakobrynky.opencrown.data.Binding, pos: Float): Float {
            var v = io.github.matakobrynky.opencrown.data.sliderValue(pos, bb.motorZeroAt())
            if (bb.invert) v = -v
            return s.step(bb, v, 16f)
        }
        for (p in listOf(0f, 0.1f, 0.3f, 0.6f, 1f)) assertEquals(-0.45f + p * 1.45f, speed(b, p), 1e-3f)
        // reversed: ends swap, still even
        val r = b.copy(invert = true)
        for (p in listOf(0f, 0.5f, 1f)) assertEquals(1f - p * 1.45f, speed(r, p), 1e-3f)
        // same speed both ways, or simple setup: centred as before
        assertEquals(0.5f, base.motorZeroAt(), 0f)
        assertEquals(0.5f, b.copy(splitMax = false).motorZeroAt(), 0f)
    }
}

class EndStopTest {
    private val P = io.github.matakobrynky.opencrown.mk.HallProtocol

    @Test
    fun endPointsMatchMkPlus() {
        // port C, from −2 turns to +10 turns of an M/L motor (395 000 per turn)
        assertEquals("T149" + "04" + "+" + "003C45B0" + "-" + "000C0DF0" + "W", P.endPoints(2, -790_000, 3_950_000))
        // order doesn't matter; under 1000 units counts as 0
        assertEquals(P.endPoints(0, 500, 395_000), P.endPoints(0, 395_000, 0))
        assertEquals("T149" + "01" + "+" + "00060706" + "+" + "00000000" + "W", P.endPoints(0, 999, 395_014))
    }

    @Test
    fun endsFromSettings() {
        val b = io.github.matakobrynky.opencrown.data.Binding(endStops = true, servoRange = io.github.matakobrynky.opencrown.data.ServoRange.HIGH,
            servoMinusDeg = 2 * 360f, servoPlusDeg = 10 * 360f, servoLinked = false)
        assertEquals(-790_000 to 3_950_000, b.endPointsRaw())
        assertEquals("T149" + "04" + "+" + "003C45B0" + "-" + "000C0DF0" + "W", P.endPoints(2, b.endPointsRaw().first, b.endPointsRaw().second))
    }

    @Test
    fun limitFlagInSpeedFrame() {
        assertEquals("17FFF", P.speedField(1f, limited = true))
        assertEquals("T144" + "1BFFF" + "07FFF" + "00000" + "00000" + "W",
            P.speed(floatArrayOf(-0.5f, 1f, 0f, 0f), booleanArrayOf(true, false, false, false)))
        assertEquals(P.speed(FloatArray(4)), P.speed(FloatArray(4), null))
    }
}
