package io.github.wisnujayaa.rebahanguard.core

/**
 * Plain-text storage for the [DreamBook]: one record per line, tab-separated, with a type tag
 * (D dream, H habit, L log, P pending). Text fields are backslash-escaped. Every field is
 * validated on the way in; a broken line is skipped, never fatal.
 */
object DreamCodec {
    private const val VERSION = "dreams-v1"

    fun encode(book: DreamBook): String = buildString {
        append(VERSION).append('\n')
        for (d in book.dreams) append("D\t").append(dreamFields(d)).append('\n')
        for (h in book.habits) append("H\t").append(habitFields(h)).append('\n')
        for (l in book.log) append("L\t${l.habitId}\t${l.day}\t${l.amount}\t${l.strength}\n")
        for (p in book.pending) {
            append("P\t${p.effectiveDay}\t")
            when {
                p.dream != null -> append("D\t").append(dreamFields(p.dream))
                p.habit != null -> append("H\t").append(habitFields(p.habit))
                else -> append("X\t${p.deleteHabitId}")
            }
            append('\n')
        }
    }

    fun decode(raw: String?): DreamBook {
        if (raw.isNullOrEmpty()) return DreamBook()
        val lines = raw.split('\n')
        if (lines.firstOrNull() != VERSION) return DreamBook()
        val dreams = LinkedHashMap<Long, Dream>()
        val habits = LinkedHashMap<Long, Habit>()
        val log = LinkedHashMap<Pair<Long, Int>, HabitDay>()
        val pending = ArrayList<Pending>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "D" -> parseDream(f.drop(1))?.let { if (it.id !in dreams) dreams[it.id] = it }
                    "H" -> parseHabit(f.drop(1))?.let { if (it.id !in habits) habits[it.id] = it }
                    "L" -> if (f.size == 5) {
                        val l = HabitDay(f[1].toLong(), f[2].toInt(), f[3].toInt().coerceIn(0, DreamRules.MAX_DAILY_TARGET * 10), f[4].toInt().coerceIn(0, 3))
                        log.putIfAbsent(l.habitId to l.day, l)
                    }
                    "P" -> if (f.size >= 4) {
                        val day = f[1].toInt()
                        val p = when (f[2]) {
                            "D" -> parseDream(f.drop(3))?.let { Pending(day, dream = it) }
                            "H" -> parseHabit(f.drop(3))?.let { Pending(day, habit = it) }
                            "X" -> if (f.size == 4) Pending(day, deleteHabitId = f[3].toLong()) else null
                            else -> null
                        }
                        if (p != null) pending += p
                    }
                }
            } catch (e: NumberFormatException) {
                // skip the line
            } catch (e: IndexOutOfBoundsException) {
                // skip the line
            }
        }
        // Drop orphans: habits without a dream, log entries and pending changes without a target.
        val validHabits = habits.values.filter { it.dreamId in dreams }
        val habitIds = validHabits.map { it.id }.toSet()
        return DreamBook(
            dreams = dreams.values.toList(),
            habits = validHabits,
            log = log.values.filter { it.habitId in habitIds },
            pending = pending.filter { p ->
                when {
                    p.dream != null -> p.dream.id in dreams
                    p.habit != null -> p.habit.id in habitIds && p.habit.dreamId in dreams
                    else -> p.deleteHabitId in habitIds
                }
            },
        )
    }

    private fun dreamFields(d: Dream) = listOf(
        d.id, esc(d.title), esc(d.why), esc(d.target), d.targetDateMs ?: "", d.createdAtMs, d.createdDay, if (d.archived) 1 else 0,
    ).joinToString("\t")

    private fun habitFields(h: Habit) = listOf(
        h.id, h.dreamId, esc(h.title), h.unit.name, h.dailyTarget, h.days, h.windowStart ?: "", h.windowEnd ?: "", h.proof.name, h.createdDay,
    ).joinToString("\t")

    private fun parseDream(f: List<String>): Dream? {
        if (f.size != 8) return null
        val title = DreamRules.sanitizeText(unesc(f[1]), DreamRules.MAX_TITLE) ?: return null
        return Dream(
            id = f[0].toLong(),
            title = title,
            why = DreamRules.sanitizeText(unesc(f[2]), DreamRules.MAX_WHY).orEmpty(),
            target = DreamRules.sanitizeText(unesc(f[3]), DreamRules.MAX_TITLE).orEmpty(),
            targetDateMs = f[4].takeIf { it.isNotEmpty() }?.toLong()?.takeIf { it > 0 },
            createdAtMs = f[5].toLong().coerceAtLeast(0),
            createdDay = f[6].toInt(),
            archived = f[7] == "1",
        )
    }

    private fun parseHabit(f: List<String>): Habit? {
        if (f.size != 10) return null
        val title = DreamRules.sanitizeText(unesc(f[2]), DreamRules.MAX_TITLE) ?: return null
        val start = f[6].takeIf { it.isNotEmpty() }?.toInt()
        val end = f[7].takeIf { it.isNotEmpty() }?.toInt()
        val windowOk = start != null && end != null && start in 0 until Schedule.MINUTES_PER_DAY && end in 0 until Schedule.MINUTES_PER_DAY
        val days = f[5].toInt() and Habit.ALL_DAYS
        return Habit(
            id = f[0].toLong(),
            dreamId = f[1].toLong(),
            title = title,
            unit = HabitUnit.entries.firstOrNull { it.name == f[3] } ?: HabitUnit.TIMES,
            dailyTarget = f[4].toInt().coerceIn(1, DreamRules.MAX_DAILY_TARGET),
            days = if (days == 0) Habit.ALL_DAYS else days,
            windowStart = if (windowOk) start else null,
            windowEnd = if (windowOk) end else null,
            proof = Proof.entries.firstOrNull { it.name == f[8] } ?: Proof.HONEST,
            createdDay = f[9].toInt(),
        )
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
