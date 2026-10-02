package io.github.wisnujayaa.rebahanguard.service

import io.github.wisnujayaa.rebahanguard.core.CheckReport
import io.github.wisnujayaa.rebahanguard.core.LockReason
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.Pose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LastCheck(
    val atMillis: Long,
    val faceWidthRatio: Float?,
    val rollDeg: Float?,
    val headTiltDeg: Float?,
    val lying: Boolean,
    val report: CheckReport? = null,
    val lockReason: LockReason? = null,
)

data class GuardStatus(
    val running: Boolean = false,
    val screenOn: Boolean = true,
    val phase: Phase = Phase.WATCHING,
    val pose: Pose = Pose.UNKNOWN,
    /** Live screen angle (+90 ceiling … -90 floor), shown to help users calibrate. */
    val screenElevationDeg: Float = Float.NaN,
    val lastCheck: LastCheck? = null,
    /** Most recent camera checks, newest first (diagnostics). */
    val checks: List<LastCheck> = emptyList(),
    val outsideSchedule: Boolean = false,
    /** > 0 while the "sit up now" warning is counting down (monotonic clock). */
    val warningUntilElapsedMs: Long = 0,
)

/** Single source of truth shared by the service (writer) and the UI (reader). */
object GuardStatusStore {
    private val _status = MutableStateFlow(GuardStatus())
    val status: StateFlow<GuardStatus> = _status.asStateFlow()

    fun update(transform: (GuardStatus) -> GuardStatus) = _status.update(transform)
}
