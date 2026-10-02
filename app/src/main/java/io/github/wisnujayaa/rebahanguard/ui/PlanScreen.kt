package io.github.wisnujayaa.rebahanguard.ui

import android.app.DatePickerDialog
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wisnujayaa.rebahanguard.core.Bucket
import io.github.wisnujayaa.rebahanguard.core.Plan
import io.github.wisnujayaa.rebahanguard.core.PlanCategory
import io.github.wisnujayaa.rebahanguard.core.PlanItem
import io.github.wisnujayaa.rebahanguard.service.PlanStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val ID = Locale("id", "ID")

/** Ticks every 30 s so "4 j 12 m lagi" and the buckets stay current while the screen is open. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun PlanTab() {
    val context = LocalContext.current
    val items by PlanStore.items(context).collectAsState()
    val now = rememberNow()
    var editing by remember { mutableStateOf<PlanItem?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val grouped = Plan.grouped(items.orEmpty(), now)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Rencana", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Yang mendesak dipaksa oleh jam. Yang penting tidak dipaksa siapa pun, karena itu " +
                "paling sering dikorbankan untuk rebahan.",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Fraunces, fontStyle = FontStyle.Italic),
            color = Tone.Muted,
        )

        if (adding || editing != null) {
            PlanEditor(
                initial = editing,
                onSave = { item ->
                    if (editing == null) PlanStore.add(context, item) else PlanStore.replace(context, item)
                    adding = false
                    editing = null
                },
                onDelete = editing?.let { e -> { PlanStore.remove(context, e.id); editing = null } },
                onCancel = { adding = false; editing = null },
            )
        } else {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape,
            ) { Text("Tambah rencana") }
        }

        Section("Mendesak", Tone.Blanket, grouped.getValue(Bucket.URGENT), now, empty = "Tidak ada deadline dalam 24 jam.") { editing = it; adding = false }
        Section("Penting, tidak mendesak", Tone.Lamp, grouped.getValue(Bucket.IMPORTANT), now, empty = "Tandai rencana sebagai penting agar muncul di sini.") { editing = it; adding = false }
        Section("Nanti saja", Tone.Muted, grouped.getValue(Bucket.LATER), now, empty = null) { editing = it; adding = false }

        val done = grouped.getValue(Bucket.DONE)
        if (done.isNotEmpty()) {
            TextButton(onClick = { showDone = !showDone }) {
                Text(if (showDone) "Sembunyikan yang selesai" else "Lihat yang selesai (${done.size})", color = Tone.Muted)
            }
            if (showDone) Section("Selesai", Tone.Mint, done, now, empty = null) { editing = it; adding = false }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(
    title: String,
    color: Color,
    items: List<PlanItem>,
    now: Long,
    empty: String?,
    onEdit: (PlanItem) -> Unit,
) {
    if (items.isEmpty() && empty == null) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(color = Tone.Hairline)
        Spacer(Modifier.height(10.dp))
        Kicker(title, color)
        if (items.isEmpty()) {
            Text(empty.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Tone.Muted, modifier = Modifier.padding(vertical = 6.dp))
        }
        for (item in items) {
            PlanRow(
                item = item,
                now = now,
                accent = color,
                onToggle = { PlanStore.setDone(context, item.id, !item.isDone) },
                onClick = { onEdit(item) },
            )
        }
    }
}

/** Small tracked label above a section. */
@Composable
fun Kicker(text: String, color: Color = Tone.Muted) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
        color = color,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun PlanRow(item: PlanItem, now: Long, accent: Color, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CheckCircle(done = item.isDone, color = accent, label = item.title, onToggle = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.isDone) Tone.Muted else Tone.Text,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(meta(item, now), style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        }
    }
}

@Composable
private fun CheckCircle(done: Boolean, color: Color, label: String, onToggle: () -> Unit) {
    Box(
        Modifier
            .padding(top = 2.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (done) color else Color.Transparent)
            .border(1.5.dp, if (done) color else Tone.Text.copy(alpha = 0.6f), CircleShape)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = if (done) "Tandai belum selesai: $label" else "Tandai selesai: $label" },
        contentAlignment = Alignment.Center,
    ) {
        if (done) Text("✓", color = Tone.OnInk, fontSize = 13.sp)
    }
}

private fun meta(item: PlanItem, now: Long): String = buildList {
    add(item.category.label)
    item.dueWallMs?.let { due ->
        add(formatDue(due, now))
        if (!item.isDone) {
            val left = due - now
            add(if (left >= 0) "${Plan.formatDuration(left)} lagi" else "terlambat ${Plan.formatDuration(-left)}")
        }
    }
    if (item.important && !item.isDone) add("penting")
}.joinToString(" · ")

fun formatDue(due: Long, now: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = due
    val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH.mm", ID).format(Date(due))
    return when (day - today) {
        0 -> "hari ini $time"
        1 -> "besok $time"
        -1 -> "kemarin $time"
        else -> SimpleDateFormat("EEE d MMM HH.mm", ID).format(Date(due))
    }
}

/** Add or edit one item. Deadline presets cover the common cases without a picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanEditor(
    initial: PlanItem?,
    onSave: (PlanItem) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category ?: PlanCategory.KULIAH) }
    var important by remember(initial) { mutableStateOf(initial?.important ?: false) }
    var due by remember(initial) { mutableStateOf(initial?.dueWallMs) }
    var error by remember { mutableStateOf(false) }

    fun pickCustom() {
        val cal = Calendar.getInstance().apply { timeInMillis = due ?: (System.currentTimeMillis() + 3_600_000) }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, d, h, min, 0)
                cal.set(Calendar.MILLISECOND, 0)
                due = cal.timeInMillis
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun endOfDay(addDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, addDays)
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (initial == null) "Rencana baru" else "Ubah rencana", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(Plan.MAX_TITLE); error = false },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Laporan praktikum DDP2") },
            isError = error,
            supportingText = { if (error) Text("Tulis dulu apa yang mau dikerjakan.") },
            singleLine = true,
        )
        Kicker("Kategori")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in PlanCategory.entries) Chip(c.label, selected = c == category) { category = c }
        }
        Kicker("Deadline")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val today = endOfDay(0)
            val tomorrow = endOfDay(1)
            Chip("Tanpa deadline", selected = due == null) { due = null }
            Chip("Hari ini 23.59", selected = due == today) { due = today }
            Chip("Besok 23.59", selected = due == tomorrow) { due = tomorrow }
            val custom = due != null && due != today && due != tomorrow
            Chip(if (custom) formatDue(due!!, System.currentTimeMillis()) else "Pilih tanggal…", selected = custom) { pickCustom() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Penting bagiku", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Untuk hal yang tidak mendesak tapi tidak boleh terus ditunda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Muted,
                )
            }
            Switch(
                checked = important,
                onCheckedChange = { important = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Tone.Ink, checkedTrackColor = Tone.Lamp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val clean = Plan.sanitizeTitle(title)
                    if (clean == null) {
                        error = true
                    } else {
                        val now = System.currentTimeMillis()
                        val base = initial ?: PlanItem(
                            id = Plan.nextId(PlanStore.load(context), now),
                            title = clean,
                            createdAtMs = now,
                        )
                        onSave(base.copy(title = clean, category = category, important = important, dueWallMs = due))
                    }
                },
                shape = CircleShape,
            ) { Text("Simpan") }
            TextButton(onClick = onCancel) { Text("Batal", color = Tone.Muted) }
            Spacer(Modifier.weight(1f))
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Hapus", color = Tone.Blanket) }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) Tone.Text else Color.Transparent)
            .border(1.dp, if (selected) Tone.Text else Tone.Hairline, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (selected) Tone.OnInk else Tone.Text)
    }
}

/** A thin progress-like accent rule, used to the left of urgent callouts. */
@Composable
fun AccentRule(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(2.dp).background(color))
}
