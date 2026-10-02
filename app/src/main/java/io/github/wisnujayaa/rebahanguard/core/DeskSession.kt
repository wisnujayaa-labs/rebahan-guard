package io.github.wisnujayaa.rebahanguard.core

import kotlin.math.ln
import kotlin.random.Random

/**
 * Mode Meja: the phone stands on the desk facing the user, and at random moments the front
 * camera checks that someone is there with their head upright. It catches lying down even
 * without the phone — the user said: during study time, no lying down, phone or not.
 *
 * Random (exponential) gaps mean there is no safe moment to slip away: whatever happened so far,
 * the next check is equally likely to come at any second.
 */
enum class DeskVerdict { PRESENT, LYING, ABSENT, UNSURE }

object DeskRules {
    const val MEAN_GAP_MS = 4 * 60_000L
    const val MIN_GAP_MS = 90_000L
    const val MAX_GAP_MS = 7 * 60_000L

    /** While something is wrong (absent, lying), look again soon. */
    const val RECHECK_MS = 25_000L

    /** Absent this many checks in a row → alarm (once could be a bathroom break). */
    const val ABSENT_ALARM_AFTER = 2

    fun nextGapMs(rnd: Random): Long {
        val u = rnd.nextDouble().coerceIn(1e-9, 1.0)
        return (-ln(u) * MEAN_GAP_MS).toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    }

    /**
     * The face as seen by a phone standing upright on a desk. A visible face with the head close
     * to vertical = sitting. Head tilted past the limit = lying (e.g. phone propped up on the bed).
     */
    fun judge(face: FaceObservation?, phone: Orientation, config: GuardConfig): DeskVerdict {
        if (face == null || !face.isValid) return DeskVerdict.ABSENT
        if (face.faceWidthRatio < MIN_FACE_RATIO) return DeskVerdict.ABSENT // someone far away, not at the desk
        val tilt = LyingJudge.headTiltDeg(phone.inPlaneRotationDeg, face.rollDeg) ?: return DeskVerdict.UNSURE
        return if (tilt >= config.minHeadTiltDeg) DeskVerdict.LYING else DeskVerdict.PRESENT
    }

    /** At a desk the face is smaller than in the hand: the phone stands ~50–70 cm away. */
    const val MIN_FACE_RATIO = 0.06f
}

/**
 * The state of one desk session, as plain data: updated by [DeskTally.record] with each check
 * result. Minutes are credited only for gaps that ended with a PRESENT check, so a session
 * spent away from the desk earns nothing.
 */
data class DeskTally(
    val startedMs: Long,
    val targetMs: Long,
    val lastCheckMs: Long = startedMs,
    val creditedMs: Long = 0,
    val checks: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val lying: Int = 0,
    val absentStreak: Int = 0,
) {
    enum class Action { NONE, WARN_ABSENT, ALARM_LYING, ALARM_ABSENT, CLEAR }

    val creditedMinutes: Int get() = (creditedMs / 60_000).toInt()
    val isComplete: Boolean get() = creditedMs >= targetMs

    /** Share of checks that found the user at the desk, 0..1 (1 when nothing was checked yet). */
    val presence: Float get() = if (checks == 0) 1f else present.toFloat() / checks

    fun record(verdict: DeskVerdict, nowMs: Long): Pair<DeskTally, Action> {
        val gap = (nowMs - lastCheckMs).coerceIn(0, DeskRules.MAX_GAP_MS + DeskRules.RECHECK_MS)
        return when (verdict) {
            DeskVerdict.PRESENT -> copy(
                lastCheckMs = nowMs, creditedMs = (creditedMs + gap).coerceAtMost(targetMs),
                checks = checks + 1, present = present + 1, absentStreak = 0,
            ) to Action.CLEAR
            DeskVerdict.LYING -> copy(
                lastCheckMs = nowMs, checks = checks + 1, lying = lying + 1, absentStreak = 0,
            ) to Action.ALARM_LYING
            DeskVerdict.ABSENT -> {
                val streak = absentStreak + 1
                copy(lastCheckMs = nowMs, checks = checks + 1, absent = absent + 1, absentStreak = streak) to
                    if (streak >= DeskRules.ABSENT_ALARM_AFTER) Action.ALARM_ABSENT else Action.WARN_ABSENT
            }
            // Dark room, odd angle: no credit, no blame; the clock for the next gap restarts.
            DeskVerdict.UNSURE -> copy(lastCheckMs = nowMs) to Action.NONE
        }
    }
}
