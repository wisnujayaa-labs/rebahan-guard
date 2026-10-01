package io.github.wisnujayaa.rebahanguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.service.GuardService
import io.github.wisnujayaa.rebahanguard.service.GuardStatus
import io.github.wisnujayaa.rebahanguard.service.GuardStatusStore
import io.github.wisnujayaa.rebahanguard.ui.RebahanGuardTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

private const val PREFS = "settings"
private const val KEY_DELAY = "delay_sec"

@Composable
private fun GuardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by GuardStatusStore.status.collectAsState()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var delaySec by remember {
        mutableFloatStateOf(prefs.getInt(KEY_DELAY, GuardService.DEFAULT_DELAY_SEC).toFloat())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            GuardService.start(context, delaySec.roundToInt())
        }
    }

    fun startGuard() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            GuardService.start(context, delaySec.roundToInt())
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Jeda sebelum kamera mengecek", style = MaterialTheme.typography.titleSmall)
                Text("${delaySec.roundToInt()} detik", style = MaterialTheme.typography.headlineSmall)
                Slider(
                    value = delaySec,
                    onValueChange = { delaySec = it },
                    onValueChangeFinished = {
                        prefs.edit().putInt(KEY_DELAY, delaySec.roundToInt()).apply()
                    },
                    valueRange = 5f..120f,
                    enabled = !status.running,
                )
                if (status.running) {
                    Text(
                        "Matikan dulu untuk mengubah jeda.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

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

        Text(
            "Privasi: gambar kamera dianalisis langsung di HP lalu dibuang. Tidak ada foto yang " +
                "disimpan atau dikirim ke internet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

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
            }
            status.lastCheck?.let { check ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(check.atMillis))
                val detail = if (check.faceWidthRatio == null) {
                    "tidak ada wajah"
                } else {
                    "wajah ${(check.faceWidthRatio * 100).roundToInt()}% lebar gambar, " +
                        "miring ${check.rollDeg?.roundToInt() ?: 0}°"
                }
                val verdict = if (check.lying) "rebahan ✋" else "aman"
                Text(
                    "Cek terakhir $time: $detail → $verdict",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun phaseLabel(phase: Phase) = when (phase) {
    Phase.WATCHING -> "Mengawasi posisi HP"
    Phase.CHECKING -> "Kamera sedang mengecek…"
    Phase.ALARMING -> "Ketahuan rebahan! Duduk dulu 😤"
    Phase.COOLDOWN -> "Aman — istirahat sebentar"
}

private fun poseLabel(pose: Pose) = when (pose) {
    Pose.FACE_DOWN -> "layar menghadap bawah (curiga telentang)"
    Pose.SIDEWAYS -> "menyamping (curiga miring)"
    Pose.UPRIGHT -> "tegak"
    Pose.UPSIDE_DOWN -> "terbalik"
    Pose.FACE_UP -> "datar, layar ke atas"
    Pose.TILTED -> "agak miring"
    Pose.UNKNOWN -> "membaca sensor…"
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
