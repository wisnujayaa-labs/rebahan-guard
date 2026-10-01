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
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.wisnujayaa.rebahanguard.core.AlarmSoundPolicy
import io.github.wisnujayaa.rebahanguard.core.Calibrator
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.core.SensorInput
import io.github.wisnujayaa.rebahanguard.service.AlarmPlayer
import io.github.wisnujayaa.rebahanguard.service.GuardService
import io.github.wisnujayaa.rebahanguard.service.GuardSettings
import io.github.wisnujayaa.rebahanguard.service.GuardStatus
import io.github.wisnujayaa.rebahanguard.service.GuardStatusStore
import io.github.wisnujayaa.rebahanguard.ui.RebahanGuardTheme
import io.github.wisnujayaa.rebahanguard.ui.previewAlarmSound
import io.github.wisnujayaa.rebahanguard.ui.recordScreenElevations
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RebahanGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    GuardScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun GuardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by GuardStatusStore.status.collectAsState()
    var settings by remember { mutableStateOf(GuardSettings.load(context)) }
    var cameraDenied by remember { mutableStateOf(false) }

    fun update(newSettings: GuardSettings) {
        settings = newSettings.sanitized()
        settings.save(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            cameraDenied = false
            GuardService.start(context, settings)
        } else {
            // After "Don't ask again" Android returns instantly with no dialog: tell the user
            // why nothing happened instead of failing silently.
            cameraDenied = true
        }
    }

    fun startGuard() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            GuardService.start(context, settings)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Rebahan Guard", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Bunyi alarm kalau kamu main HP sambil rebahan. Sensor gravitasi mengawasi terus; " +
                "kamera depan hanya menyala beberapa detik untuk memastikan.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StatusCard(status)

        if (status.running) {
            Text(
                "Matikan penjaga dulu untuk mengubah pengaturan.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DelayCard(settings, enabled = !status.running, onChange = ::update)
        DetectionCard(settings, enabled = !status.running, onChange = ::update)
        AlarmSoundCard(settings, enabled = !status.running, onChange = ::update)

        if (status.running) {
            OutlinedButton(
                onClick = { GuardService.stop(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Matikan penjaga") }
        } else {
            Button(onClick = { startGuard() }, modifier = Modifier.fillMaxWidth()) {
                Text("Aktifkan penjaga")
            }
        }

        if (cameraDenied) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Izin kamera ditolak. Tanpa kamera, aplikasi tidak bisa memastikan kamu " +
                            "sedang rebahan.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { openAppSettings(context) }) {
                        Text("Buka pengaturan izin")
                    }
                }
            }
        }

        Text(
            "Privasi: gambar kamera dianalisis langsung di HP lalu dibuang. Aplikasi ini tidak " +
                "punya izin internet, jadi tidak ada yang bisa dikirim ke mana pun.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ------------------------------------------------------------------------------ status

@Composable
private fun StatusCard(status: GuardStatus) {
    val alarming = status.running && status.phase == Phase.ALARMING
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (alarming) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when {
                    !status.running -> "Penjaga mati"
                    !status.screenOn -> "Layar mati — beristirahat"
                    else -> phaseLabel(status.phase)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (status.running && status.screenOn) {
                Text("Posisi HP: ${poseLabel(status.pose)}", style = MaterialTheme.typography.bodyMedium)
                if (status.screenElevationDeg.isFinite()) {
                    Text(
                        "Sudut layar: ${status.screenElevationDeg.roundToInt()}° " +
                            "(+ = menghadap atas, − = menghadap bawah)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            status.lastCheck?.let { check ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(check.atMillis))
                val detail = if (check.faceWidthRatio == null) {
                    "tidak ada wajah"
                } else {
                    val tilt = check.headTiltDeg?.let { ", kepala miring ${it.roundToInt()}°" } ?: ""
                    "wajah ${(check.faceWidthRatio * 100).roundToInt()}% lebar gambar$tilt"
                }
                val verdict = if (check.lying) "rebahan ✋" else "aman"
                Text("Cek terakhir $time: $detail → $verdict", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------------------ delay

@Composable
private fun DelayCard(settings: GuardSettings, enabled: Boolean, onChange: (GuardSettings) -> Unit) {
    var value by remember(settings.delaySec) { mutableStateOf(settings.delaySec.toFloat()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Jeda sebelum kamera mengecek", style = MaterialTheme.typography.titleSmall)
            Text("${value.roundToInt()} detik", style = MaterialTheme.typography.headlineSmall)
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onChange(settings.copy(delaySec = value.roundToInt())) },
                valueRange = SensorInput.MIN_DELAY_SEC.toFloat()..SensorInput.MAX_DELAY_SEC.toFloat(),
                enabled = enabled,
            )
        }
    }
}

// ------------------------------------------------------------------------------ detection

private sealed interface CalibrationState {
    data object Idle : CalibrationState
    data class Countdown(val step: Int, val secondsLeft: Int) : CalibrationState
    data class Recording(val step: Int) : CalibrationState
    data class Done(val outcome: Calibrator.Outcome) : CalibrationState
}

private const val CALIBRATION_COUNTDOWN_SEC = 5
private const val CALIBRATION_RECORD_MS = 5_000L

@Composable
private fun DetectionCard(settings: GuardSettings, enabled: Boolean, onChange: (GuardSettings) -> Unit) {
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
                state = CalibrationState.Countdown(2, 0) // wait for the user to lie down
            } else {
                state = CalibrationState.Done(Calibrator.calibrate(sittingSamples, samples))
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deteksi", style = MaterialTheme.typography.titleSmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mode ketat", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Kamera juga mengecek saat HP tegak, jadi rebahan miring dengan HP tegak " +
                            "ikut tertangkap. Kamera lebih sering menyala.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.strictMode,
                    onCheckedChange = { onChange(settings.copy(strictMode = it)) },
                    enabled = enabled,
                )
            }

            Text(
                "Batas sudut rebahan: ${settings.lyingElevationDeg.roundToInt()}°. Layar yang " +
                    "menghadap lebih ke bawah dari ini dianggap \"kamu sedang menatap HP dari bawah\".",
                style = MaterialTheme.typography.bodySmall,
            )

            when (val s = state) {
                CalibrationState.Idle -> OutlinedButton(
                    onClick = { recordStep(1) },
                    enabled = enabled,
                ) { Text("Kalibrasi sesuai kebiasaanku") }

                is CalibrationState.Countdown -> if (s.step == 2 && s.secondsLeft == 0) {
                    Text(
                        "Langkah 2: rebahan seperti biasa kamu main HP (boleh tegak/agak tegak), " +
                            "lalu tekan tombol di bawah.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { recordStep(2) }) { Text("Rekam posisi rebahan") }
                } else {
                    val what = if (s.step == 1) "DUDUK dan pegang HP seperti biasa" else "REBAHAN seperti biasa"
                    Text("Langkah ${s.step}: $what. Mulai merekam dalam ${s.secondsLeft}…")
                }

                is CalibrationState.Recording -> Text(
                    "Merekam… tahan posisi selama ${CALIBRATION_RECORD_MS / 1000} detik.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is CalibrationState.Done -> {
                    when (val outcome = s.outcome) {
                        is Calibrator.Outcome.Ok -> {
                            val r = outcome.result
                            Text(
                                "Duduk: ±${r.sittingMedianDeg.roundToInt()}°, rebahan: " +
                                    "±${r.lyingMedianDeg.roundToInt()}°. Batas baru: " +
                                    "${r.lyingElevationDeg.roundToInt()}°." +
                                    if (r.separable) "" else " Kedua posisi agak mirip; " +
                                        "pertimbangkan mode ketat.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    onChange(settings.copy(lyingElevationDeg = r.lyingElevationDeg))
                                    state = CalibrationState.Idle
                                }) { Text("Simpan") }
                                TextButton(onClick = { state = CalibrationState.Idle }) { Text("Batal") }
                            }
                        }
                        Calibrator.Outcome.NotEnoughData -> {
                            Text("Data sensor kurang. Pastikan layar tidak terkunci, lalu ulangi.")
                            TextButton(onClick = { state = CalibrationState.Idle }) { Text("Ulangi") }
                        }
                        Calibrator.Outcome.NotSeparable -> {
                            Text(
                                "Saat rebahan, layarmu justru menghadap ke atas seperti saat duduk, " +
                                    "jadi sudut layar saja tidak bisa membedakannya. Aktifkan mode ketat.",
                            )
                            TextButton(onClick = { state = CalibrationState.Idle }) { Text("Oke") }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ alarm sound

@Composable
private fun AlarmSoundCard(settings: GuardSettings, enabled: Boolean, onChange: (GuardSettings) -> Unit) {
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
            choose(picked) // null = user picked "Default"
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access across reboots, otherwise the alarm would silently fall back.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Provider doesn't support persistable grants: still usable this session.
            }
            choose(uri)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nada alarm", style = MaterialTheme.typography.titleSmall)
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = enabled,
                    onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pilih nada alarm")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, AlarmPlayer.defaultAlarmUri())
                            .putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                settings.alarmSoundUri?.let(Uri::parse),
                            )
                        try {
                            systemPicker.launch(intent)
                        } catch (e: Exception) {
                            // Some OEM ROMs ship without a ringtone picker: offer files instead.
                            filePicker.launch(arrayOf("audio/*"))
                        }
                    },
                ) { Text("Nada sistem") }
                OutlinedButton(
                    enabled = enabled,
                    onClick = { filePicker.launch(arrayOf("audio/*")) },
                ) { Text("File audio") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    previewJob?.cancel()
                    previewJob = scope.launch {
                        previewAlarmSound(context, settings.alarmSoundUri?.let(Uri::parse))
                    }
                }) { Text("▶ Tes 3 detik") }
                if (settings.alarmSoundUri != null) {
                    TextButton(enabled = enabled, onClick = { choose(null) }) { Text("Pakai bawaan") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ helpers

private fun releasePersistedPermission(context: Context, uri: String) {
    try {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (e: Exception) {
        // It wasn't a persisted grant (e.g. a system ringtone): nothing to release.
    }
}

private fun phaseLabel(phase: Phase) = when (phase) {
    Phase.WATCHING -> "Mengawasi posisi HP"
    Phase.CHECKING -> "Kamera sedang mengecek…"
    Phase.ALARMING -> "Ketahuan rebahan! Duduk dulu 😤"
    Phase.COOLDOWN -> "Aman — istirahat sebentar"
}

private fun poseLabel(pose: Pose) = when (pose) {
    Pose.FACE_DOWN -> "layar menghadap ke arahmu dari atas (curiga rebahan)"
    Pose.SIDEWAYS -> "menyamping (curiga miring)"
    Pose.UPRIGHT -> "tegak"
    Pose.UPSIDE_DOWN -> "terbalik"
    Pose.FACE_UP -> "datar, layar ke atas"
    Pose.TILTED -> "agak miring"
    Pose.UNKNOWN -> "membaca sensor…"
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
