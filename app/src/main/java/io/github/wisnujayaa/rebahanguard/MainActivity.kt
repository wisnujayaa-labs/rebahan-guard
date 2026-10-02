package io.github.wisnujayaa.rebahanguard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.wisnujayaa.rebahanguard.core.AlarmSoundPolicy
import io.github.wisnujayaa.rebahanguard.core.Calibrator
import io.github.wisnujayaa.rebahanguard.core.Commitment
import io.github.wisnujayaa.rebahanguard.core.EmergencyStop
import io.github.wisnujayaa.rebahanguard.core.EmergencyStopRules
import io.github.wisnujayaa.rebahanguard.core.Schedule
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.core.SensorInput
import io.github.wisnujayaa.rebahanguard.service.AlarmPlayer
import io.github.wisnujayaa.rebahanguard.service.CommitmentStore
import io.github.wisnujayaa.rebahanguard.service.PartnerStore
import io.github.wisnujayaa.rebahanguard.service.Protection
import io.github.wisnujayaa.rebahanguard.service.GuardService
import io.github.wisnujayaa.rebahanguard.service.GuardSettings
import io.github.wisnujayaa.rebahanguard.service.GuardStatus
import io.github.wisnujayaa.rebahanguard.service.GuardStatusStore
import io.github.wisnujayaa.rebahanguard.core.Bucket
import io.github.wisnujayaa.rebahanguard.core.Plan
import io.github.wisnujayaa.rebahanguard.service.PlanStore
import io.github.wisnujayaa.rebahanguard.ui.AccentRule
import io.github.wisnujayaa.rebahanguard.ui.CheckHistory
import io.github.wisnujayaa.rebahanguard.ui.DreamsTab
import io.github.wisnujayaa.rebahanguard.ui.Fraunces
import io.github.wisnujayaa.rebahanguard.ui.Kicker
import io.github.wisnujayaa.rebahanguard.ui.PlanRow
import io.github.wisnujayaa.rebahanguard.ui.PlanTab
import io.github.wisnujayaa.rebahanguard.ui.rememberNow
import io.github.wisnujayaa.rebahanguard.ui.Tone
import io.github.wisnujayaa.rebahanguard.ui.PartnerCodeField
import io.github.wisnujayaa.rebahanguard.ui.PartnerSection
import io.github.wisnujayaa.rebahanguard.ui.RebahanGuardTheme
import io.github.wisnujayaa.rebahanguard.ui.ScreenAngleDial
import io.github.wisnujayaa.rebahanguard.ui.StatsSection
import io.github.wisnujayaa.rebahanguard.ui.previewAlarmSound
import io.github.wisnujayaa.rebahanguard.ui.recordScreenElevations
import io.github.wisnujayaa.rebahanguard.ui.rememberLiveScreenElevation
import io.github.wisnujayaa.rebahanguard.ui.soundTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Status-bar icons follow the system light/dark mode, like the app's palette.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppSurface {
                GuardScreen()
            }
        }
    }
}

/**
 * The app's root: theme + a Surface that sets LocalContentColor, so every Text without an
 * explicit color inherits the palette's ink color (dark on paper, light at night).
 * Screenshot tests render through this too, so a regression here shows up in the images.
 */
@Composable
internal fun AppSurface(content: @Composable () -> Unit) {
    RebahanGuardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Tone.Ink, contentColor = Tone.Text) {
            content()
        }
    }
}

internal enum class Tab(val label: String) {
    TODAY("Hari ini"),
    PLAN("Rencana"),
    DREAMS("Impian"),
    SETTINGS("Atur"),
}

@Composable
private fun GuardScreen() {
    val context = LocalContext.current
    val status by GuardStatusStore.status.collectAsState()
    val liveAngle by rememberLiveScreenElevation()
    var settings by remember { mutableStateOf(GuardSettings.load(context)) }
    var cameraDenied by remember { mutableStateOf(false) }
    var needsOverlayPermission by remember { mutableStateOf(false) }
    var confirmCommitment by remember { mutableStateOf(false) }
    var showStopFlow by remember { mutableStateOf(false) }

    // Protection state is re-read regularly (not on every frame: the dial recomposes often).
    var commitmentLeftMs by remember { mutableLongStateOf(CommitmentStore.remainingMs(context)) }
    var hasPartner by remember { mutableStateOf(PartnerStore.hasPartner(context)) }
    var scheduleActive by remember { mutableStateOf(false) }
    var statsRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(status.running, settings.schedule) {
        while (true) {
            commitmentLeftMs = CommitmentStore.remainingMs(context)
            hasPartner = PartnerStore.hasPartner(context)
            scheduleActive = settings.schedule.enabled && settings.schedule.isWithin(Protection.minuteOfDay())
            statsRefresh++
            delay(15_000)
        }
    }
    val committed = commitmentLeftMs > 0
    val isProtected = committed || hasPartner || scheduleActive

    fun update(newSettings: GuardSettings) {
        settings = newSettings.sanitized()
        settings.save(context)
    }

    fun overlayReady() = !settings.lockScreen || Settings.canDrawOverlays(context)

    fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts the guard; begins the commitment first if one is configured and none is running. */
    fun launchGuard(beginCommitment: Boolean) {
        if (!overlayReady()) {
            needsOverlayPermission = true
            return
        }
        needsOverlayPermission = false
        if (beginCommitment && settings.commitmentHours > 0 && !CommitmentStore.isActive(context)) {
            CommitmentStore.begin(context, settings.commitmentHours)
            commitmentLeftMs = CommitmentStore.remainingMs(context)
        }
        GuardService.start(context, settings)
    }

    var pendingCommitment by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            cameraDenied = false
            launchGuard(beginCommitment = pendingCommitment)
        } else {
            // After "Don't ask again" Android returns instantly with no dialog: say why
            // nothing happened instead of failing silently.
            cameraDenied = true
        }
    }

    fun startGuard(beginCommitment: Boolean) {
        pendingCommitment = beginCommitment
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) launchGuard(beginCommitment) else permissionLauncher.launch(missing.toTypedArray())
    }

    // While protected, opening the app is enough to bring the guard back (after a restart, a
    // force stop or a battery saver).
    LaunchedEffect(status.running, committed, hasPartner, scheduleActive) {
        val wasRunning = CommitmentStore.isSessionOpen(context)
        val shouldRun = committed || ((hasPartner || scheduleActive) && wasRunning)
        if (shouldRun && !status.running && hasPermissions() && overlayReady()) {
            GuardService.start(context, settings)
        }
    }

    val editable = !status.running && !committed
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tone.Ink)
            .safeDrawingPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.TODAY -> TabColumn {
                    TodayHeader(liveAngle, settings.lyingElevationDeg)
                    UrgentCallout(onOpenPlan = { tab = Tab.PLAN })

                    StatusLines(status)

                    if (isProtected) {
                        ProtectionBanner(
                            committed = committed,
                            commitmentLeftMs = commitmentLeftMs,
                            partnerName = if (hasPartner) PartnerStore.name(context) ?: "Temanmu" else null,
                            schedule = settings.schedule.takeIf { scheduleActive },
                        )
                    }

                    when {
                        status.running && isProtected && showStopFlow -> StopFlow(
                            hasPartner = hasPartner,
                            onStopped = {
                                CommitmentStore.markLegitStop(context)
                                CommitmentStore.clear(context)
                                commitmentLeftMs = 0
                                showStopFlow = false
                                GuardService.stop(context)
                            },
                            onCancel = { showStopFlow = false },
                        )

                        status.running && isProtected -> TextButton(
                            onClick = { showStopFlow = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Matikan penjaga", color = Tone.Muted) }

                        status.running -> OutlinedButton(
                            onClick = { GuardService.stop(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Tone.Text),
                        ) { Text("Matikan penjaga", style = MaterialTheme.typography.titleMedium) }

                        else -> Button(
                            onClick = {
                                if (settings.commitmentHours > 0 && !committed) confirmCommitment = true
                                else startGuard(beginCommitment = false)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = CircleShape,
                        ) {
                            Text(
                                if (settings.commitmentHours > 0 && !committed) "Nyalakan dan berkomitmen" else "Nyalakan penjaga",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }

                    if (needsOverlayPermission) {
                        Notice(
                            "Agar bisa mengunci layar, izinkan Rebahan Guard tampil di atas aplikasi lain. " +
                                "Setelah itu kembali ke sini dan nyalakan lagi.",
                            action = "Buka izin tampil di atas" to { openOverlaySettings(context) },
                        )
                    }

                    if (cameraDenied) {
                        Notice(
                            "Izin kamera ditolak. Tanpa kamera, aplikasi tidak bisa memastikan kamu rebahan.",
                            action = "Buka pengaturan izin" to { openAppSettings(context) },
                        )
                    }

                    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
                    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                        Notice(
                            "Penghemat baterai bisa diam-diam mematikan penjaga. Izinkan Rebahan " +
                                "Guard berjalan tanpa batasan baterai.",
                            action = "Izinkan tanpa batasan baterai" to { requestIgnoreBatteryOptimizations(context) },
                        )
                    }

                    ImportantToday(onOpenPlan = { tab = Tab.PLAN })
                }

                Tab.PLAN -> PlanTab()

                Tab.DREAMS -> DreamsTab(statsRefresh, status.checks)

                Tab.SETTINGS -> TabColumn {
                    Text("Atur", style = MaterialTheme.typography.headlineLarge)
                    Kicker("Teman pemegang kunci")
                    PartnerSection(editable = true, onChanged = { hasPartner = PartnerStore.hasPartner(context) })
                    Kicker("Penjaga")
                    SettingsPanel(settings, editable, committed, ::update)
                    Kicker("Sudut layar sekarang")
                    ScreenAngleDial(
                        elevationDeg = liveAngle,
                        thresholdDeg = settings.lyingElevationDeg,
                        alarming = status.running && status.phase == Phase.ALARMING,
                    )
                    Text(
                        "Gambar kamera dianalisis di HP lalu dibuang. Aplikasi ini tidak punya izin internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Muted,
                    )
                }
            }
        }
        BottomNav(selected = tab, onSelect = { tab = it })
    }

    if (confirmCommitment) {
        val until = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(System.currentTimeMillis() + settings.commitmentHours * Commitment.HOUR_MS))
        AlertDialog(
            onDismissRequest = { confirmCommitment = false },
            containerColor = Tone.Dusk,
            title = { Text("Berkomitmen sampai $until?") },
            text = {
                Text(
                    "Sampai pukul $until, penjaga tidak bisa dimatikan begitu saja. Jalan keluarnya " +
                        "hanya kode dari teman pengawasmu, atau jalur darurat yang lambat (menunggu " +
                        "lalu mengetik sebuah pengakuan panjang). Telepon dan tombol Darurat di layar " +
                        "kunci tetap selalu bisa dipakai.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCommitment = false
                    startGuard(beginCommitment = true)
                }) { Text("Mulai komitmen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCommitment = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** Four quiet text tabs; the selected one gets ink and a short rule above it. */
@Composable
internal fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    Column {
        HorizontalDivider(color = Tone.Hairline)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            for (t in Tab.entries) {
                val active = t == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab, onClick = { onSelect(t) })
                        .padding(top = 0.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(if (active) Tone.Text else androidx.compose.ui.graphics.Color.Transparent),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) Tone.Text else Tone.Muted,
                    )
                }
            }
        }
    }
}

/** Date, live posture, and a greeting set in serif. */
@Composable
internal fun TodayHeader(liveAngle: Float, thresholdDeg: Float) {
    val now = rememberNow()
    val hour = remember(now) { java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 4..10 -> "Selamat pagi."
        in 11..14 -> "Selamat siang."
        in 15..17 -> "Selamat sore."
        else -> "Selamat malam."
    }
    val date = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(Date(now)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(date, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp), color = Tone.Muted, modifier = Modifier.weight(1f))
            val (label, color) = when {
                !liveAngle.isFinite() -> "Membaca sensor" to Tone.Muted
                liveAngle < thresholdDeg -> "Posisi rebahan · ${liveAngle.roundToInt()}°" to Tone.Blanket
                else -> "Duduk · ${liveAngle.roundToInt()}°" to Tone.Mint
            }
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Text(greeting, style = MaterialTheme.typography.displaySmall)
    }
}

/** The most urgent item, if any, with a brick-red rule — the one thing that can't wait. */
@Composable
internal fun UrgentCallout(onOpenPlan: () -> Unit) {
    val context = LocalContext.current
    val items by PlanStore.items(context).collectAsState()
    val now = rememberNow()
    val list = items.orEmpty()
    val urgentCount = list.count { Plan.bucket(it, now) == Bucket.URGENT }
    val importantCount = list.count { Plan.bucket(it, now) == Bucket.IMPORTANT }
    val line = when {
        urgentCount + importantCount == 0 -> "Belum ada rencana. Tulis satu hal yang ingin kamu selesaikan."
        urgentCount == 0 -> "Tidak ada yang mendesak. Waktu yang tepat untuk yang penting."
        else -> "$urgentCount hal mendesak menunggumu."
    }
    Text(
        line,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fraunces, fontStyle = FontStyle.Italic),
        color = Tone.Muted,
    )
    val urgent = Plan.mostUrgent(list, now) ?: return
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .clickable(onClick = onOpenPlan),
    ) {
        AccentRule(Tone.Blanket, Modifier.fillMaxHeight())
        Column(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp)) {
            val due = urgent.dueWallMs ?: now
            val left = due - now
            Kicker(
                if (left >= 0) "Mendesak · ${Plan.formatDuration(left)} lagi" else "Terlambat ${Plan.formatDuration(-left)}",
                Tone.Blanket,
            )
            Text(urgent.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Up to three important (not urgent) items: the ones nothing else will remind you of. */
@Composable
internal fun ImportantToday(onOpenPlan: () -> Unit) {
    val context = LocalContext.current
    val items by PlanStore.items(context).collectAsState()
    val now = rememberNow()
    val important = Plan.grouped(items.orEmpty(), now).getValue(Bucket.IMPORTANT)
    if (important.isEmpty()) return
    Column {
        HorizontalDivider(color = Tone.Hairline)
        Spacer(Modifier.height(12.dp))
        Kicker("Penting, jangan ditunda lagi", Tone.Lamp)
        for (item in important.take(3)) {
            PlanRow(
                item = item,
                now = now,
                accent = Tone.Lamp,
                onToggle = { PlanStore.setDone(context, item.id, !item.isDone) },
                onClick = onOpenPlan,
            )
        }
        if (important.size > 3) {
            TextButton(onClick = onOpenPlan) { Text("Lihat semua (${important.size})", color = Tone.Muted) }
        }
    }
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.Blanket.copy(alpha = 0.22f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = action.second) { Text(action.first) }
    }
}

// ------------------------------------------------------------------------------ commitment

@Composable
private fun ProtectionBanner(committed: Boolean, commitmentLeftMs: Long, partnerName: String?, schedule: Schedule?) {
    val context = LocalContext.current
    val until = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + commitmentLeftMs))
    val interruptions = remember(commitmentLeftMs) { CommitmentStore.interruptions(context) }
    val emergencies = remember(commitmentLeftMs) { CommitmentStore.emergencies(context) }
    val reasons = buildList {
        if (committed) add("berkomitmen sampai pukul $until")
        if (partnerName != null) add("kuncinya dipegang $partnerName")
        if (schedule != null) add("jadwal jaga ${Schedule.format(schedule.startMinute)}–${Schedule.format(schedule.endMinute)}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Terkunci: ${reasons.joinToString(", ")}.",
            style = MaterialTheme.typography.titleMedium,
            color = Tone.Lamp,
        )
        val notes = buildList {
            if (interruptions > 0) add("penjaga terputus atau jam diubah $interruptions kali")
            if (emergencies > 0) add("tombol Darurat dipakai $emergencies kali")
        }
        if (notes.isNotEmpty()) {
            Text(
                "Tercatat: ${notes.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Muted,
            )
        }
    }
}

/** Switching off a protected guard: the partner's code, or the slow emergency path. */
@Composable
private fun StopFlow(hasPartner: Boolean, onStopped: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var emergency by remember { mutableStateOf(!hasPartner) }
    if (emergency) {
        EmergencyStopPanel(hasPartner = hasPartner, onStopped = onStopped, onCancel = onCancel)
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val name = PartnerStore.name(context) ?: "temanmu"
        Text("Minta izin ke $name", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ceritakan kenapa kamu mau mematikan penjaga, lalu minta dia mengetik kodenya di sini " +
                "atau membacakan kode Authenticator-nya.",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
        PartnerCodeField(actionLabel = "Matikan penjaga", onVerified = onStopped)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { emergency = true }) { Text("$name tidak bisa dihubungi") }
            TextButton(onClick = onCancel) { Text("Tetap menyala") }
        }
    }
}

/**
 * The deliberately slow way out: the countdown only runs while this screen stays open, and
 * restarts if the user leaves the app.
 */
@Suppress("DEPRECATION")
@Composable
private fun EmergencyStopPanel(hasPartner: Boolean, onStopped: () -> Unit, onCancel: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var typed by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                startedAt = SystemClock.elapsedRealtime() // left the app: start over
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    val waitMs = EmergencyStopRules.waitMs(hasPartner)
    val waited = (now - startedAt).coerceAtLeast(0)
    val leftSec = ((waitMs - waited).coerceAtLeast(0) + 999) / 1000
    val ready = EmergencyStop.canStop(waited, typed, hasPartner)
    val correct = EmergencyStop.correctWords(typed)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Jalur darurat", style = MaterialTheme.typography.titleMedium)
        Text(
            if (leftSec > 0) {
                "Tunggu ${leftSec / 60}:${"%02d".format(leftSec % 60)} lagi dengan layar ini tetap terbuka. " +
                    "Kalau kamu keluar dari aplikasi, hitungannya mulai dari awal. Selama menunggu, " +
                    "pikirkan lagi: apa yang seharusnya kamu kerjakan sekarang?"
            } else {
                "Waktu tunggu selesai."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.Muted,
        )
        Text("Ketik pengakuan ini, kata demi kata:", style = MaterialTheme.typography.bodyMedium)
        Text("\u201C${EmergencyStop.PHRASE}\u201D", style = MaterialTheme.typography.bodyLarge, color = Tone.Lamp)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.take(400) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            supportingText = { Text("$correct dari ${EmergencyStop.wordCount} kata benar") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStopped, enabled = ready, shape = CircleShape) { Text("Matikan penjaga") }
            TextButton(onClick = onCancel) { Text("Tetap menyala dan bekerja") }
        }
    }
}

// ------------------------------------------------------------------------------ status

@Composable
internal fun StatusLines(status: GuardStatus) {
    val (headline, detail) = when {
        !status.running -> "Penjaga mati." to "Nyalakan saat ada yang harus dikerjakan."
        !status.screenOn -> "Layar mati, penjaga ikut istirahat." to "Begitu layar menyala, pengawasan lanjut."
        status.outsideSchedule -> "Di luar jadwal jaga." to "Penjaga menyala tapi istirahat sampai jadwalnya mulai."
        status.phase == Phase.ALARMING && status.warningUntilElapsedMs > 0 ->
            "Duduk sekarang." to "Kalau tetap rebahan, layar dikunci sebentar lagi."
        status.phase == Phase.WATCHING -> "Mengawasi." to "HP-mu ${poseLabel(status.pose)}."
        status.phase == Phase.CHECKING -> "Kamera sedang memastikan…" to "Hanya beberapa detik, gambar tidak disimpan."
        status.phase == Phase.ALARMING -> "Ketahuan rebahan." to "Layar terkunci sampai kamu duduk."
        else -> "Aman." to "Cek berikutnya sebentar lagi."
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            headline,
            style = MaterialTheme.typography.headlineMedium,
            color = if (status.running && status.phase == Phase.ALARMING) Tone.Blanket else Tone.Text,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tone.Muted)
        status.lastCheck?.let { check ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(check.atMillis))
            val seen = if (check.faceWidthRatio == null) {
                "tidak ada wajah"
            } else {
                val tilt = check.headTiltDeg?.let { ", kepala miring ${it.roundToInt()}°" } ?: ""
                "wajah ${(check.faceWidthRatio * 100).roundToInt()}% lebar gambar$tilt"
            }
            Text(
                "Cek terakhir $time: $seen, ${if (check.lying) "rebahan" else "aman"}.",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Muted,
            )
        }
    }
}

// ------------------------------------------------------------------------------ settings panel

/** One quiet panel; rows separated by hairlines instead of a stack of cards. */
@Composable
internal fun SettingsPanel(
    settings: GuardSettings,
    editable: Boolean,
    committed: Boolean,
    onChange: (GuardSettings) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(24.dp)),
    ) {
        if (!editable) {
            Text(
                if (committed) "Pengaturan terkunci selama komitmen." else "Matikan penjaga dulu untuk mengubah pengaturan.",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Lamp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
        }
        ScheduleRow(settings, editable, onChange)
        Divider()
        CommitmentRow(settings, editable, onChange)
        Divider()
        LockRow(settings, editable, onChange)
        Divider()
        DelayRow(settings, editable, onChange)
        Divider()
        ThresholdRow(settings, editable, onChange)
        Divider()
        StrictRow(settings, editable, onChange)
        Divider()
        SoundRow(settings, editable, onChange)
    }
}

@Composable
private fun ScheduleRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    val sch = settings.schedule
    var start by remember(sch.startMinute) { mutableStateOf(sch.startMinute / 30f) }
    var end by remember(sch.endMinute) { mutableStateOf(sch.endMinute / 30f) }
    val sliderColors = SliderDefaults.colors(
        thumbColor = Tone.Lamp,
        activeTrackColor = Tone.Lamp,
        inactiveTrackColor = Tone.Hairline,
    )
    SettingRow(
        "Jadwal jaga",
        trailing = {
            Switch(
                checked = sch.enabled,
                onCheckedChange = { onChange(settings.copy(schedule = sch.copy(enabled = it))) },
                enabled = editable,
                colors = SwitchDefaults.colors(checkedThumbColor = Tone.Ink, checkedTrackColor = Tone.Lamp),
            )
        },
    ) {
        Text(
            "Penjaga hanya bekerja di jam ini, dan selama jam ini tidak bisa dimatikan begitu " +
                "saja. Nyalakan sekali, lalu biarkan menyala.",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
        if (sch.enabled) {
            Text(
                "${Schedule.format(start.roundToInt() * 30)} sampai ${Schedule.format(end.roundToInt() * 30)}",
                style = MaterialTheme.typography.titleSmall,
                color = Tone.Lamp,
            )
            Text("Mulai", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            Slider(
                value = start,
                onValueChange = { start = it },
                onValueChangeFinished = { onChange(settings.copy(schedule = sch.copy(startMinute = start.roundToInt() * 30))) },
                valueRange = 0f..47f,
                steps = 46,
                enabled = editable,
                colors = sliderColors,
            )
            Text("Selesai", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            Slider(
                value = end,
                onValueChange = { end = it },
                onValueChangeFinished = { onChange(settings.copy(schedule = sch.copy(endMinute = end.roundToInt() * 30))) },
                valueRange = 0f..47f,
                steps = 46,
                enabled = editable,
                colors = sliderColors,
            )
        }
    }
}

@Composable
private fun CommitmentRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    var value by remember(settings.commitmentHours) { mutableStateOf(settings.commitmentHours.toFloat()) }
    val hours = value.roundToInt()
    SettingRow("Komitmen", if (hours == 0) "tidak ada" else "$hours jam") {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(settings.copy(commitmentHours = value.roundToInt())) },
            valueRange = 0f..Commitment.MAX_HOURS.toFloat(),
            steps = Commitment.MAX_HOURS - 1,
            enabled = editable,
            colors = SliderDefaults.colors(
                thumbColor = Tone.Lamp,
                activeTrackColor = Tone.Lamp,
                inactiveTrackColor = Tone.Hairline,
            ),
        )
        Text(
            "Selama komitmen, penjaga tidak bisa dimatikan begitu saja: harus menunggu 2 menit " +
                "dan mengetik sebuah kalimat. Tujuannya mengalahkan rasa malas sesaat.",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
    }
}

@Composable
private fun LockRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    SettingRow(
        "Kunci layar saat rebahan",
        trailing = {
            Switch(
                checked = settings.lockScreen,
                onCheckedChange = { onChange(settings.copy(lockScreen = it)) },
                enabled = editable,
                colors = SwitchDefaults.colors(checkedThumbColor = Tone.Ink, checkedTrackColor = Tone.Lamp),
            )
        },
    ) {
        Text(
            "Layar tertutup sampai kamu duduk. Telepon masuk tidak pernah diblokir, dan tombol " +
                "Darurat selalu ada. Mencoba mengecilkan volume alarm menambah hukuman: 5, lalu " +
                "10, lalu 15 menit kunci (dan mengetik kalimat komitmen), dan masuk laporan.",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Tone.Hairline, modifier = Modifier.padding(horizontal = 20.dp))

@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.Text, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = MaterialTheme.typography.titleSmall, color = Tone.Lamp)
            if (trailing != null) trailing()
        }
        content?.invoke(this)
    }
}

@Composable
private fun DelayRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    var value by remember(settings.delaySec) { mutableStateOf(settings.delaySec.toFloat()) }
    SettingRow("Jeda sebelum kamera mengecek", "${value.roundToInt()} dtk") {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(settings.copy(delaySec = value.roundToInt())) },
            valueRange = SensorInput.MIN_DELAY_SEC.toFloat()..SensorInput.MAX_DELAY_SEC.toFloat(),
            enabled = editable,
            colors = SliderDefaults.colors(
                thumbColor = Tone.Lamp,
                activeTrackColor = Tone.Lamp,
                inactiveTrackColor = Tone.Hairline,
            ),
        )
    }
}

@Composable
private fun StrictRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    SettingRow(
        "Mode ketat",
        trailing = {
            Switch(
                checked = settings.strictMode,
                onCheckedChange = { onChange(settings.copy(strictMode = it)) },
                enabled = editable,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Tone.Ink,
                    checkedTrackColor = Tone.Lamp,
                ),
            )
        },
    ) {
        Text(
            "Kamera juga mengecek saat HP tegak, supaya rebahan miring dengan HP tegak ikut " +
                "tertangkap. Kamera jadi lebih sering menyala.",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
    }
}

// ------------------------------------------------------------------------------ calibration

private sealed interface CalibrationState {
    data object Idle : CalibrationState
    data class Countdown(val step: Int, val secondsLeft: Int) : CalibrationState
    data object WaitingToLieDown : CalibrationState
    data class Recording(val step: Int) : CalibrationState
    data class Done(val outcome: Calibrator.Outcome) : CalibrationState
}

private const val CALIBRATION_COUNTDOWN_SEC = 5
private const val CALIBRATION_RECORD_MS = 5_000L

@Composable
private fun ThresholdRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CalibrationState>(CalibrationState.Idle) }
    var sittingSamples by remember { mutableStateOf<List<Float>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun recordStep(step: Int) {
        job?.cancel()
        job = scope.launch {
            for (s in CALIBRATION_COUNTDOWN_SEC downTo 1) {
                state = CalibrationState.Countdown(step, s)
                delay(1_000)
            }
            state = CalibrationState.Recording(step)
            val samples = recordScreenElevations(context, CALIBRATION_RECORD_MS)
            if (step == 1) {
                sittingSamples = samples
                state = CalibrationState.WaitingToLieDown
            } else {
                state = CalibrationState.Done(Calibrator.calibrate(sittingSamples, samples))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        state = CalibrationState.Idle
    }

    SettingRow(
        "Batas rebahan",
        value = "${settings.lyingElevationDeg.roundToInt()}°",
    ) {
        Text(
            "Garis putus-putus pada jarum. Layar yang menghadap lebih ke bawah dari garis itu " +
                "dianggap kamu sedang menatap HP dari posisi rebahan.",
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )
        when (val s = state) {
            CalibrationState.Idle -> TextButton(onClick = { recordStep(1) }, enabled = editable) {
                Text("Sesuaikan dengan caraku memegang HP")
            }

            is CalibrationState.Countdown -> {
                val pose = if (s.step == 1) "Duduk dan pegang HP seperti biasa." else "Rebahan seperti biasa."
                Text("$pose Merekam dalam ${s.secondsLeft}…", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = ::cancel) { Text("Batal") }
            }

            CalibrationState.WaitingToLieDown -> {
                Text(
                    "Posisi duduk tersimpan. Sekarang rebahan seperti saat kamu biasa main HP, " +
                        "lalu ketuk tombol di bawah.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { recordStep(2) }, shape = CircleShape) { Text("Rekam posisi rebahan") }
                    TextButton(onClick = ::cancel) { Text("Batal") }
                }
            }

            is CalibrationState.Recording -> Text(
                "Merekam… tahan posisi ${CALIBRATION_RECORD_MS / 1000} detik.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Lamp,
            )

            is CalibrationState.Done -> when (val outcome = s.outcome) {
                is Calibrator.Outcome.Ok -> {
                    val r = outcome.result
                    Text(
                        "Duduk sekitar ${r.sittingMedianDeg.roundToInt()}°, rebahan sekitar " +
                            "${r.lyingMedianDeg.roundToInt()}°. Batas baru ${r.lyingElevationDeg.roundToInt()}°." +
                            if (r.separable) "" else " Dua posisimu mirip; mode ketat akan membantu.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onChange(settings.copy(lyingElevationDeg = r.lyingElevationDeg))
                            state = CalibrationState.Idle
                        }, shape = CircleShape) { Text("Simpan batas") }
                        TextButton(onClick = ::cancel) { Text("Batal") }
                    }
                }
                Calibrator.Outcome.NotEnoughData -> {
                    Text("Data sensor kurang. Pastikan layar tidak terkunci, lalu ulangi.")
                    TextButton(onClick = ::cancel) { Text("Ulangi") }
                }
                Calibrator.Outcome.NotSeparable -> {
                    Text(
                        "Saat rebahan layarmu justru menghadap ke atas seperti saat duduk, jadi " +
                            "sudut layar saja tidak cukup. Nyalakan mode ketat.",
                    )
                    TextButton(onClick = ::cancel) { Text("Mengerti") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ alarm sound

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundRow(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val title = remember(settings.alarmSoundUri) { soundTitle(context, settings.alarmSoundUri) }

    fun choose(uri: Uri?) {
        val old = settings.alarmSoundUri
        val new = AlarmSoundPolicy.sanitize(uri?.toString())
        if (old != null && old != new) releasePersistedPermission(context, old)
        onChange(settings.copy(alarmSoundUri = new))
    }

    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            choose(picked)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access across reboots, otherwise the alarm would silently fall back.
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Provider doesn't support persistable grants: still usable this session.
            }
            choose(uri)
        }
    }

    SettingRow("Nada alarm") {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Tone.Lamp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = editable,
                onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pilih nada alarm")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, AlarmPlayer.defaultAlarmUri())
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.alarmSoundUri?.let(Uri::parse))
                    try {
                        systemPicker.launch(intent)
                    } catch (e: Exception) {
                        // Some ROMs ship without a ringtone picker: offer files instead.
                        filePicker.launch(arrayOf("audio/*"))
                    }
                },
            ) { Text("Nada sistem") }
            TextButton(enabled = editable, onClick = { filePicker.launch(arrayOf("audio/*")) }) {
                Text("File audio")
            }
            TextButton(onClick = {
                previewJob?.cancel()
                previewJob = scope.launch { previewAlarmSound(context, settings.alarmSoundUri?.let(Uri::parse)) }
            }) { Text("Dengarkan") }
            if (settings.alarmSoundUri != null) {
                TextButton(enabled = editable, onClick = { choose(null) }) { Text("Pakai bawaan") }
            }
        }
    }
}

// ------------------------------------------------------------------------------ helpers

private fun releasePersistedPermission(context: Context, uri: String) {
    try {
        context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
        // It wasn't a persisted grant (e.g. a system ringtone): nothing to release.
    }
}

private fun poseLabel(pose: Pose) = when (pose) {
    Pose.FACE_DOWN -> "menghadap ke arahmu dari atas, seperti saat rebahan"
    Pose.SIDEWAYS -> "menyamping, seperti saat rebahan miring"
    Pose.UPRIGHT -> "tegak"
    Pose.UPSIDE_DOWN -> "terbalik"
    Pose.FACE_UP -> "tergeletak, layar ke atas"
    Pose.TILTED -> "agak miring"
    Pose.UNKNOWN -> "sedang dibaca sensornya"
    Pose.RESTING -> "tertelungkup diam di meja"
}

@android.annotation.SuppressLint("BatteryLife") // a self-control app is exactly the use case
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            openAppSettings(context)
        }
    }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        openAppSettings(context)
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}
