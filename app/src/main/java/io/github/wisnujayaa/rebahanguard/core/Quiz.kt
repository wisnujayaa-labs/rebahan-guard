package io.github.wisnujayaa.rebahanguard.core

import java.text.Normalizer
import kotlin.random.Random

/**
 * Retrieval practice from the pages the user photographed: a sentence from the page with one
 * key word blanked out (a cloze). Works offline on every phone and is graded exactly, so a wrong
 * answer never depends on a language model's mood. Wrong answers are never punished: the card
 * simply comes back sooner (punishing would push people toward easy material).
 */
data class QuizCard(
    val id: Long,
    val habitId: Long,
    /** The sentence with the answer replaced by "____". */
    val prompt: String,
    val answer: String,
    val createdDay: Int,
    /** Index into [Spacing.INTERVALS]; -1 = never answered. */
    val box: Int = -1,
    val dueDay: Int = createdDay,
    val correct: Int = 0,
    val wrong: Int = 0,
)

object Cloze {
    const val BLANK = "____"
    const val MIN_SENTENCE_WORDS = 6
    const val MAX_SENTENCE_CHARS = 220

    /** Small, very common Indonesian/English words that make useless blanks. */
    private val STOP = setOf(
        "yang", "dan", "di", "ke", "dari", "dengan", "untuk", "pada", "adalah", "ini", "itu", "dalam",
        "tidak", "akan", "juga", "atau", "karena", "oleh", "sebagai", "dapat", "bisa", "lebih", "sangat",
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "which", "into", "their",
    )

    /** Splits OCR text into sentences, dropping fragments and page furniture. */
    fun sentences(text: String): List<String> =
        text.replace(Regex("-\\s*\\n\\s*"), "") // words hyphenated across lines
            .replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { s -> s.length <= MAX_SENTENCE_CHARS && words(s).size >= MIN_SENTENCE_WORDS && s.count { it.isLetter() } > s.length / 2 }

    private fun words(s: String) = s.split(' ').filter { it.isNotBlank() }

    private fun core(word: String) = word.trim { !it.isLetterOrDigit() }

    /**
     * Picks the most "content-like" word: a number, a capitalised term in mid-sentence, or the
     * longest non-stop word (length ≥ 5). Deterministic for a given [rnd] seed.
     */
    fun make(text: String, rnd: Random, max: Int = 2): List<Pair<String, String>> {
        val candidates = sentences(text).mapNotNull { s ->
            val ws = words(s)
            val scored = ws.mapIndexedNotNull { i, w ->
                val c = core(w)
                if (c.length < 4 || c.lowercase() in STOP) return@mapIndexedNotNull null
                // A word that appears twice would give its own answer away.
                if (ws.count { core(it).equals(c, ignoreCase = true) } > 1) return@mapIndexedNotNull null
                val score = when {
                    c.any { it.isDigit() } -> 30 + c.length
                    i > 0 && c.first().isUpperCase() -> 20 + c.length
                    c.length >= 5 -> c.length
                    else -> null
                } ?: return@mapIndexedNotNull null
                Triple(i, c, score)
            }
            val best = scored.maxByOrNull { it.third } ?: return@mapNotNull null
            val (idx, answer, _) = best
            val prompt = ws.mapIndexed { i, w -> if (i == idx) w.replace(answer, BLANK) else w }.joinToString(" ")
            prompt to answer
        }
        return candidates.shuffled(rnd).distinctBy { it.second.lowercase() }.take(max)
    }

    /** Case, accents and punctuation don't matter; one typo is forgiven in words of 5+ letters. */
    fun isCorrect(given: String, answer: String): Boolean {
        val a = norm(answer)
        val g = norm(given)
        if (g.isEmpty()) return false
        if (g == a) return true
        return a.length >= 5 && levenshtein(g, a) <= 1
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** Leitner-style spacing: right → next box (longer wait), wrong → back to the first box. */
object Spacing {
    val INTERVALS = intArrayOf(1, 3, 7, 14, 30)

    fun answer(card: QuizCard, correct: Boolean, today: Int): QuizCard =
        if (correct) {
            val box = (card.box + 1).coerceAtMost(INTERVALS.size - 1)
            card.copy(box = box, dueDay = today + INTERVALS[box], correct = card.correct + 1)
        } else {
            card.copy(box = 0, dueDay = today + INTERVALS[0], wrong = card.wrong + 1)
        }

    fun due(cards: List<QuizCard>, today: Int, max: Int = 5): List<QuizCard> =
        cards.filter { it.dueDay <= today }.sortedWith(compareBy({ it.dueDay }, { it.box }, { it.id })).take(max)

    /** Share of answers that were right, as a simple "how much stays" score. */
    fun retention(cards: List<QuizCard>): Float? {
        val total = cards.sumOf { it.correct + it.wrong }
        return if (total == 0) null else cards.sumOf { it.correct }.toFloat() / total
    }
}

/**
 * Photographed pages: tells whether a new photo shows a new page (reading progressed) rather
 * than the same page again, by comparing the words on both.
 */
object PageProgress {
    fun wordSet(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    fun similarity(a: String, b: String): Float {
        val x = wordSet(a)
        val y = wordSet(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        return (x intersect y).size.toFloat() / (x union y).size
    }

    /** Enough text to be a page of a book, not a wall or a blank sheet. */
    fun looksLikePage(text: String): Boolean = wordSet(text).size >= 25

    fun isNewPage(previous: String?, current: String): Boolean =
        looksLikePage(current) && (previous == null || similarity(previous, current) < 0.5f)

    /** A page number printed alone on a line (top or bottom), if any. */
    fun pageNumber(text: String): Int? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val edge = (lines.take(2) + lines.takeLast(2))
        return edge.firstNotNullOfOrNull { l -> l.takeIf { it.matches(Regex("\\d{1,4}")) }?.toInt() }
    }
}

object QuizCodec {
    private const val VERSION = "quiz-v1"

    fun encode(cards: List<QuizCard>): String = buildString {
        append(VERSION).append('\n')
        for (c in cards) {
            append(listOf(c.id, c.habitId, esc(c.prompt), esc(c.answer), c.createdDay, c.box, c.dueDay, c.correct, c.wrong).joinToString("\t"))
            append('\n')
        }
    }

    fun decode(raw: String?): List<QuizCard> {
        if (raw.isNullOrEmpty()) return emptyList()
        val lines = raw.split('\n')
        if (lines.first() != VERSION) return emptyList()
        val seen = HashSet<Long>()
        return lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 9) return@mapNotNull null
            try {
                QuizCard(
                    id = f[0].toLong(), habitId = f[1].toLong(), prompt = unesc(f[2]).take(400), answer = unesc(f[3]).take(80),
                    createdDay = f[4].toInt(), box = f[5].toInt().coerceIn(-1, Spacing.INTERVALS.size - 1), dueDay = f[6].toInt(),
                    correct = f[7].toInt().coerceAtLeast(0), wrong = f[8].toInt().coerceAtLeast(0),
                ).takeIf { it.prompt.contains(Cloze.BLANK) && it.answer.isNotBlank() }
            } catch (e: NumberFormatException) {
                null
            }
        }.filter { seen.add(it.id) }.takeLast(MAX_CARDS)
    }

    const val MAX_CARDS = 500

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
