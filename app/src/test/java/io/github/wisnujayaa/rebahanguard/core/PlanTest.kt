package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlanTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun item(id: Long, due: Long? = null, important: Boolean = false, done: Long? = null, title: String = "T$id") =
        PlanItem(id = id, title = title, dueWallMs = due, important = important, doneAtMs = done, createdAtMs = id)

    @Test
    fun deadlineWithinADay_isUrgent_evenIfNotImportant() {
        assertEquals(Bucket.URGENT, Plan.bucket(item(1, due = now + 4 * hour), now))
        assertEquals(Bucket.URGENT, Plan.bucket(item(1, due = now + 24 * hour), now))
        assertEquals(Bucket.LATER, Plan.bucket(item(1, due = now + 24 * hour + 1), now))
    }

    @Test
    fun overdue_staysUrgent() {
        assertEquals(Bucket.URGENT, Plan.bucket(item(1, due = now - 3 * hour), now))
    }

    @Test
    fun importantWithoutDeadline_isImportantNotUrgent() {
        assertEquals(Bucket.IMPORTANT, Plan.bucket(item(1, important = true), now))
        assertEquals(Bucket.IMPORTANT, Plan.bucket(item(1, due = now + 72 * hour, important = true), now))
        assertEquals(Bucket.LATER, Plan.bucket(item(1), now))
    }

    @Test
    fun done_winsOverEverything() {
        assertEquals(Bucket.DONE, Plan.bucket(item(1, due = now - hour, important = true, done = now), now))
    }

    @Test
    fun grouped_sortsByDeadlineThenCreation() {
        val items = listOf(item(3, due = now + 5 * hour), item(1, due = now + hour), item(2, due = now + 5 * hour))
        val urgent = Plan.grouped(items, now).getValue(Bucket.URGENT)
        assertEquals(listOf(1L, 2L, 3L), urgent.map { it.id })
    }

    @Test
    fun grouped_hasEveryBucket_evenWhenEmpty() {
        val g = Plan.grouped(emptyList(), now)
        assertEquals(Bucket.entries.toSet(), g.keys)
        assertTrue(g.values.all { it.isEmpty() })
    }

    @Test
    fun mostUrgent_isNearestOpenDeadline() {
        val items = listOf(item(1, due = now + 5 * hour), item(2, due = now + hour), item(3, due = now + 30 * 60_000, done = now))
        assertEquals(2L, Plan.mostUrgent(items, now)?.id)
        assertNull(Plan.mostUrgent(listOf(item(1, important = true)), now))
    }

    @Test
    fun lockMessage_namesTheTaskAndTimeLeft() {
        val msg = Plan.lockMessage(item(1, due = now + 4 * hour + 12 * 60_000, title = "Laporan DDP2"), now)
        assertTrue(msg, msg.contains("Laporan DDP2"))
        assertTrue(msg, msg.contains("4 j 12 m lagi"))
        val late = Plan.lockMessage(item(1, due = now - 2 * hour, title = "Kuis"), now)
        assertTrue(late, late.contains("lewat deadline 2 j"))
    }

    @Test
    fun formatDuration() {
        assertEquals("kurang dari 1 m", Plan.formatDuration(30_000))
        assertEquals("38 m", Plan.formatDuration(38 * 60_000L))
        assertEquals("2 j", Plan.formatDuration(2 * hour))
        assertEquals("2 hari 3 j", Plan.formatDuration(51 * hour))
        assertEquals("kurang dari 1 m", Plan.formatDuration(-5))
    }

    @Test
    fun sanitizeTitle() {
        assertEquals("Cuci baju", Plan.sanitizeTitle("  Cuci \n\t baju  "))
        assertNull(Plan.sanitizeTitle("   \n "))
        assertEquals(Plan.MAX_TITLE, Plan.sanitizeTitle("x".repeat(500))!!.length)
        assertEquals("ab", Plan.sanitizeTitle("a\u0000b"))
    }

    @Test
    fun nextId_isUniqueAndIncreasing() {
        val items = listOf(item(now + 50))
        assertEquals(now + 51, Plan.nextId(items, now))
        assertEquals(now, Plan.nextId(emptyList(), now))
    }

    @Test
    fun prune_neverDropsOpenItems_andKeepsRecentDone() {
        val open = (1L..10L).map { item(it) }
        val done = (100L..200L).map { item(it, done = it) }
        val pruned = Plan.prune(open + done)
        assertTrue(pruned.containsAll(open))
        assertEquals(Plan.KEEP_DONE, pruned.count { it.isDone })
        assertTrue(pruned.filter { it.isDone }.all { it.doneAtMs!! > 200 - Plan.KEEP_DONE })
    }
}

class PlanCodecTest {
    private val items = listOf(
        PlanItem(1, "Laporan DDP2", PlanCategory.KULIAH, dueWallMs = 1_800_000_000_000, important = true, createdAtMs = 5),
        PlanItem(2, "Baca bab 4 \\ Matdis", PlanCategory.BELAJAR, createdAtMs = 6),
        PlanItem(3, "Cuci baju", PlanCategory.RUMAH, doneAtMs = 1_800_000_100_000, createdAtMs = 7),
    )

    @Test
    fun roundTrip() {
        assertEquals(items, PlanCodec.decode(PlanCodec.encode(items)))
    }

    @Test
    fun emptyAndMissing() {
        assertEquals(emptyList<PlanItem>(), PlanCodec.decode(null))
        assertEquals(emptyList<PlanItem>(), PlanCodec.decode(""))
        assertEquals(emptyList<PlanItem>(), PlanCodec.decode(PlanCodec.encode(emptyList())))
    }

    @Test
    fun unknownVersion_isIgnoredNotCrashed() {
        assertEquals(emptyList<PlanItem>(), PlanCodec.decode("v9\n1\tx\tKULIAH\t\t0\t\t0\n"))
    }

    @Test
    fun corruptedLines_areSkipped() {
        val raw = PlanCodec.encode(items) + "garbage\nabc\tdef\n9\t\tKULIAH\t\t0\t\t0\n"
        assertEquals(items, PlanCodec.decode(raw))
    }

    @Test
    fun unknownCategory_fallsBackToLainnya() {
        val decoded = PlanCodec.decode("v1\n1\tx\tSPACE\t\t0\t\t0\n")
        assertEquals(PlanCategory.LAINNYA, decoded.single().category)
    }

    @Test
    fun duplicateIds_keepFirst() {
        val decoded = PlanCodec.decode("v1\n1\ta\tKULIAH\t\t0\t\t0\n1\tb\tKULIAH\t\t0\t\t0\n")
        assertEquals("a", decoded.single().title)
    }

    @Test
    fun fuzz_randomInput_neverThrows() {
        val rnd = Random(42)
        val alphabet = "v1\t\n\\0123456789-KULIAHx ".toCharArray()
        repeat(3_000) {
            val s = String(CharArray(rnd.nextInt(0, 200)) { alphabet[rnd.nextInt(alphabet.size)] })
            val decoded = PlanCodec.decode(s)
            assertTrue(decoded.size <= Plan.MAX_ITEMS)
            assertTrue(decoded.all { it.title.isNotBlank() })
        }
    }

    @Test
    fun fuzz_randomItems_roundTrip() {
        val rnd = Random(7)
        repeat(300) {
            val list = (0 until rnd.nextInt(0, 20)).map { i ->
                PlanItem(
                    id = i.toLong() + 1,
                    title = Plan.sanitizeTitle(String(CharArray(rnd.nextInt(1, 30)) { "ab \\c".random(rnd) })) ?: "x",
                    category = PlanCategory.entries.random(rnd),
                    dueWallMs = if (rnd.nextBoolean()) rnd.nextLong(1, Long.MAX_VALUE / 2) else null,
                    important = rnd.nextBoolean(),
                    doneAtMs = if (rnd.nextBoolean()) rnd.nextLong(1, Long.MAX_VALUE / 2) else null,
                    createdAtMs = rnd.nextLong(0, Long.MAX_VALUE / 2),
                )
            }
            assertEquals(list, PlanCodec.decode(PlanCodec.encode(list)))
        }
    }

    @Test
    fun titlesWithNewlines_cannotForgeExtraItems() {
        val evil = PlanItem(1, "a\n2\tforged\tKULIAH\t\t0\t\t0", PlanCategory.KULIAH)
        val decoded = PlanCodec.decode(PlanCodec.encode(listOf(evil)))
        assertEquals(1, decoded.size)
        assertFalse(decoded.any { it.title == "forged" })
    }
}
