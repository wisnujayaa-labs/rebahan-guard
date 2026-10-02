package io.github.wisnujayaa.rebahanguard.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.github.wisnujayaa.rebahanguard.core.DeskRules
import io.github.wisnujayaa.rebahanguard.core.DeskTally
import io.github.wisnujayaa.rebahanguard.core.DeskVerdict
import io.github.wisnujayaa.rebahanguard.core.GuardConfig
import io.github.wisnujayaa.rebahanguard.core.Habit
import io.github.wisnujayaa.rebahanguard.core.HabitUnit
import io.github.wisnujayaa.rebahanguard.core.Orientation
import io.github.wisnujayaa.rebahanguard.core.PlaceRules
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.core.Proof
import io.github.wisnujayaa.rebahanguard.core.StepProof
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the UI shows about the running session. */
data class ActiveSession(
    val habitId: Long,
    val title: String,
    val proof: Proof,
    val unit: HabitUnit,
    val startedWallMs: Long,
    val startedElapsedMs: Long,
    val targetMs: Long,
    val desk: DeskTally? = null,
    val creditedMinutes: Int = 0,
    val steps: Int = 0,
    val movingMs: Long = 0,
    val atPlace: Boolean? = null,
    /** Non-null while a desk alarm is ringing: why. */
    val alarm: String? = null,
    val lastVerdict: DeskVerdict? = null,
    val placeName: String? = null,
)

object SessionStore {
    private val _session = MutableStateFlow<ActiveSession?>(null)
    val session: StateFlow<ActiveSession?> = _session.asStateFlow()

    /** Strongest proof seen per habit today: lets "catat hasil" after a session carry its weight. */
    private val strengthToday = HashMap<Pair<Long, Int>, Int>()

    internal fun set(s: ActiveSession?) { _session.value = s }
    internal fun update(f: (ActiveSession) -> ActiveSession) = _session.update { it?.let(f) }

    fun noteStrength(habitId: Long, day: Int, strength: Int) {
        val k = habitId to day
        strengthToday[k] = maxOf(strengthToday[k] ?: 0, strength)
    }

    fun strength(habitId: Long, day: Int): Int = strengthToday[habitId to day] ?: 1
}

/**
 * Runs one session inside the guard service: random desk checks with the camera, movement from
 * the step counter, presence at a saved place, or simply time without the phone. Progress is
 * written to [DreamStore] as it is earned, so nothing is lost if the service is killed.
 */
class SessionRunner(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val config: GuardConfig,
    private val faceChecker: FaceChecker,
    private val alarm: AlarmPlayer,
    private val orientation: () -> Orientation,
    private val screenOn: () -> Boolean,
    private val notify: (String) -> Unit,
    /** The alarm started: the service holds the volume up and counts attempts to lower it. */
    private val onAlarm: () -> Unit = {},
    /** In a call or the emergency pause: no checks, no alarms. */
    private val paused: () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.nanoTime())
    private var creditAccMs = 0L
    private var lastTickMs = 0L
    private var lastStepTotal: Float? = null
    private var lastStepMs = 0L

    val active: ActiveSession? get() = SessionStore.session.value
    val isRunning: Boolean get() = active != null
    val needsSensorsWithScreenOff: Boolean get() = active?.proof == Proof.DESK

    private val deskCheck = Runnable { runDeskCheck() }
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (isRunning) handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(habit: Habit, durationMin: Int) {
        stop(silent = true)
        val now = SystemClock.elapsedRealtime()
        val place = if (habit.proof == Proof.PLACE) PlaceStore.load(context).firstOrNull() else null
        SessionStore.set(
            ActiveSession(
                habitId = habit.id, title = habit.title, proof = habit.proof, unit = habit.unit,
                startedWallMs = System.currentTimeMillis(), startedElapsedMs = now,
                targetMs = durationMin.coerceIn(5, 240) * 60_000L,
                desk = if (habit.proof == Proof.DESK) DeskTally(now, durationMin.coerceIn(5, 240) * 60_000L) else null,
                placeName = place?.name,
            )
        )
        creditAccMs = 0
        lastTickMs = now
        lastStepTotal = null
        if (habit.proof == Proof.DESK) handler.postDelayed(deskCheck, 45_000) // first look soon: is the stand set up?
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop(silent: Boolean = false) {
        val s = active ?: return
        handler.removeCallbacks(deskCheck)
        handler.removeCallbacks(tick)
        if (s.alarm != null) alarm.stop()
        flushCredit(force = true)
        SessionStore.noteStrength(s.habitId, DreamStore.today(), strengthOf(s))
        SessionStore.set(null)
        if (!silent) notify("Sesi “${s.title}” selesai: ${creditSummary(s)}.")
    }

    private fun creditSummary(s: ActiveSession): String = when (s.proof) {
        Proof.MOVE -> "${s.steps} langkah"
        else -> "${s.creditedMinutes} menit tercatat"
    }

    /** 3 = sensors decided, 2 = partial evidence, 1 = nothing verified. */
    private fun strengthOf(s: ActiveSession): Int = when (s.proof) {
        Proof.DESK -> s.desk?.let { if (it.checks >= 2 && it.presence >= 0.8f) 3 else if (it.checks >= 1) 2 else 1 } ?: 1
        Proof.MOVE -> if (StepProof.kind(s.steps, s.movingMs) != StepProof.Kind.NONE) 3 else 1
        Proof.PLACE -> if (s.atPlace == true) 2 else 1
        Proof.FOCUS -> 2
        Proof.PHOTO -> 2
        Proof.HONEST -> 1
    }

    // ------------------------------------------------------------------ desk (Mode Meja)

    private fun runDeskCheck() {
        val s = active ?: return
        if (s.proof != Proof.DESK) return
        if (paused() || faceChecker.isChecking || CameraGate.photoInUse) {
            // In a call, or the camera is busy (guard check, photo screen): try again shortly.
            handler.postDelayed(deskCheck, 15_000)
            return
        }
        val sessionId = s.startedElapsedMs
        // Watchdog: if the guard cancels this check (its callback never comes), look again.
        handler.postDelayed(deskCheck, config.checkTimeoutMs + DeskRules.RECHECK_MS)
        faceChecker.check(owner = owner) { face, _ ->
            if (active?.startedElapsedMs != sessionId) return@check // a newer session started meanwhile
            handler.removeCallbacks(deskCheck)
            val now = SystemClock.elapsedRealtime()
            val verdict = DeskRules.judge(face, orientation(), config)
            val tally = active?.desk ?: return@check
            val (next, action) = tally.record(verdict, now)
            val gained = next.creditedMs - tally.creditedMs
            if (gained > 0 && s.unit == HabitUnit.MINUTES) credit(gained, 3)
            SessionStore.update { it.copy(desk = next, lastVerdict = verdict) }
            when (action) {
                DeskTally.Action.ALARM_LYING -> raise("Kamu terlihat rebahan. Kembali ke meja.")
                DeskTally.Action.ALARM_ABSENT -> raise("Kamu tidak terlihat di meja. Kembali duduk.")
                DeskTally.Action.WARN_ABSENT -> { alarm.warn(); notify("Kamu tidak terlihat di meja. Pemeriksaan berikutnya sebentar lagi.") }
                DeskTally.Action.CLEAR -> clearAlarm()
                DeskTally.Action.NONE -> Unit
            }
            val wrong = action != DeskTally.Action.CLEAR && action != DeskTally.Action.NONE
            if (next.isComplete) {
                notify("Target sesi “${s.title}” tercapai.")
                stop()
                return@check
            }
            handler.postDelayed(deskCheck, if (wrong) DeskRules.RECHECK_MS else DeskRules.nextGapMs(rnd))
        }
    }

    /** A call came in or the emergency button was pressed: silence any desk alarm. */
    fun pauseAlarm() = clearAlarm()

    private fun raise(why: String) {
        if (paused()) return
        SessionStore.update { it.copy(alarm = why) }
        alarm.start(vibrateAtMax = false)
        onAlarm()
        notify(why)
    }

    private fun clearAlarm() {
        if (active?.alarm == null) return
        SessionStore.update { it.copy(alarm = null) }
        alarm.stop()
    }

    // ------------------------------------------------------------------ periodic work

    private fun onTick() {
        val s = active ?: return
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTickMs).coerceIn(0, 2 * TICK_MS)
        lastTickMs = now
        when (s.proof) {
            // "Fokus tanpa HP": time counts while the phone is not in use (screen off, or face
            // down and still on a desk).
            Proof.FOCUS -> if (!screenOn() || orientation().pose == Pose.RESTING) credit(dt, 2)
            Proof.PLACE -> {
                val at = isAtPlace()
                SessionStore.update { it.copy(atPlace = at) }
                if (at == true) credit(dt, 2)
            }
            Proof.MOVE -> if (s.unit == HabitUnit.MINUTES && now - lastStepMs < 15_000) credit(dt, 3)
            else -> Unit
        }
        // Desk sessions end when enough time was confirmed; this cap ends them anyway if the
        // camera could never confirm anything (dark room, phone moved, checks cancelled).
        val limit = if (s.proof == Proof.DESK) s.targetMs * 2 else s.targetMs
        if (now - s.startedElapsedMs >= limit) {
            notify("Waktu sesi “${s.title}” sudah habis.")
            stop()
        }
    }

    /** Raw step-counter readings (total since boot). */
    fun onStepCounter(total: Float) {
        val s = active ?: return
        if (s.proof != Proof.MOVE) return
        val now = SystemClock.elapsedRealtime()
        val delta = StepProof.delta(lastStepTotal, total)
        lastStepTotal = total
        if (delta <= 0) return
        val moving = if (lastStepMs > 0 && now - lastStepMs < 15_000) now - lastStepMs else 0
        lastStepMs = now
        SessionStore.update { it.copy(steps = it.steps + delta, movingMs = it.movingMs + moving) }
        if (s.unit == HabitUnit.STEPS) {
            val strength = if (StepProof.kind(s.steps + delta, s.movingMs + moving) != StepProof.Kind.NONE) 3 else 2
            DreamStore.addProgress(context, s.habitId, delta, strength)
        }
    }

    private fun credit(ms: Long, strength: Int) {
        val s = active ?: return
        if (s.unit != HabitUnit.MINUTES) return
        creditAccMs += ms
        flushCredit(force = false, strength = strength)
    }

    private fun flushCredit(force: Boolean, strength: Int = 2) {
        val s = active ?: return
        val minutes = (creditAccMs / 60_000).toInt()
        if (minutes <= 0) return
        creditAccMs -= minutes * 60_000L
        DreamStore.addProgress(context, s.habitId, minutes, strength)
        SessionStore.update { it.copy(creditedMinutes = it.creditedMinutes + minutes) }
        if (force) creditAccMs = 0
    }

    // ------------------------------------------------------------------ place

    @SuppressLint("MissingPermission") // checked just below
    private fun isAtPlace(): Boolean? {
        val place = PlaceStore.load(context).firstOrNull { it.name == active?.placeName } ?: return null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val bssids = PlaceRules.normalizeBssids(wifi.scanResults.mapNotNull { it.BSSID })
            val loc = bestLocation()
            PlaceRules.isAt(place, loc?.latitude, loc?.longitude, loc?.accuracy, bssids)
        } catch (e: Exception) {
            Log.w(TAG, "Place check failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in providers) {
                lm.getCurrentLocation(p, null, ContextCompat.getMainExecutor(context)) { /* refreshes the cache */ }
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .minByOrNull { it.accuracy }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val TAG = "SessionRunner"
        const val TICK_MS = 10_000L
    }
}
