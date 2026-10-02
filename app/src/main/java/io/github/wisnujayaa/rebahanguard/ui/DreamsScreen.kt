package io.github.wisnujayaa.rebahanguard.ui

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.wisnujayaa.rebahanguard.core.DeskVerdict
import io.github.wisnujayaa.rebahanguard.core.Dream
import io.github.wisnujayaa.rebahanguard.core.DreamBook
import io.github.wisnujayaa.rebahanguard.core.DreamRules
import io.github.wisnujayaa.rebahanguard.core.EvidenceReport
import io.github.wisnujayaa.rebahanguard.core.Habit
import io.github.wisnujayaa.rebahanguard.core.HabitUnit
import io.github.wisnujayaa.rebahanguard.core.Cloze
import io.github.wisnujayaa.rebahanguard.core.NightStats
import io.github.wisnujayaa.rebahanguard.core.Proof
import io.github.wisnujayaa.rebahanguard.core.Schedule
import io.github.wisnujayaa.rebahanguard.core.Spacing
import io.github.wisnujayaa.rebahanguard.service.ActiveSession
import io.github.wisnujayaa.rebahanguard.service.DreamStore
import io.github.wisnujayaa.rebahanguard.service.LastCheck
import io.github.wisnujayaa.rebahanguard.service.PartnerStore
import io.github.wisnujayaa.rebahanguard.service.QuizStore
import io.github.wisnujayaa.rebahanguard.service.SessionStore
import io.github.wisnujayaa.rebahanguard.service.StatsStore
import kotlinx.coroutines.delay

/** Starts a session for a habit; the caller checks permissions first. */
typealias StartSession = (habit: Habit, minutes: Int) -> Unit

private sealed interface Editing {
    data object NewDream : Editing
    data class EditDream(val dream: Dream) : Editing
    data class NewHabit(val dreamId: Long) : Editing
    data class EditHabit(val habit: Habit) : Editing
    data class Photo(val habit: Habit) : Editing
    data class Start(val habit: Habit) : Editing
}

@Composable
fun DreamsTab(refreshKey: Any?, checks: List<LastCheck>, onStartSession: StartSession) {
    val context = LocalContext.current
    val book by DreamStore.flow(context).collectAsState()
    val session by SessionStore.session.collectAsState()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val b = book ?: DreamBook()
    val today = DreamStore.today()

    fun applyChange(change: DreamRules.Change) {
        val r = DreamStore.apply(context, change)
        notice = r.rejected ?: if (r.deferred) "Perubahan ini meringankan komitmenmu, jadi baru berlaku besok." else null
        if (r.rejected == null) editing = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Impian", style = MaterialTheme.typography.headlineLarge)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.Lamp) }
        session?.let { SessionPanel(it) }

        when (val e = editing) {
            Editing.NewDream -> DreamEditor(null, onSave = { applyChange(DreamRules.Change.AddDream(it)) }, onArchive = null, onCancel = { editing = null })
            is Editing.EditDream -> DreamEditor(e.dream, onSave = { applyChange(DreamRules.Change.EditDream(it)) },
                onArchive = { applyChange(DreamRules.Change.EditDream(e.dream.copy(archived = true))) }, onCancel = { editing = null })
            is Editing.NewHabit -> HabitEditor(e.dreamId, null, onSave = { applyChange(DreamRules.Change.AddHabit(it)) }, onDelete = null, onCancel = { editing = null })
            is Editing.EditHabit -> HabitEditor(e.habit.dreamId, e.habit, onSave = { applyChange(DreamRules.Change.EditHabit(it)) },
                onDelete = { applyChange(DreamRules.Change.DeleteHabit(e.habit.id)) }, onCancel = { editing = null })
            is Editing.Photo -> PhotoProof(e.habit, onClose = { editing = null })
            is Editing.Start -> StartSessionCard(e.habit, b, today, onStart = { minutes -> onStartSession(e.habit, minutes); editing = null }, onCancel = { editing = null })
            null -> Unit
        }

        if (b.activeDreams.isEmpty() && editing == null) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                AccentRule(Tone.Lamp, Modifier.fillMaxHeight())
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tulis apa yang ingin kamu capai, dan kenapa itu penting bagimu.",
                        style = MaterialTheme.typography.titleLarge.copy(fontStyle = FontStyle.Italic))
                    Text("Kalimatmu sendiri yang akan muncul di layar kunci saat kamu memilih rebahan.",
                        style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
                }
            }
        }

        for (dream in b.activeDreams) {
            DreamBlock(
                book = b, dream = dream, today = today, sessionRunning = session != null,
                onEdit = { editing = Editing.EditDream(dream); notice = null },
                onAddHabit = { editing = Editing.NewHabit(dream.id); notice = null },
                onEditHabit = { editing = Editing.EditHabit(it); notice = null },
                onStart = { editing = Editing.Start(it) },
                onPhoto = { editing = Editing.Photo(it) },
                onTick = { h -> DreamStore.addProgress(context, h.id, h.dailyTarget - DreamRules.amount(b, h.id, today), SessionStore.strength(h.id, today)) },
            )
        }

        if (DreamRules.canAddDream(b) && editing == null) {
            OutlinedButton(onClick = { editing = Editing.NewDream; notice = null }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
                Text(if (b.activeDreams.isEmpty()) "Tulis impian pertamamu" else "Tambah impian (${b.activeDreams.size}/${DreamRules.MAX_ACTIVE_DREAMS})")
            }
        }

        if (b.pending.isNotEmpty()) {
            Text("${b.pending.size} perubahan menunggu berlaku besok.", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        }

        QuizSection(today)

        HorizontalDivider(color = Tone.Hairline)
        Kicker("Jejakmu")
        StatsSection(refreshKey)
        TextButton(onClick = {
            val text = EvidenceReport.summary(b, today, PartnerStore.name(context)) + "\n" +
                NightStats.summary(StatsStore.load(context), StatsStore.tonight(), null)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Kirim laporan ke teman"))
        }) { Text("Kirim laporan lengkap ke teman") }
        CheckHistory(checks)
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DreamBlock(
    book: DreamBook,
    dream: Dream,
    today: Int,
    sessionRunning: Boolean,
    onEdit: () -> Unit,
    onAddHabit: () -> Unit,
    onEditHabit: (Habit) -> Unit,
    onStart: (Habit) -> Unit,
    onPhoto: (Habit) -> Unit,
    onTick: (Habit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = Tone.Hairline)
        Column(Modifier.clickable(onClick = onEdit)) {
            val streak = DreamRules.streak(book, dream.id, today)
            Kicker(if (streak > 0) "Impian · $streak hari berturut-turut" else "Impian")
            Text(dream.title, style = MaterialTheme.typography.headlineMedium)
            if (dream.target.isNotBlank()) Text(dream.target, style = MaterialTheme.typography.bodyMedium, color = Tone.Muted)
        }
        if (dream.why.isNotBlank()) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                AccentRule(Tone.Lamp, Modifier.fillMaxHeight())
                Text("“${dream.why}”", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fraunces, fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(start = 14.dp))
            }
        }
        for (h in book.habitsOf(dream.id)) {
            HabitRow(book, h, today, sessionRunning, onEdit = { onEditHabit(h) }, onStart = { onStart(h) }, onPhoto = { onPhoto(h) }, onTick = { onTick(h) })
        }
        TextButton(onClick = onAddHabit) { Text("Tambah kebiasaan", color = Tone.Muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitRow(book: DreamBook, h: Habit, today: Int, sessionRunning: Boolean, onEdit: () -> Unit, onStart: () -> Unit, onPhoto: () -> Unit, onTick: () -> Unit) {
    val amount = DreamRules.amount(book, h.id, today)
    val done = amount >= h.dailyTarget
    val scheduled = DreamRules.isScheduled(h, today)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.clickable(onClick = onEdit), verticalAlignment = Alignment.Bottom) {
            Text(h.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$amount / ${h.dailyTarget} ${h.unit.label}", style = MaterialTheme.typography.bodySmall, color = if (done) Tone.Mint else Tone.Muted)
        }
        ProgressLine(amount.toFloat() / h.dailyTarget, if (done) Tone.Mint else Tone.Text)
        val meta = buildList {
            add(h.proof.label)
            if (h.hasWindow) add("jam target ${Schedule.format(h.windowStart!!)}–${Schedule.format(h.windowEnd!!)}")
            if (!scheduled) add("libur hari ini")
        }.joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        if (!done && scheduled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (h.proof) {
                    Proof.DESK, Proof.FOCUS, Proof.MOVE, Proof.PLACE ->
                        if (!sessionRunning) Chip("Mulai sesi", false, onStart)
                    Proof.PHOTO -> Chip("Foto hasil", false, onPhoto)
                    Proof.HONEST -> Chip("Tandai selesai", false, onTick)
                }
                if (h.unit == HabitUnit.PAGES && h.proof != Proof.PHOTO) Chip("Foto halaman", false, onPhoto)
                if (h.unit != HabitUnit.MINUTES && h.unit != HabitUnit.STEPS && h.proof != Proof.HONEST && h.proof != Proof.PHOTO) Chip("Tandai selesai", false, onTick)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartSessionCard(habit: Habit, book: DreamBook, today: Int, onStart: (Int) -> Unit, onCancel: () -> Unit) {
    val left = (habit.dailyTarget - DreamRules.amount(book, habit.id, today)).coerceAtLeast(5)
    var minutes by remember { mutableStateOf(if (habit.unit == HabitUnit.MINUTES) left.coerceAtMost(120) else 25) }
    EditorCard {
        Kicker("Mulai sesi · ${habit.proof.label}")
        Text(habit.title, style = MaterialTheme.typography.titleLarge)
        Text(
            when (habit.proof) {
                Proof.DESK -> "Berdirikan HP di dudukan di meja, kamera depan menghadap wajahmu. Kamera akan memeriksa di waktu acak bahwa kamu duduk. Kalau kamu rebahan atau pergi, alarm berbunyi."
                Proof.FOCUS -> "Telungkupkan HP atau matikan layarnya. Waktu hanya dihitung saat HP tidak dipakai, dan aplikasi pengalih dikunci."
                Proof.MOVE -> "Bawa HP-mu. Langkah dan ritmenya dihitung sensor."
                Proof.PLACE -> "Waktu dihitung selama kamu berada di tempat yang kamu simpan (GPS dan Wi-Fi)."
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall, color = Tone.Muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(25, 50, 90, left.coerceAtMost(180)).distinct()) Chip("$m menit", m == minutes) { minutes = m }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStart(minutes) }, shape = CircleShape) { Text("Mulai") }
            TextButton(onClick = onCancel) { Text("Batal", color = Tone.Muted) }
        }
    }
}

/** The running session: time, what the sensors saw, and a way to end it. */
@Composable
fun SessionPanel(s: ActiveSession) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val elapsed = (now - s.startedElapsedMs).coerceAtLeast(0)
    EditorCard {
        Kicker("Sesi berjalan · ${s.proof.label}", if (s.alarm != null) Tone.Blanket else Tone.Mint)
        Text(s.title, style = MaterialTheme.typography.titleLarge)
        Text("${elapsed / 60_000}:${"%02d".format((elapsed / 1000) % 60)} / ${s.targetMs / 60_000}:00",
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = Fraunces))
        ProgressLine(elapsed.toFloat() / s.targetMs)
        s.alarm?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Tone.Blanket) }
        val lines = buildList {
            s.desk?.let { d ->
                add("Pemeriksaan kamera: ${d.checks} kali, terlihat duduk ${d.present} kali")
                s.lastVerdict?.let { v ->
                    add("Terakhir: " + when (v) {
                        DeskVerdict.PRESENT -> "kamu terlihat duduk"
                        DeskVerdict.LYING -> "kamu terlihat rebahan"
                        DeskVerdict.ABSENT -> "kamu tidak terlihat"
                        DeskVerdict.UNSURE -> "kurang jelas (gelap atau sudut aneh)"
                    })
                }
            }
            if (s.unit == HabitUnit.MINUTES) add("Tercatat ${s.creditedMinutes} menit")
            if (s.steps > 0 || s.proof == Proof.MOVE) add("${s.steps} langkah")
            s.placeName?.let { add(if (s.atPlace == true) "Berada di $it" else "Belum terdeteksi di $it") }
        }
        for (l in lines) Text(l, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        OutlinedButton(onClick = { io.github.wisnujayaa.rebahanguard.service.GuardService.stopSession(context) }, shape = CircleShape) {
            Text("Akhiri sesi")
        }
    }
}

/** Fill-in-the-blank questions from photographed pages, spaced out over days. */
@Composable
private fun QuizSection(today: Int) {
    val context = LocalContext.current
    val cards by QuizStore.flow(context).collectAsState()
    val all = cards.orEmpty()
    if (all.isEmpty()) return
    val due = Spacing.due(all, today)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = Tone.Hairline)
        val retention = Spacing.retention(all)
        Kicker(if (retention != null) "Kuis ingatan · ${(retention * 100).toInt()}% benar" else "Kuis ingatan", Tone.Lamp)
        if (due.isEmpty()) {
            Text("Tidak ada pertanyaan untuk hari ini. Pertanyaan muncul lagi dengan jarak makin panjang: 1, 3, 7, 14, 30 hari.",
                style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        } else {
            QuizCardView(due.first(), due.size, today)
        }
    }
}

@Composable
private fun QuizCardView(card: io.github.wisnujayaa.rebahanguard.core.QuizCard, dueCount: Int, today: Int) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var answer by remember(card.id) { mutableStateOf("") }
        var result by remember(card.id) { mutableStateOf<Boolean?>(null) }
        Text(card.prompt, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fraunces))
        OutlinedTextField(answer, { answer = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, enabled = result == null,
            placeholder = { Text("Jawab tanpa melihat buku") })
        when (val r = result) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { result = Cloze.isCorrect(answer, card.answer) }, shape = CircleShape) { Text("Periksa") }
                Text("$dueCount pertanyaan hari ini", style = MaterialTheme.typography.bodySmall, color = Tone.Muted, modifier = Modifier.align(Alignment.CenterVertically))
            }
            else -> {
                Text(if (r) "Benar. Muncul lagi nanti dengan jarak lebih panjang." else "Jawabannya: ${card.answer}. Tidak apa-apa, pertanyaan ini muncul lagi besok.",
                    style = MaterialTheme.typography.bodyMedium, color = if (r) Tone.Mint else Tone.Text)
                Button(onClick = { QuizStore.update(context) { list -> list.map { if (it.id == card.id) Spacing.answer(it, r, today) else it } } },
                    shape = CircleShape) { Text("Lanjut") }
            }
        }
    }
}

/** Today's habits on the home tab, with the same actions as the Impian tab. */
@Composable
fun HabitsToday(onOpenDreams: () -> Unit) {
    val context = LocalContext.current
    val book by DreamStore.flow(context).collectAsState()
    val b = book ?: return
    val today = DreamStore.today()
    val habits = b.habits.filter { h -> b.dream(h.dreamId)?.archived == false && DreamRules.isScheduled(h, today) }
    if (habits.isEmpty()) return
    Column(Modifier.clickable(onClick = onOpenDreams), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = Tone.Hairline)
        Kicker("Untuk impianmu", Tone.Lamp)
        for (h in habits) {
            val amount = DreamRules.amount(b, h.id, today)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(h.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("$amount / ${h.dailyTarget} ${h.unit.label}", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            }
            ProgressLine(amount.toFloat() / h.dailyTarget, if (amount >= h.dailyTarget) Tone.Mint else Tone.Text)
        }
    }
}
