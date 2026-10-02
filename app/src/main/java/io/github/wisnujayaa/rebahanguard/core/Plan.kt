package io.github.wisnujayaa.rebahanguard.core

enum class PlanCategory(val label: String) {
    KULIAH("Kuliah"),
    BELAJAR("Belajar"),
    RUMAH("Rumah"),
    PEKERJAAN("Pekerjaan"),
    LAINNYA("Lainnya"),
}

/**
 * One thing to do. [dueWallMs] is optional: "read chapter 4" matters even without a deadline.
 * [important] is the user's own call — urgency comes from the clock, importance doesn't.
 */
data class PlanItem(
    val id: Long,
    val title: String,
    val category: PlanCategory = PlanCategory.LAINNYA,
    val dueWallMs: Long? = null,
    val important: Boolean = false,
    val doneAtMs: Long? = null,
    val createdAtMs: Long = 0,
) {
    val isDone: Boolean get() = doneAtMs != null
}

/**
 * The Eisenhower split: urgent (the clock is forcing it), important but not urgent (nothing
 * forces it, so it is the first thing traded for lying in bed), and the rest.
 */
enum class Bucket { URGENT, IMPORTANT, LATER, DONE }

object Plan {
    /** A deadline closer than this (or already passed) makes an item urgent. */
    const val URGENT_WITHIN_MS = 24 * 3_600_000L
    const val MAX_TITLE = 120
    const val MAX_ITEMS = 300
    const val KEEP_DONE = 30

    fun bucket(item: PlanItem, nowMs: Long): Bucket = when {
        item.isDone -> Bucket.DONE
        item.dueWallMs != null && item.dueWallMs - nowMs <= URGENT_WITHIN_MS -> Bucket.URGENT
        item.important -> Bucket.IMPORTANT
        else -> Bucket.LATER
    }

    /** Items grouped by bucket, each list in the order it should be shown. */
    fun grouped(items: List<PlanItem>, nowMs: Long): Map<Bucket, List<PlanItem>> {
        val byBucket = items.groupBy { bucket(it, nowMs) }
        val open = compareBy<PlanItem>({ it.dueWallMs ?: Long.MAX_VALUE }, { it.createdAtMs }, { it.id })
        return Bucket.entries.associateWith { b ->
            val list = byBucket[b].orEmpty()
            if (b == Bucket.DONE) list.sortedByDescending { it.doneAtMs }.take(KEEP_DONE) else list.sortedWith(open)
        }
    }

    /** The open item with the nearest deadline within the urgent window, if any. */
    fun mostUrgent(items: List<PlanItem>, nowMs: Long): PlanItem? =
        items.filter { bucket(it, nowMs) == Bucket.URGENT }.minByOrNull { it.dueWallMs ?: Long.MAX_VALUE }

    /** What the lock screen says when there is something urgent. */
    fun lockMessage(item: PlanItem, nowMs: Long): String {
        val due = item.dueWallMs ?: return "Kamu rebahan, padahal \u201C${item.title}\u201D masih menunggu."
        val left = due - nowMs
        return if (left >= 0) {
            "Kamu rebahan, padahal \u201C${item.title}\u201D harus selesai ${formatDuration(left)} lagi."
        } else {
            "Kamu rebahan, padahal \u201C${item.title}\u201D sudah lewat deadline ${formatDuration(-left)}."
        }
    }

    /** "4 j 12 m", "38 m", "kurang dari 1 m", "2 hari 3 j". */
    fun formatDuration(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0) / 60_000)
        val days = totalMin / (24 * 60)
        val hours = (totalMin / 60) % 24
        val minutes = totalMin % 60
        return when {
            days > 0 -> if (hours > 0) "$days hari $hours j" else "$days hari"
            hours > 0 -> if (minutes > 0) "$hours j $minutes m" else "$hours j"
            minutes > 0 -> "$minutes m"
            else -> "kurang dari 1 m"
        }
    }

    /** Trims, collapses whitespace and drops control characters; null if nothing is left. */
    fun sanitizeTitle(raw: String): String? {
        val cleaned = raw.filter { !it.isISOControl() || it == ' ' }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TITLE)
            .trim()
        return cleaned.ifEmpty { null }
    }

    fun nextId(items: List<PlanItem>, nowMs: Long): Long = maxOf(nowMs, (items.maxOfOrNull { it.id } ?: 0) + 1)

    /** Keeps the list bounded: oldest finished items go first, open items are never dropped. */
    fun prune(items: List<PlanItem>): List<PlanItem> {
        val (done, open) = items.partition { it.isDone }
        val keptDone = done.sortedByDescending { it.doneAtMs }.take(KEEP_DONE)
        return (open.take(MAX_ITEMS) + keptDone).take(MAX_ITEMS)
    }
}

/**
 * Plain-text storage for plan items: one item per line, fields separated by tabs, with
 * backslash escapes for tabs, newlines and backslashes in titles. A corrupted line is skipped,
 * never fatal — a broken file must not take the whole plan down with it.
 */
object PlanCodec {
    private const val VERSION = "v1"

    fun encode(items: List<PlanItem>): String = buildString {
        append(VERSION).append('\n')
        for (i in items) {
            append(i.id).append('\t')
            append(escape(i.title)).append('\t')
            append(i.category.name).append('\t')
            append(i.dueWallMs ?: "").append('\t')
            append(if (i.important) 1 else 0).append('\t')
            append(i.doneAtMs ?: "").append('\t')
            append(i.createdAtMs).append('\n')
        }
    }

    fun decode(raw: String?): List<PlanItem> {
        if (raw.isNullOrEmpty()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull() != VERSION) return emptyList()
        val seen = HashSet<Long>()
        return lines.drop(1).mapNotNull { line -> parse(line) }.filter { seen.add(it.id) }.take(Plan.MAX_ITEMS)
    }

    private fun parse(line: String): PlanItem? {
        if (line.isEmpty()) return null
        val f = line.split('\t')
        if (f.size != 7) return null
        return try {
            val title = Plan.sanitizeTitle(unescape(f[1])) ?: return null
            PlanItem(
                id = f[0].toLong(),
                title = title,
                category = PlanCategory.entries.firstOrNull { it.name == f[2] } ?: PlanCategory.LAINNYA,
                dueWallMs = f[3].takeIf { it.isNotEmpty() }?.toLong()?.takeIf { it > 0 },
                important = f[4] == "1",
                doneAtMs = f[5].takeIf { it.isNotEmpty() }?.toLong()?.takeIf { it > 0 },
                createdAtMs = f[6].toLong().coerceAtLeast(0),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
