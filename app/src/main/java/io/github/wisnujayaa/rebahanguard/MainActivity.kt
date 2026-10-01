package io.github.wisnujayaa.rebahanguard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
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
import io.github.wisnujayaa.rebahanguard.ui.Night
import io.github.wisnujayaa.rebahanguard.ui.RebahanGuardTheme
import io.github.wisnujayaa.rebahanguard.ui.ScreenAngleDial
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
        // Light status-bar icons on our always-dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            RebahanGuardTheme {
                // Surface sets LocalContentColor: every Text without an explicit color inherits
                // the light night-text color instead of Compose's default black.
                Surface(modifier = Modifier.fillMaxSize(), color = Night.Ink, contentColor = Night.Text) {
                    GuardScreen()
                }
            }
        }
    }
}

@Composable
private fun GuardScreen() {
    val context = LocalContext.current
    val status by GuardStatusStore.status.collectAsState()
    val liveAngle by rememberLiveScreenElevation()
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
            // After "Don't ask again" Android returns instantly with no dialog: say why
            // nothing happened instead of failing silently.
            cameraDenied = true
        }
    }

    fun startGuard() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) GuardService.start(context, settings) else permissionLauncher.launch(missing.toTypedArray())
    }

    val editable = !status.running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Rebahan Guard", style = MaterialTheme.typography.titleMedium, color = Night.Muted)

        ScreenAngleDial(
            elevationDeg = liveAngle,
            thresholdDeg = settings.lyingElevationDeg,
            alarming = status.running && status.phase == Phase.ALARMING,
        )

        StatusLines(status)

        if (status.running) {
            OutlinedButton(
                onClick = { GuardService.stop(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Night.Text),
            ) { Text("Matikan penjaga", style = MaterialTheme.typography.titleMedium) }
        } else {
            Button(
                onClick = { startGuard() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = CircleShape,
            ) { Text("Nyalakan penjaga", style = MaterialTheme.typography.titleMedium) }
        }

        if (cameraDenied) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Night.Blanket.copy(alpha = 0.22f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Izin kamera ditolak. Tanpa kamera, aplikasi tidak bisa memastikan kamu rebahan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { openAppSettings(context) }) { Text("Buka pengaturan izin") }
            }
        }

        SettingsPanel(settings, editable, ::update)

        Text(
            "Gambar kamera dianalisis di HP lalu dibuang. Aplikasi ini tidak punya izin internet.",
            style = MaterialTheme.typography.bodySmall,
            color = Night.Muted,
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------------------ status

@Composable
private fun StatusLines(status: GuardStatus) {
    val (headline, detail) = when {
        !status.running -> "Penjaga mati." to "Nyalakan sebelum tidur. Jarum di atas mengikuti HP-mu."
        !status.screenOn -> "Layar mati, penjaga ikut istirahat." to "Begitu layar menyala, pengawasan lanjut."
        status.phase == Phase.WATCHING -> "Mengawasi." to "HP-mu ${poseLabel(status.pose)}."
        status.phase == Phase.CHECKING -> "Kamera sedang memastikan…" to "Hanya beberapa detik, gambar tidak disimpan."
        status.phase == Phase.ALARMING -> "Ketahuan rebahan." to "Duduk dulu, alarmnya berhenti sendiri."
        else -> "Aman." to "Cek berikutnya sebentar lagi."
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            headline,
            style = MaterialTheme.typography.headlineMedium,
            color = if (status.running && status.phase == Phase.ALARMING) Night.Blanket else Night.Text,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Night.Muted)
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
                color = Night.Muted,
            )
        }
    }
}

// ------------------------------------------------------------------------------ settings panel

/** One quiet panel; rows separated by hairlines instead of a stack of cards. */
@Composable
private fun SettingsPanel(settings: GuardSettings, editable: Boolean, onChange: (GuardSettings) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Night.Dusk.copy(alpha = 0.7f)),
    ) {
        if (!editable) {
            Text(
                "Matikan penjaga dulu untuk mengubah pengaturan.",
                style = MaterialTheme.typography.bodySmall,
                color = Night.Lamp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
        }
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
private fun Divider() = HorizontalDivider(color = Night.Hairline, modifier = Modifier.padding(horizontal = 20.dp))

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
            Text(title, style = MaterialTheme.typography.titleSmall, color = Night.Text, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = MaterialTheme.typography.titleSmall, color = Night.Lamp)
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
                thumbColor = Night.Lamp,
                activeTrackColor = Night.Lamp,
                inactiveTrackColor = Night.Hairline,
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
                    checkedThumbColor = Night.Ink,
                    checkedTrackColor = Night.Lamp,
                ),
            )
        },
    ) {
        Text(
            "Kamera juga mengecek saat HP tegak, supaya rebahan miring dengan HP tegak ikut " +
                "tertangkap. Kamera jadi lebih sering menyala.",
            style = MaterialTheme.typography.bodySmall,
            color = Night.Muted,
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
            color = Night.Muted,
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
                color = Night.Lamp,
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
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Night.Lamp)
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
