package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TamperPenaltyTest {
    private val min = TamperPenalty.MINUTE_MS

    @Test
    fun penaltyGrowsPerAttempt() {
        assertEquals(0L, TamperPenalty.extraLockMs(0))
        assertEquals(5 * min, TamperPenalty.extraLockMs(1))
        assertEquals(10 * min, TamperPenalty.extraLockMs(2))
        assertEquals(15 * min, TamperPenalty.extraLockMs(3))
        assertEquals(15 * min, TamperPenalty.extraLockMs(50)) // capped: proportionate, not endless
        assertEquals(0L, TamperPenalty.extraLockMs(-3))
    }

    @Test
    fun escalationSteps() {
        assertFalse(TamperPenalty.startsAtMaxVibration(1))
        assertTrue(TamperPenalty.startsAtMaxVibration(2))
        assertFalse(TamperPenalty.requiresPhrase(2))
        assertTrue(TamperPenalty.requiresPhrase(3))
    }

    @Test
    fun attempt_isAnyVolumeBelowWhatWasSet() {
        assertTrue(TamperPenalty.isAttempt(currentVolume = 5, enforcedVolume = 7))
        assertTrue(TamperPenalty.isAttempt(0, 7))
        assertFalse(TamperPenalty.isAttempt(7, 7))
        assertFalse(TamperPenalty.isAttempt(9, 7)) // turning it UP is fine
        assertFalse(TamperPenalty.isAttempt(0, 0)) // nothing enforced (volume couldn't be set)
        assertFalse(TamperPenalty.isAttempt(-1, 7)) // bogus reading
    }

    @Test
    fun remaining_isClamped() {
        assertEquals(60_000L, TamperPenalty.remainingMs(untilElapsedMs = 160_000, nowElapsedMs = 100_000, fullMs = 300_000))
        assertEquals(0L, TamperPenalty.remainingMs(100_000, 200_000, 300_000))
        assertEquals(300_000L, TamperPenalty.remainingMs(10_000_000, 0, 300_000)) // never more than full
    }

    @Test
    fun phrase_isLongEnoughToCost() {
        assertTrue(TypedPhrase(TamperPenalty.PHRASE).wordCount >= 20)
    }
}

class VibrationLadderTest {
    @Test
    fun getsStrongerEveryStep() {
        val amps = (0 until VibrationLadder.LEVELS).map { VibrationLadder.pattern(it).amplitudes.max() }
        assertEquals(amps.sorted(), amps)
        assertEquals(255, amps.last())
        val duty = (0 until VibrationLadder.LEVELS).map {
            val t = VibrationLadder.pattern(it).timings
            t[1].toFloat() / (t[1] + t[2])
        }
        assertEquals(duty.sorted(), duty)
    }

    @Test
    fun level_followsElapsedTime() {
        assertEquals(0, VibrationLadder.level(0, startAtMax = false))
        assertEquals(1, VibrationLadder.level(VibrationLadder.STEP_MS, false))
        assertEquals(VibrationLadder.LEVELS - 1, VibrationLadder.level(10 * 60_000, false))
        assertEquals(VibrationLadder.LEVELS - 1, VibrationLadder.level(0, startAtMax = true))
        assertEquals(0, VibrationLadder.level(-5_000, false))
    }

    @Test
    fun patterns_areValidForAndroid() {
        for (l in -2..10) {
            val p = VibrationLadder.pattern(l)
            assertEquals(p.timings.size, p.amplitudes.size)
            assertTrue(p.timings.all { it >= 0 })
            assertTrue(p.amplitudes.all { it in 0..255 })
            assertTrue(p.amplitudes.any { it > 0 })
        }
    }
}

class TypedPhraseTest {
    private val p = TypedPhrase("Aku memilih bangun, sekarang!")

    @Test
    fun ignoresCaseAccentsAndPunctuation() {
        assertTrue(p.matches("aku   MEMILIH bangun sekarang"))
        assertTrue(p.matches("Akú memilih bangun... sekarang"))
        assertFalse(p.matches("aku memilih bangun"))
        assertFalse(p.matches("aku memilih tidur sekarang"))
    }

    @Test
    fun progress() {
        assertEquals(0, p.correctWords(""))
        assertEquals(2, p.correctWords("aku memilih tid"))
        assertEquals(4, p.correctWords("aku memilih bangun sekarang"))
        assertEquals(4, p.wordCount)
    }

    @Test
    fun emptyPhrase_neverMatches() {
        assertFalse(TypedPhrase("  ...  ").matches(""))
    }

    @Test
    fun emergencyStop_stillBehavesTheSame() {
        assertTrue(EmergencyStop.phraseMatches(EmergencyStop.PHRASE.uppercase()))
        assertEquals(EmergencyStop.wordCount, EmergencyStop.correctWords(EmergencyStop.PHRASE))
    }
}

class DeskRestTest {
    private val faceDownFlat = Orientation(Pose.FACE_DOWN, -88f, Float.NaN)

    @Test
    fun faceDown_coveredAndStill_isResting() {
        assertEquals(Pose.RESTING, DeskRest.resolve(faceDownFlat, proximityNear = true, still = true).pose)
    }

    @Test
    fun anyMissingSignal_keepsFaceDown() {
        assertEquals(Pose.FACE_DOWN, DeskRest.resolve(faceDownFlat, proximityNear = false, still = true).pose)
        assertEquals(Pose.FACE_DOWN, DeskRest.resolve(faceDownFlat, proximityNear = null, still = true).pose)
        assertEquals(Pose.FACE_DOWN, DeskRest.resolve(faceDownFlat, proximityNear = true, still = false).pose)
    }

    @Test
    fun onlyNearlyFlatFaceDownQualifies() {
        val tilted = Orientation(Pose.FACE_DOWN, -40f, 0f)
        assertEquals(Pose.FACE_DOWN, DeskRest.resolve(tilted, true, true).pose)
        val sideways = Orientation(Pose.SIDEWAYS, -5f, 90f)
        assertEquals(Pose.SIDEWAYS, DeskRest.resolve(sideways, true, true).pose)
        val nan = Orientation(Pose.FACE_DOWN, Float.NaN, Float.NaN)
        assertEquals(Pose.FACE_DOWN, DeskRest.resolve(nan, true, true).pose)
    }

    @Test
    fun resting_isNeverSuspicious_evenInStrictMode() {
        assertFalse(Pose.RESTING.isSuspicious)
        assertFalse(GuardConfig(strictMode = true).isSuspicious(Pose.RESTING))
        assertEquals(Evidence.NONE, LyingJudge.evidence(Orientation.of(Pose.RESTING), GuardConfig(strictMode = true)))
    }

    @Test
    fun isNear() {
        assertEquals(true, DeskRest.isNear(0f, 5f))
        assertEquals(false, DeskRest.isNear(5f, 5f))
        assertEquals(true, DeskRest.isNear(1f, 8f))
        assertEquals(false, DeskRest.isNear(8f, 8f))
        assertNull(DeskRest.isNear(Float.NaN, 5f))
        assertNull(DeskRest.isNear(0f, 0f))
    }
}

class StillnessMeterTest {
    private fun feed(m: StillnessMeter, noise: Float, seconds: Int, rnd: Random = Random(1), start: Long = 0): Long {
        var t = start
        repeat(seconds * 5) {
            m.add(0f, 0f, -9.81f + (rnd.nextFloat() - 0.5f) * 2 * noise, t)
            t += 200
        }
        return t - 200
    }

    @Test
    fun deskNoise_isStill() {
        val m = StillnessMeter()
        val t = feed(m, noise = 0.01f, seconds = 4)
        assertTrue(m.isStill(t))
    }

    @Test
    fun handTremor_isNotStill() {
        val m = StillnessMeter()
        val t = feed(m, noise = 0.25f, seconds = 4)
        assertFalse(m.isStill(t))
    }

    @Test
    fun notEnoughData_isNotStill() {
        val m = StillnessMeter()
        val t = feed(m, noise = 0f, seconds = 1)
        assertFalse(m.isStill(t))
    }

    @Test
    fun staleData_isNotStill() {
        val m = StillnessMeter()
        val t = feed(m, noise = 0f, seconds = 4)
        assertFalse(m.isStill(t + 10_000))
    }

    @Test
    fun movementLeavesTheWindow() {
        val m = StillnessMeter()
        var t = feed(m, noise = 0.5f, seconds = 2)
        t = feed(m, noise = 0.005f, seconds = 4, start = t + 200)
        assertTrue(m.isStill(t))
    }

    @Test
    fun garbageAndClockJumps_areSafe() {
        val m = StillnessMeter()
        m.add(Float.NaN, 0f, 0f, 0)
        m.add(0f, Float.POSITIVE_INFINITY, 0f, 0)
        val t = feed(m, noise = 0f, seconds = 4, start = 10_000)
        m.add(0f, 0f, -9.81f, 5) // clock went backwards
        assertFalse(m.isStill(t))
        assertFalse(m.isStill(5))
    }
}

class NightStatsTamperTest {
    @Test
    fun tamperBreaksTheStreak() {
        val records = listOf(
            NightRecord(night = 1, guarded = true),
            NightRecord(night = 2, guarded = true, tampers = 1),
            NightRecord(night = 3, guarded = true),
        )
        assertEquals(1, NightStats.streak(records, tonight = 4))
        // tonight already has a tamper: tonight counts (as broken) right away
        assertEquals(0, NightStats.streak(records + NightRecord(4, guarded = true, tampers = 1), tonight = 4))
    }

    @Test
    fun summaryReportsTampers() {
        val s = NightStats.summary(listOf(NightRecord(10, guarded = true, tampers = 2)), tonight = 10, partnerName = null)
        assertTrue(s, s.contains("mengecilkan volume alarm 2 kali"))
        assertTrue(s, s.contains("Malam bersih (tidak ketahuan rebahan): 0"))
    }
}
