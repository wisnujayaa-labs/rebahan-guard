package io.github.wisnujayaa.rebahanguard.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.wisnujayaa.rebahanguard.core.CommitmentTemplates
import io.github.wisnujayaa.rebahanguard.core.Dream
import io.github.wisnujayaa.rebahanguard.core.DreamRules
import io.github.wisnujayaa.rebahanguard.core.Habit
import io.github.wisnujayaa.rebahanguard.core.HabitUnit
import io.github.wisnujayaa.rebahanguard.core.Proof
import io.github.wisnujayaa.rebahanguard.core.Schedule

@Composable
internal fun EditorCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * A dream in three steps: what, why (own words or a template), and what is lost by waiting.
 * The finished sentence is what the lock screen will quote back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DreamEditor(initial: Dream?, onSave: (Dream) -> Unit, onArchive: (() -> Unit)?, onCancel: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var target by remember { mutableStateOf(initial?.target.orEmpty()) }
    var why by remember { mutableStateOf(initial?.why.orEmpty()) }
    var step by remember { mutableIntStateOf(if (initial == null) 1 else 3) }
    var theme by remember { mutableStateOf(CommitmentTemplates.Theme.SELF) }
    var reason by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    EditorCard {
        Kicker(if (initial == null) "Impian baru · langkah $step dari 3" else "Ubah impian")
        when (step) {
            1 -> {
                Text("Apa yang ingin kamu capai?", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(title, { title = it.take(DreamRules.MAX_TITLE); error = null }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Kuliah S2 di luar negeri") }, singleLine = true)
                OutlinedTextField(target, { target = it.take(DreamRules.MAX_TITLE) }, Modifier.fillMaxWidth(),
                    label = { Text("Target yang terukur (opsional)") }, placeholder = { Text("IELTS 7.0 sebelum Mei 2027") }, singleLine = true)
            }
            2 -> {
                Text("Kenapa impian ini penting bagimu?", style = MaterialTheme.typography.titleLarge)
                Text("Belum punya kata-kata? Pilih yang paling mengena, lalu ubah sesukamu.", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in CommitmentTemplates.Theme.entries) Chip(t.label, t == theme) { theme = t }
                }
                for (s in CommitmentTemplates.WHY.getValue(theme)) {
                    Suggestion(s, selected = reason == s) { reason = s }
                }
                OutlinedTextField(reason, { reason = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Dengan kata-katamu") }, minLines = 2)
            }
            3 -> if (initial == null) {
                Text("Apa yang hilang kalau terus ditunda?", style = MaterialTheme.typography.titleLarge)
                for (s in CommitmentTemplates.COST) Suggestion(s, selected = cost == s) { cost = s }
                OutlinedTextField(cost, { cost = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Atau tulis sendiri") }, minLines = 2)
                val composed = CommitmentTemplates.compose(title, reason, cost)
                Kicker("Kalimat komitmenmu", Tone.Lamp)
                Text("“$composed”", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fraunces, fontStyle = FontStyle.Italic))
            } else {
                OutlinedTextField(title, { title = it.take(DreamRules.MAX_TITLE) }, Modifier.fillMaxWidth(), label = { Text("Impian") }, singleLine = true)
                OutlinedTextField(target, { target = it.take(DreamRules.MAX_TITLE) }, Modifier.fillMaxWidth(), label = { Text("Target") }, singleLine = true)
                OutlinedTextField(why, { why = it.take(DreamRules.MAX_WHY) }, Modifier.fillMaxWidth(), label = { Text("Kalimat komitmen") }, minLines = 3)
                Text("Perubahan yang meringankan baru berlaku besok.", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            }
        }
        error?.let { Text(it, color = Tone.Blanket, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (initial == null && step < 3) {
                Button(onClick = {
                    if (step == 1 && DreamRules.sanitizeText(title, DreamRules.MAX_TITLE) == null) error = "Tulis dulu impianmu."
                    else step++
                }, shape = CircleShape) { Text("Lanjut") }
                if (step > 1) TextButton(onClick = { step-- }) { Text("Kembali", color = Tone.Muted) }
            } else {
                Button(onClick = {
                    val t = DreamRules.sanitizeText(title, DreamRules.MAX_TITLE)
                    if (t == null) {
                        error = "Tulis dulu impianmu."
                    } else {
                        val now = System.currentTimeMillis()
                        val base = initial ?: Dream(id = now, title = t, why = "", createdAtMs = now)
                        onSave(base.copy(
                            title = t,
                            target = DreamRules.sanitizeText(target, DreamRules.MAX_TITLE).orEmpty(),
                            why = DreamRules.sanitizeText(
                                if (initial == null) CommitmentTemplates.compose(title, reason, cost) else why,
                                DreamRules.MAX_WHY,
                            ).orEmpty(),
                        ))
                    }
                }, shape = CircleShape) { Text("Simpan") }
            }
            TextButton(onClick = onCancel) { Text("Batal", color = Tone.Muted) }
            Spacer(Modifier.weight(1f))
            if (onArchive != null) TextButton(onClick = onArchive) { Text("Arsipkan", color = Tone.Blanket) }
        }
    }
}

@Composable
private fun Suggestion(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Tone.Text else Tone.Hairline, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (selected) Tone.Text else Tone.Muted)
    }
}

private val DAY_LABELS = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

/** A habit: what, how much per day, which days, an optional time window, and its proof. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitEditor(dreamId: Long, initial: Habit?, onSave: (Habit) -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var unit by remember { mutableStateOf(initial?.unit ?: HabitUnit.MINUTES) }
    var amount by remember { mutableStateOf((initial?.dailyTarget ?: 30).toString()) }
    var days by remember { mutableIntStateOf(initial?.days ?: Habit.ALL_DAYS) }
    var useWindow by remember { mutableStateOf(initial?.hasWindow ?: false) }
    var start by remember { mutableIntStateOf(initial?.windowStart ?: (19 * 60 + 30)) }
    var end by remember { mutableIntStateOf(initial?.windowEnd ?: (21 * 60)) }
    var proof by remember { mutableStateOf(initial?.proof ?: Proof.DESK) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pickTime(current: Int, set: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> set(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    EditorCard {
        Kicker(if (initial == null) "Kebiasaan baru" else "Ubah kebiasaan")
        OutlinedTextField(title, { title = it.take(DreamRules.MAX_TITLE); error = null }, Modifier.fillMaxWidth(),
            placeholder = { Text("Latihan listening") }, singleLine = true)
        Kicker("Setiap hari")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(amount, { v -> amount = v.filter { it.isDigit() }.take(6) }, Modifier.width(110.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (u in HabitUnit.entries) Chip(u.label, u == unit) { unit = u }
            }
        }
        Kicker("Hari")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_LABELS.forEachIndexed { i, label ->
                val on = days and (1 shl i) != 0
                Chip(label, on) { days = (days xor (1 shl i)).let { if (it == 0) days else it } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Jam target", style = MaterialTheme.typography.titleSmall)
                Text("Selama jam ini aplikasi pengalih dikunci dan kamu diingatkan memulai sesi.", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            }
            Switch(useWindow, { useWindow = it }, colors = SwitchDefaults.colors(checkedThumbColor = Tone.Ink, checkedTrackColor = Tone.Lamp))
        }
        if (useWindow) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Mulai ${Schedule.format(start)}", true) { pickTime(start) { start = it } }
                Chip("Selesai ${Schedule.format(end)}", true) { pickTime(end) { end = it } }
            }
        }
        Kicker("Bagaimana membuktikannya?")
        for (p in Proof.entries) ProofOption(p, p == proof) { proof = p }
        error?.let { Text(it, color = Tone.Blanket, style = MaterialTheme.typography.bodySmall) }
        Text("Perubahan yang meringankan (target turun, hari dikurangi, bukti dilemahkan) baru berlaku besok.",
            style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val t = DreamRules.sanitizeText(title, DreamRules.MAX_TITLE)
                val n = amount.toIntOrNull()?.coerceIn(1, DreamRules.MAX_DAILY_TARGET)
                when {
                    t == null -> error = "Tulis dulu kebiasaannya."
                    n == null -> error = "Isi target harian."
                    else -> {
                        val base = initial ?: Habit(id = System.currentTimeMillis(), dreamId = dreamId, title = t)
                        onSave(base.copy(
                            title = t, unit = unit, dailyTarget = n, days = days, proof = proof,
                            windowStart = if (useWindow) start else null, windowEnd = if (useWindow) end else null,
                        ))
                    }
                }
            }, shape = CircleShape) { Text("Simpan") }
            TextButton(onClick = onCancel) { Text("Batal", color = Tone.Muted) }
            Spacer(Modifier.weight(1f))
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Hapus", color = Tone.Blanket) }
        }
    }
}

@Composable
private fun ProofOption(p: Proof, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Tone.Text else Tone.Hairline, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.titleSmall)
            Text(p.hint, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        }
        Spacer(Modifier.width(10.dp))
        StrengthDots(p.strength)
    }
}

/** ●●○ — how strong a proof is. */
@Composable
fun StrengthDots(strength: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..3) {
            Box(Modifier.size(width = 12.dp, height = 3.dp).clip(CircleShape).background(if (i <= strength) Tone.Text else Tone.Hairline))
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, color: androidx.compose.ui.graphics.Color = Tone.Text) {
    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Tone.Hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(color))
    }
}
