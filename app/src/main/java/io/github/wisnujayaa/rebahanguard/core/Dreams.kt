package io.github.wisnujayaa.rebahanguard.core

/**
 * How a habit is proven. Strength 3 = sensors decide; 2 = evidence a person could still fake
 * with effort; 1 = honesty (logged and visible to the partner as such).
 */
enum class Proof(val label: String, val strength: Int, val hint: String) {
    DESK("Mode Meja", 3, "HP berdiri di meja; kamera memeriksa di waktu acak bahwa kamu duduk"),
    MOVE("Gerak", 3, "Langkah dan ritme dihitung sensor"),
    FOCUS("Fokus tanpa HP", 2, "HP tidak dipakai dan aplikasi pengalih dikunci"),
    PLACE("Tempat", 2, "Berada di tempat yang kamu simpan"),
    PHOTO("Foto hasil", 2, "Foto langsung dari aplikasi, bukan dari galeri"),
    HONEST("Kejujuran", 1, "Dicentang sendiri, dilaporkan sebagai jujur"),
}

enum class HabitUnit(val label: String) {
    MINUTES("menit"),
    PAGES("halaman"),
    STEPS("langkah"),
    TIMES("kali"),
}

/** A dream (impian): the reason behind everything else, in the user's own words. */
data class Dream(
    val id: Long,
    val title: String,
    val why: String,
    /** A measurable target with a date, e.g. "IELTS 7.0" by May 2027. Optional. */
    val target: String = "",
    val targetDateMs: Long? = null,
    val createdAtMs: Long = 0,
    val createdDay: Int = 0,
    val archived: Boolean = false,
)

/**
 * A habit (kebiasaan) serving a dream: what is actually done, how much per day, on which days,
 * optionally in a fixed time window ("jam target"), and how it is proven.
 */
data class Habit(
    val id: Long,
    val dreamId: Long,
    val title: String,
    val unit: HabitUnit = HabitUnit.MINUTES,
    val dailyTarget: Int = 30,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val days: Int = ALL_DAYS,
    /** Minutes since midnight; both null = no time window. */
    val windowStart: Int? = null,
    val windowEnd: Int? = null,
    val proof: Proof = Proof.DESK,
    val createdDay: Int = 0,
) {
    val hasWindow: Boolean get() = windowStart != null && windowEnd != null

    companion object {
        const val ALL_DAYS = 0b1111111
    }
}

/** Progress on one habit for one day. [strength] is the strongest proof seen that day. */
data class HabitDay(
    val habitId: Long,
    val day: Int,
    val amount: Int = 0,
    val strength: Int = 0,
)

/** A lighter change waiting for tomorrow (see [DreamRules.defer]). */
data class Pending(
    val effectiveDay: Int,
    val habit: Habit? = null,
    val dream: Dream? = null,
    /** Delete this habit id (habit and dream null) or archive the dream ([dream] set, archived). */
    val deleteHabitId: Long? = null,
)

data class DreamBook(
    val dreams: List<Dream> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val log: List<HabitDay> = emptyList(),
    val pending: List<Pending> = emptyList(),
) {
    val activeDreams: List<Dream> get() = dreams.filter { !it.archived }
    fun habitsOf(dreamId: Long) = habits.filter { it.dreamId == dreamId }
    fun dream(id: Long) = dreams.firstOrNull { it.id == id }
    fun habit(id: Long) = habits.firstOrNull { it.id == id }
}

/**
 * Days for habits run from 04:00 to 04:00, so studying past midnight still counts for "today".
 */
object DayClock {
    const val DAY_MS = 86_400_000L
    const val CUTOFF_MS = 4 * 3_600_000L

    fun dayOf(epochMs: Long, zoneOffsetMs: Int): Int = Math.floorDiv(epochMs + zoneOffsetMs - CUTOFF_MS, DAY_MS).toInt()

    /** 0 = Monday … 6 = Sunday. 1970-01-01 (day 0) was a Thursday. */
    fun weekdayOf(day: Int): Int = Math.floorMod(day + 3, 7)
}

object DreamRules {
    const val MAX_ACTIVE_DREAMS = 3
    const val MAX_TITLE = 80
    const val MAX_WHY = 400
    const val MAX_DAILY_TARGET = 100_000
    const val KEEP_LOG_DAYS = 120

    fun canAddDream(book: DreamBook): Boolean = book.activeDreams.size < MAX_ACTIVE_DREAMS

    fun isScheduled(habit: Habit, day: Int): Boolean = habit.days and (1 shl DayClock.weekdayOf(day)) != 0

    fun inWindow(habit: Habit, minuteOfDay: Int): Boolean {
        val s = habit.windowStart ?: return false
        val e = habit.windowEnd ?: return false
        return Schedule(enabled = true, startMinute = s, endMinute = e).isWithin(minuteOfDay)
    }

    fun amount(book: DreamBook, habitId: Long, day: Int): Int =
        book.log.firstOrNull { it.habitId == habitId && it.day == day }?.amount ?: 0

    fun isDone(book: DreamBook, habit: Habit, day: Int): Boolean = amount(book, habit.id, day) >= habit.dailyTarget

    /** Adds progress (clamped, never negative) and remembers the strongest proof of the day. */
    fun addProgress(book: DreamBook, habitId: Long, day: Int, delta: Int, strength: Int): DreamBook {
        if (delta <= 0 || book.habit(habitId) == null) return book
        val existing = book.log.firstOrNull { it.habitId == habitId && it.day == day } ?: HabitDay(habitId, day)
        val updated = existing.copy(
            amount = (existing.amount.toLong() + delta).coerceAtMost(MAX_DAILY_TARGET.toLong() * 10).toInt(),
            strength = maxOf(existing.strength, strength.coerceIn(0, 3)),
        )
        val log = (book.log.filterNot { it.habitId == habitId && it.day == day } + updated)
            .filter { it.day > day - KEEP_LOG_DAYS }
        return book.copy(log = log)
    }

    /** Habits due today that are not done yet, the ones whose window is open first. */
    fun behindToday(book: DreamBook, day: Int, minuteOfDay: Int): List<Habit> =
        book.habits
            .filter { h -> book.dream(h.dreamId)?.archived == false && isScheduled(h, day) && !isDone(book, h, day) }
            .sortedWith(compareByDescending<Habit> { inWindow(it, minuteOfDay) }.thenBy { it.windowStart ?: Int.MAX_VALUE })

    /** Consecutive scheduled days on which every habit of the dream was done, up to yesterday. */
    fun streak(book: DreamBook, dreamId: Long, today: Int): Int {
        val habits = book.habitsOf(dreamId)
        if (habits.isEmpty()) return 0
        var d = today - 1
        var count = 0
        var guard = 0
        while (guard++ < KEEP_LOG_DAYS) {
            val due = habits.filter { isScheduled(it, d) && it.createdDay <= d }
            if (due.isEmpty()) {
                if (habits.all { it.createdDay > d }) break
                d--
                continue
            }
            if (due.all { isDone(book, it, d) }) count++ else break
            d--
        }
        // Today counts too once everything is already done.
        val dueToday = habits.filter { isScheduled(it, today) }
        if (dueToday.isNotEmpty() && dueToday.all { isDone(book, it, today) }) count++
        return count
    }

    // ------------------------------------------------------------------ "berlaku besok"

    /**
     * Whether replacing [old] with [new] makes the commitment lighter. Lighter changes wait until
     * tomorrow, so the tired, tempted self of tonight can't undo what the clear-headed self set.
     */
    fun isLighter(old: Habit, new: Habit): Boolean {
        if (new.dailyTarget < old.dailyTarget) return true
        if (new.unit != old.unit) return true // can't compare: treat as lighter
        if ((old.days and new.days.inv()) != 0) return true // a scheduled day removed
        if (new.proof.strength < old.proof.strength) return true
        if (old.hasWindow && !new.hasWindow) return true
        if (old.hasWindow && new.hasWindow && windowLength(new) < windowLength(old)) return true
        return false
    }

    fun isLighter(old: Dream, new: Dream): Boolean =
        old.why != new.why || old.title != new.title || old.target != new.target ||
            old.targetDateMs != new.targetDateMs || (!old.archived && new.archived)

    private fun windowLength(h: Habit): Int {
        val s = h.windowStart ?: return 0
        val e = h.windowEnd ?: return 0
        return Math.floorMod(e - s, Schedule.MINUTES_PER_DAY).let { if (it == 0) Schedule.MINUTES_PER_DAY else it }
    }

    sealed interface Change {
        data class AddDream(val dream: Dream) : Change
        data class EditDream(val dream: Dream) : Change
        data class AddHabit(val habit: Habit) : Change
        data class EditHabit(val habit: Habit) : Change
        data class DeleteHabit(val habitId: Long) : Change
    }

    data class Result(val book: DreamBook, val deferred: Boolean, val rejected: String? = null)

    /**
     * Applies a change now, or queues it for tomorrow if it makes things lighter. Anything created
     * today may still be edited today (fixing a typo right after writing it is not cheating).
     */
    fun apply(book: DreamBook, change: Change, today: Int): Result = when (change) {
        is Change.AddDream -> when {
            !canAddDream(book) -> Result(book, false, "Maksimal $MAX_ACTIVE_DREAMS impian aktif. Selesaikan atau arsipkan satu dulu.")
            book.dream(change.dream.id) != null -> Result(book, false, "Impian ini sudah ada.")
            else -> Result(book.copy(dreams = book.dreams + change.dream.copy(createdDay = today)), false)
        }

        is Change.EditDream -> {
            val old = book.dream(change.dream.id)
            when {
                old == null -> Result(book, false, "Impian tidak ditemukan.")
                old.archived && !change.dream.archived && !canAddDream(book) ->
                    Result(book, false, "Maksimal $MAX_ACTIVE_DREAMS impian aktif. Arsipkan satu dulu.")
                old.createdDay == today || !isLighter(old, change.dream) ->
                    Result(book.copy(dreams = book.dreams.map { if (it.id == old.id) change.dream.copy(createdDay = old.createdDay) else it }), false)
                else -> Result(queue(book, Pending(today + 1, dream = change.dream.copy(createdDay = old.createdDay))), true)
            }
        }

        is Change.AddHabit -> when {
            book.dream(change.habit.dreamId)?.archived != false -> Result(book, false, "Pilih impian yang masih aktif.")
            book.habit(change.habit.id) != null -> Result(book, false, "Kebiasaan ini sudah ada.")
            else -> Result(book.copy(habits = book.habits + change.habit.copy(createdDay = today)), false)
        }

        is Change.EditHabit -> {
            val old = book.habit(change.habit.id)
            when {
                old == null -> Result(book, false, "Kebiasaan tidak ditemukan.")
                old.createdDay == today || !isLighter(old, change.habit) ->
                    Result(book.copy(habits = book.habits.map { if (it.id == old.id) change.habit.copy(createdDay = old.createdDay, dreamId = old.dreamId) else it }), false)
                else -> Result(queue(book, Pending(today + 1, habit = change.habit.copy(createdDay = old.createdDay, dreamId = old.dreamId))), true)
            }
        }

        is Change.DeleteHabit -> {
            val old = book.habit(change.habitId)
            when {
                old == null -> Result(book, false, "Kebiasaan tidak ditemukan.")
                old.createdDay == today -> Result(dropHabit(book, old.id), false)
                else -> Result(queue(book, Pending(today + 1, deleteHabitId = old.id)), true)
            }
        }
    }

    /** One pending change per target: a newer request replaces an older one. */
    private fun queue(book: DreamBook, p: Pending): DreamBook {
        val key = targetKey(p)
        return book.copy(pending = book.pending.filterNot { targetKey(it) == key } + p)
    }

    private fun targetKey(p: Pending): String = when {
        p.dream != null -> "d${p.dream.id}"
        p.habit != null -> "h${p.habit.id}"
        else -> "h${p.deleteHabitId}"
    }

    /** Applies every pending change whose day has come. Call on every load. */
    fun settle(book: DreamBook, today: Int): DreamBook {
        val (due, later) = book.pending.partition { it.effectiveDay <= today }
        var b = book.copy(pending = later)
        for (p in due.sortedBy { it.effectiveDay }) {
            b = when {
                p.dream != null -> {
                    val current = b.dream(p.dream.id)
                    // Never let a queued change bring back a 4th active dream.
                    val next = if (current != null && current.archived && !p.dream.archived && !canAddDream(b)) p.dream.copy(archived = true) else p.dream
                    b.copy(dreams = b.dreams.map { if (it.id == next.id) next else it })
                }
                p.habit != null -> b.copy(habits = b.habits.map { if (it.id == p.habit.id) p.habit else it })
                p.deleteHabitId != null -> dropHabit(b, p.deleteHabitId)
                else -> b
            }
        }
        return b
    }

    private fun dropHabit(book: DreamBook, id: Long) =
        book.copy(habits = book.habits.filterNot { it.id == id }, pending = book.pending.filterNot { it.habit?.id == id || it.deleteHabitId == id })

    fun sanitizeText(raw: String, max: Int): String? =
        raw.filter { !it.isISOControl() || it == ' ' }.replace(Regex("\\s+"), " ").trim().take(max).trim().ifEmpty { null }

    // ------------------------------------------------------------------ lock screen

    data class LockCopy(val title: String, val body: String, val quote: String?, val quoteDate: String?)

    /**
     * What the lock screen says when no deadline is pressing: the habit that is behind (its
     * window first), and the user's own reason for the dream behind it.
     */
    fun lockCopy(book: DreamBook, day: Int, minuteOfDay: Int, index: Long): LockCopy? {
        val habit = behindToday(book, day, minuteOfDay).firstOrNull()
        val dream = habit?.let { book.dream(it.dreamId) }
            ?: book.activeDreams.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(index, it.size.toLong()).toInt()] }
            ?: return null
        val body = if (habit != null) {
            val done = amount(book, habit.id, day)
            val inWin = inWindow(habit, minuteOfDay)
            "Hari ini baru $done dari ${habit.dailyTarget} ${habit.unit.label} untuk “${habit.title}”." +
                if (inWin) " Ini jam targetnya." else ""
        } else {
            "Kamu menulis impian ini sendiri: “${dream.title}”."
        }
        return LockCopy(
            title = "Kamu memilih kasur.",
            body = body,
            quote = dream.why.takeIf { it.isNotBlank() },
            quoteDate = null,
        )
    }
}
