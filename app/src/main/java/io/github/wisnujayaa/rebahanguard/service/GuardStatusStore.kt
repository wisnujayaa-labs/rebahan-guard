package io.github.wisnujayaa.rebahanguard.service

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
    val lying: Boolean,
)

data class GuardStatus(
    val running: Boolean = false,
    val screenOn: Boolean = true,
    val phase: Phase = Phase.WATCHING,
    val pose: Pose = Pose.UNKNOWN,
    val lastCheck: LastCheck? = null,
)

/** Single source of truth shared by the service (writer) and the UI (reader). */
object GuardStatusStore {
    private val _status = MutableStateFlow(GuardStatus())
    val status: StateFlow<GuardStatus> = _status.asStateFlow()

    fun update(transform: (GuardStatus) -> GuardStatus) = _status.update(transform)
}
