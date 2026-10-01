package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorInputTest {
    @Test
    fun malformedEvents_areUnknown() {
        assertEquals(Pose.UNKNOWN, SensorInput.toPose(null, deviceLocked = false))
        assertEquals(Pose.UNKNOWN, SensorInput.toPose(floatArrayOf(), deviceLocked = false))
        assertEquals(Pose.UNKNOWN, SensorInput.toPose(floatArrayOf(0f, 0f), deviceLocked = false))
    }

    @Test
    fun extraValuesInTheEvent_areIgnored() {
        // Some sensors report more than 3 values (e.g. accuracy fields).
        val values = floatArrayOf(0f, 0f, -9.81f, 123f, 456f)
        assertEquals(Pose.FACE_DOWN, SensorInput.toPose(values, deviceLocked = false))
    }

    @Test
    fun lockScreen_neverTriggers() {
        val faceDown = floatArrayOf(0f, 0f, -9.81f)
        assertEquals(Pose.UNKNOWN, SensorInput.toPose(faceDown, deviceLocked = true))
    }

    @Test
    fun delaySetting_isClamped() {
        assertEquals(SensorInput.MIN_DELAY_SEC, SensorInput.sanitizeDelaySec(Int.MIN_VALUE))
        assertEquals(SensorInput.MIN_DELAY_SEC, SensorInput.sanitizeDelaySec(-1))
        assertEquals(SensorInput.MIN_DELAY_SEC, SensorInput.sanitizeDelaySec(0))
        assertEquals(30, SensorInput.sanitizeDelaySec(30))
        assertEquals(SensorInput.MAX_DELAY_SEC, SensorInput.sanitizeDelaySec(Int.MAX_VALUE))
    }

    @Test
    fun clampedDelay_alwaysMakesAValidConfig() {
        // Int.MAX_VALUE seconds would overflow to a negative Long of ms without the clamp.
        for (raw in listOf(Int.MIN_VALUE, -1, 0, 5, 120, Int.MAX_VALUE)) {
            val sec = SensorInput.sanitizeDelaySec(raw)
            GuardConfig(triggerDelayMs = sec * 1_000L) // must not throw
        }
    }
}

class GravityFilterTest {
    @Test
    fun firstReading_isUsedDirectly_noRampUp() {
        val f = GravityFilter()
        assertArrayEquals(floatArrayOf(0f, 0f, 9.81f), f.update(floatArrayOf(0f, 0f, 9.81f)), 0.0001f)
    }

    @Test
    fun jitter_isSmoothed() {
        val f = GravityFilter(smoothing = 0.9f)
        f.update(floatArrayOf(0f, 0f, 9.81f))
        val out = f.update(floatArrayOf(10f, 0f, 9.81f))!! // sudden hand jerk on x
        assertEquals(1f, out[0], 0.0001f) // only 10% of the spike gets through
    }

    @Test
    fun convergesToANewOrientation() {
        val f = GravityFilter()
        f.update(floatArrayOf(0f, 0f, 9.81f))
        var out: FloatArray? = null
        repeat(200) { out = f.update(floatArrayOf(0f, 0f, -9.81f)) }
        assertEquals(-9.81f, out!![2], 0.01f)
    }

    @Test
    fun glitches_areSkipped_andDoNotPoisonTheEstimate() {
        val f = GravityFilter()
        f.update(floatArrayOf(0f, 0f, 9.81f))
        assertNull(f.update(floatArrayOf(Float.NaN, 0f, 9.81f)))
        assertNull(f.update(floatArrayOf(0f, Float.POSITIVE_INFINITY, 9.81f)))
        assertNull(f.update(floatArrayOf(1f, 2f)))
        val out = f.update(floatArrayOf(0f, 0f, 9.81f))
        assertNotNull(out)
        assertTrue(out!!.all { it.isFinite() })
        assertEquals(9.81f, out[2], 0.0001f)
    }

    @Test
    fun returnedArray_isACopy() {
        val f = GravityFilter()
        val out = f.update(floatArrayOf(0f, 0f, 9.81f))!!
        out[2] = -1000f // caller scribbles on it
        val next = f.update(floatArrayOf(0f, 0f, 9.81f))!!
        assertEquals(9.81f, next[2], 0.0001f)
    }

    @Test
    fun invalidSmoothing_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { GravityFilter(smoothing = 1f) }
        assertThrows(IllegalArgumentException::class.java) { GravityFilter(smoothing = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { GravityFilter(smoothing = Float.NaN) }
    }

    @Test
    fun filteredOutput_feedsTheClassifierSafely() {
        val f = GravityFilter()
        assertFalse(SensorInput.toPose(f.update(floatArrayOf(Float.NaN, 0f, 0f)), false).isSuspicious)
    }
}
