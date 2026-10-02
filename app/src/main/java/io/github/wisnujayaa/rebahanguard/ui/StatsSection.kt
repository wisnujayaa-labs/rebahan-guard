package io.github.wisnujayaa.rebahanguard.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wisnujayaa.rebahanguard.core.LockReason
import io.github.wisnujayaa.rebahanguard.core.NightStats
import io.github.wisnujayaa.rebahanguard.service.LastCheck
import io.github.wisnujayaa.rebahanguard.service.PartnerStore
import io.github.wisnujayaa.rebahanguard.service.StatsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Streak, this week's numbers, and a report the user can send to their partner. */
@Composable
fun StatsSection(refreshKey: Any?) {
    val context = LocalContext.current
    val records = remember(refreshKey) { StatsStore.load(context) }
    val tonight = StatsStore.tonight()
    val streak = NightStats.streak(records, tonight)
    val week = records.filter { it.night in (tonight - 6)..tonight && it.guarded }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Night.Dusk.copy(alpha = 0.7f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$streak", style = MaterialTheme.typography.displaySmall, color = Night.Lamp)
            Text(
                if (streak == 1) "malam bersih berturut-turut" else "malam bersih berturut-turut",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(
            "7 malam terakhir: ${week.size} malam dijaga, ketahuan ${week.sumOf { it.caught }} kali, " +
                "terkunci ${week.sumOf { it.lockedMs } / 60_000} menit.",
            style = MaterialTheme.typography.bodySmall,
            color = Night.Muted,
        )
        TextButton(onClick = {
            val text = NightStats.summary(records, tonight, PartnerStore.name(context))
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Kirim laporan ke teman"))
        }) { Text("Kirim laporan ke teman") }
    }
}

/** The last camera checks and why each ended the way it did — data instead of guesses. */
@Composable
fun CheckHistory(checks: List<LastCheck>) {
    if (checks.isEmpty()) return
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Riwayat cek kamera", style = MaterialTheme.typography.titleSmall)
        for (c in checks.take(6)) {
            val report = c.report
            val parts = buildList {
                add(report?.reason?.label ?: "selesai")
                if (report != null && report.meanLuma.isFinite()) add("terang ${report.meanLuma.roundToInt()}/255")
                if (report?.usedRingLight == true) add("lampu layar")
                c.faceWidthRatio?.let { add("wajah ${(it * 100).roundToInt()}%") }
            }
            val verdict = when {
                !c.lying -> "aman"
                c.lockReason == LockReason.STRONG_POSE -> "dikunci (posisi jelas rebahan)"
                c.lockReason == LockReason.PERSISTENT_SUSPICION -> "dikunci (curiga terus-menerus)"
                else -> "dikunci"
            }
            Text(
                "${format.format(Date(c.atMillis))}  ${parts.joinToString(", ")} → $verdict",
                style = MaterialTheme.typography.bodySmall,
                color = Night.Muted,
            )
        }
    }
}
