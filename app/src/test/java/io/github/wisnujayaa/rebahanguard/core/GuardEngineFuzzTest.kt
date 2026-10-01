package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Randomized ("fuzz") testing of [GuardEngine].
 *
 * A fake phone keeps track of whether the camera and the alarm are physically on, then fires
 * hundreds of thousands of random events at the engine: poses, camera results (good, garbage,
 * late, duplicated), screen-offs, long pauses and, optionally, a clock that jumps backwards.
 * After every single event the safety invariants below must hold. Seeds are fixed, so any
 * failure is reproducible: the message tells you which seed and step broke.
 */
class GuardEngineFuzzTest {

    private val config = GuardConfig(
        triggerDelayMs = 5_000,
        cooldownMs = 20_000,
        releaseMs = 1_500,
        cameraWindowMs = 3_000,
        checkTimeoutMs = 10_000,
        maxAlarmMs = 30_000,
    )

    private class Stats {
        var checks = 0
        var alarms = 0
        var watchdogTimeouts = 0
        var alarmCaps = 0
        var spuriousResults = 0
    }

    private class FakePhone(
        val config: GuardConfig,
        val rnd: Random,
        val seed: Int,
        val clockCanGoBackwards: Boolean,
        val stats: Stats,
    ) {
        val engine = GuardEngine(config)
        var now = 1_000_000L
        var pose = Pose.UPRIGHT
        var cameraOn = false
        var cameraSince = 0L
        var alarmOn = false
        var alarmSince = 0L
        var step = 0

        private fun where() = "seed=$seed step=$step phase=${engine.phase}"

        private fun check(condition: Boolean, what: String) {
            if (!condition) throw AssertionError("Invariant broken: $what (${where()})")
        }

        fun run(steps: Int) {
            repeat(steps) {
                step = it
                val r = rnd.nextInt(100)
                when {
                    r < 70 -> poseEvent(shortStep())
                    r < 80 -> poseEvent(rnd.nextLong(0, 120_000)) // phone left alone a while
                    r < 93 -> faceResultEvent()
                    else -> screenOffEvent()
                }
                checkHardwareMatchesPhase()
            }
        }

        private fun shortStep(): Long =
            if (rnd.nextInt(10) == 0) 0 else rnd.nextLong(1, 3_000) // 0 = two events, same ms

        private fun advanceClock(by: Long) {
            now += by
            if (clockCanGoBackwards && rnd.nextInt(30) == 0) now -= rnd.nextLong(0, 10_000)
        }

        private fun poseEvent(gap: Long) {
            advanceClock(gap)
            // Poses are "sticky" like real life, so long suspicious stretches actually happen.
            if (rnd.nextInt(10) == 0) pose = Pose.entries[rnd.nextInt(Pose.entries.size)]

            val wasAlarming = engine.phase == Phase.ALARMING
            val wasChecking = engine.phase == Phase.CHECKING
            val action = engine.onPose(pose, now)
            apply(action, fromFaceResult = false, face = null)

            if (wasChecking && action == Action.CANCEL_CAMERA_CHECK) stats.watchdogTimeouts++
            if (wasAlarming && action == Action.STOP_ALARM && engine.phase == Phase.COOLDOWN) {
                stats.alarmCaps++
            }

            if (!clockCanGoBackwards) {
                // Nothing may stay on longer than its limit (checked at every sensor event).
                if (alarmOn) check(now - alarmSince < config.maxAlarmMs, "alarm rang too long")
                if (cameraOn) check(now - cameraSince < config.checkTimeoutMs, "camera stuck on")
            }
        }

        private fun randomFace(): FaceObservation? = when (rnd.nextInt(8)) {
            0 -> null
            1 -> FaceObservation(Float.NaN, 0f)
            2 -> FaceObservation(0.4f, Float.POSITIVE_INFINITY)
            3 -> FaceObservation(-0.3f, 0f)
            4 -> FaceObservation(rnd.nextFloat() * 0.15f, rnd.nextFloat() * 360f - 180f) // far away
            5 -> FaceObservation(0.3f + rnd.nextFloat(), 85f + rnd.nextFloat() * 10f)   // sitting
            else -> FaceObservation(0.25f + rnd.nextFloat(), rnd.nextFloat() * 360f - 180f)
        }

        private fun faceResultEvent() {
            advanceClock(rnd.nextLong(0, 1_500))
            val face = randomFace()
            val phaseBefore = engine.phase

            if (!cameraOn) {
                // A late / duplicate / unexpected result: must be ignored completely.
                stats.spuriousResults++
                val action = engine.onFaceResult(face, now)
                check(action == Action.NONE, "spurious face result caused $action")
                check(engine.phase == phaseBefore, "spurious face result changed the phase")
                return
            }

            cameraOn = false // the camera check is finished once it reports
            val action = engine.onFaceResult(face, now)
            apply(action, fromFaceResult = true, face = face)
            check(
                engine.phase == Phase.ALARMING || engine.phase == Phase.COOLDOWN,
                "a finished check must end in ALARMING or COOLDOWN",
            )
        }

        private fun screenOffEvent() {
            advanceClock(rnd.nextLong(0, 2_000))
            apply(engine.onScreenOff(), fromFaceResult = false, face = null)
            check(engine.phase == Phase.WATCHING, "screen off must reset to WATCHING")
            check(!cameraOn && !alarmOn, "screen off must turn everything off")
        }

        private fun apply(action: Action, fromFaceResult: Boolean, face: FaceObservation?) {
            when (action) {
                Action.NONE -> Unit
                Action.START_CAMERA_CHECK -> {
                    check(!cameraOn, "camera started twice")
                    check(!alarmOn, "camera started while the alarm rings")
                    cameraOn = true
                    cameraSince = now
                    stats.checks++
                }
                Action.CANCEL_CAMERA_CHECK -> {
                    check(cameraOn, "cancelled a camera that was not on")
                    cameraOn = false
                }
                Action.START_ALARM -> {
                    check(fromFaceResult, "alarm started without a camera check")
                    check(face != null && face.isValid, "alarm started from an invalid face")
                    check(face!!.faceWidthRatio >= config.minFaceWidthRatio, "alarm from a distant face")
                    check(!alarmOn, "alarm started twice")
                    alarmOn = true
                    alarmSince = now
                    stats.alarms++
                }
                Action.STOP_ALARM -> {
                    check(alarmOn, "stopped an alarm that was not ringing")
                    alarmOn = false
                }
            }
        }

        private fun checkHardwareMatchesPhase() {
            check(cameraOn == (engine.phase == Phase.CHECKING), "camera state != phase")
            check(alarmOn == (engine.phase == Phase.ALARMING), "alarm state != phase")
        }
    }

    private fun fuzz(seeds: IntRange, steps: Int, clockCanGoBackwards: Boolean): Stats {
        val stats = Stats()
        for (seed in seeds) {
            FakePhone(config, Random(seed), seed, clockCanGoBackwards, stats).run(steps)
        }
        return stats
    }

    @Test
    fun invariantsHold_forRandomEventStreams() {
        val stats = fuzz(seeds = 1..400, steps = 2_000, clockCanGoBackwards = false)
        // Make sure the random streams really reached every interesting path,
        // otherwise a "passing" fuzz test would prove nothing.
        assertTrue("checks=${stats.checks}", stats.checks > 1_000)
        assertTrue("alarms=${stats.alarms}", stats.alarms > 100)
        assertTrue("watchdog=${stats.watchdogTimeouts}", stats.watchdogTimeouts > 10)
        assertTrue("alarmCaps=${stats.alarmCaps}", stats.alarmCaps > 10)
        assertTrue("spurious=${stats.spuriousResults}", stats.spuriousResults > 1_000)
    }

    @Test
    fun invariantsHold_evenWhenTheClockMisbehaves() {
        val stats = fuzz(seeds = 1_000..1_200, steps = 2_000, clockCanGoBackwards = true)
        assertTrue(stats.checks > 100)
    }

    @Test
    fun sameSeed_sameBehaviour() {
        // Determinism: the engine has no hidden randomness or wall-clock dependence.
        fun trace(seed: Int): List<Any> {
            val rnd = Random(seed)
            val e = GuardEngine(config)
            var t = 0L
            return List(5_000) {
                t += rnd.nextLong(0, 4_000)
                val pose = Pose.entries[rnd.nextInt(Pose.entries.size)]
                if (rnd.nextInt(20) == 0) e.onFaceResult(FaceObservation(0.4f, 0f), t)
                else e.onPose(pose, t)
            }
        }
        assertEquals(trace(99), trace(99))
    }
}
