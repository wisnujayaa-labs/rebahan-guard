package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CalibratorTest {
    private fun around(center: Float, spread: Float, n: Int = 50, seed: Int = 1): List<Float> {
        val rnd = Random(seed)
        return List(n) { center + (rnd.nextFloat() * 2f - 1f) * spread }
    }

    @Test
    fun clearlySeparatedPostures_thresholdLiesBetween() {
        val sitting = around(35f, 8f)        // looking down at the phone
        val lying = around(-20f, 6f, seed = 2) // propped up, looking slightly up
        val outcome = Calibrator.calibrate(sitting, lying) as Calibrator.Outcome.Ok
        val r = outcome.result
        assertTrue(r.separable)
        assertTrue("threshold=${r.lyingElevationDeg}", r.lyingElevationDeg > -14f && r.lyingElevationDeg < 27f)
        // The learned threshold must classify the recordings correctly.
        assertTrue(lying.filter { it < r.lyingElevationDeg }.size >= 45)
        assertTrue(sitting.none { it < r.lyingElevationDeg })
    }

    @Test
    fun overlappingPostures_stillGiveAThresholdBetweenTheMedians() {
        val outcome = Calibrator.calibrate(around(10f, 15f), around(-5f, 15f, seed = 3))
        val r = (outcome as Calibrator.Outcome.Ok).result
        assertFalse(r.separable)
        assertTrue(r.lyingElevationDeg > r.lyingMedianDeg && r.lyingElevationDeg < r.sittingMedianDeg)
    }

    @Test
    fun lyingLooksMoreUpwardThanSitting_isNotSeparable() {
        val outcome = Calibrator.calibrate(around(-10f, 5f), around(20f, 5f))
        assertEquals(Calibrator.Outcome.NotSeparable, outcome)
    }

    @Test
    fun tooFewOrGarbageSamples_isNotEnoughData() {
        assertEquals(Calibrator.Outcome.NotEnoughData, Calibrator.calibrate(emptyList(), around(0f, 1f)))
        assertEquals(Calibrator.Outcome.NotEnoughData, Calibrator.calibrate(around(30f, 1f, n = 9), around(-30f, 1f)))
        val garbage = List(100) { Float.NaN } + List(100) { 500f }
        assertEquals(Calibrator.Outcome.NotEnoughData, Calibrator.calibrate(garbage, around(-30f, 1f)))
    }

    @Test
    fun aFewWildSamples_doNotRuinTheResult() {
        // Hand jerks while recording: 3 extreme samples among 50.
        val sitting = around(35f, 5f) + listOf(-80f, -75f, -85f)
        val lying = around(-25f, 5f, seed = 4) + listOf(80f, 85f)
        val r = (Calibrator.calibrate(sitting, lying) as Calibrator.Outcome.Ok).result
        assertTrue(r.separable)
        assertTrue(r.lyingElevationDeg > -20f && r.lyingElevationDeg < 30f)
    }

    @Test
    fun result_isAlwaysWithinTheAllowedRange() {
        val rnd = Random(9)
        repeat(2_000) {
            val sit = around(rnd.nextFloat() * 180f - 90f, rnd.nextFloat() * 40f, seed = rnd.nextInt())
            val lie = around(rnd.nextFloat() * 180f - 90f, rnd.nextFloat() * 40f, seed = rnd.nextInt())
            val outcome = Calibrator.calibrate(sit, lie)
            if (outcome is Calibrator.Outcome.Ok) {
                val t = outcome.result.lyingElevationDeg
                assertTrue("t=$t", t >= SensorInput.MIN_LYING_ELEVATION_DEG && t <= SensorInput.MAX_LYING_ELEVATION_DEG)
                GuardConfig(lyingElevationDeg = t) // must be accepted
            }
        }
    }

    @Test
    fun percentile_basics() {
        assertEquals(1f, Calibrator.percentile(listOf(1f), 0.5f), 0f)
        assertEquals(2.5f, Calibrator.percentile(listOf(1f, 2f, 3f, 4f), 0.5f), 0.0001f)
        assertEquals(1f, Calibrator.percentile(listOf(1f, 2f, 3f), 0f), 0f)
        assertEquals(3f, Calibrator.percentile(listOf(1f, 2f, 3f), 1f), 0f)
    }
}

class AlarmSoundPolicyTest {
    @Test
    fun systemAndPickedSounds_areAccepted() {
        val ok = listOf(
            "content://settings/system/alarm_alert",
            "content://media/internal/audio/media/42",
            "content://com.android.providers.media.documents/document/audio%3A1234",
            "android.resource://io.github.wisnujayaa.rebahanguard/raw/beep",
        )
        for (u in ok) assertEquals(u, AlarmSoundPolicy.sanitize(u))
    }

    @Test
    fun everythingElse_isRejected() {
        val bad = listOf(
            null, "", "   ", "content://", "content", "://x",
            "file:///sdcard/Music/song.mp3",       // raw file paths: no permission model
            "http://example.com/a.mp3",            // never stream from the network
            "https://example.com/a.mp3",
            "javascript:alert(1)",
            "content://media/audio\n/1",           // control character
            "content://media/audio /1",            // whitespace
            "content://" + "a".repeat(3_000),      // oversized
        )
        for (u in bad) assertNull("$u", AlarmSoundPolicy.sanitize(u))
    }

    @Test
    fun schemeCheck_isCaseInsensitive() =
        assertEquals("CONTENT://media/1", AlarmSoundPolicy.sanitize("CONTENT://media/1"))
}

class SensorInputElevationTest {
    @Test
    fun elevationSetting_isClamped() {
        assertEquals(PoseClassifier.DEFAULT_LYING_ELEVATION_DEG, SensorInput.sanitizeLyingElevationDeg(Float.NaN), 0f)
        assertEquals(PoseClassifier.DEFAULT_LYING_ELEVATION_DEG, SensorInput.sanitizeLyingElevationDeg(Float.POSITIVE_INFINITY), 0f)
        assertEquals(SensorInput.MIN_LYING_ELEVATION_DEG, SensorInput.sanitizeLyingElevationDeg(-1000f), 0f)
        assertEquals(SensorInput.MAX_LYING_ELEVATION_DEG, SensorInput.sanitizeLyingElevationDeg(1000f), 0f)
        assertEquals(-12f, SensorInput.sanitizeLyingElevationDeg(-12f), 0f)
    }

    @Test
    fun orientation_respectsTheConfiguredThreshold() {
        val slightlyDown = floatArrayOf(0f, 9.7f, -1.5f) // ~ -9°
        assertEquals(Pose.FACE_DOWN, SensorInput.toOrientation(slightlyDown, false, -5f).pose)
        assertEquals(Pose.UPRIGHT, SensorInput.toOrientation(slightlyDown, false, -20f).pose)
        assertEquals(Pose.UNKNOWN, SensorInput.toOrientation(slightlyDown, true, -5f).pose)
    }
}
