package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DreamRulesTest {
    private val day = 20_000 // some day
    private val dream = Dream(1, "Kuliah S2 di luar negeri", "Aku ingin membuktikan bahwa aku bisa.", createdDay = day - 10)
    private val habit = Habit(10, dreamId = 1, title = "Listening", unit = HabitUnit.MINUTES, dailyTarget = 30,
        windowStart = 19 * 60 + 30, windowEnd = 21 * 60, proof = Proof.DESK, createdDay = day - 10)
    private val book = DreamBook(dreams = listOf(dream), habits = listOf(habit))

    @Test
    fun atMostThreeActiveDreams() {
        var b = DreamBook()
        for (i in 1..3) {
            val r = DreamRules.apply(b, DreamRules.Change.AddDream(Dream(i.toLong(), "D$i", "")), day)
            assertNull(r.rejected)
            b = r.book
        }
        val r = DreamRules.apply(b, DreamRules.Change.AddDream(Dream(4, "D4", "")), day)
        assertNotNull(r.rejected)
        assertEquals(3, r.book.dreams.size)
        // an archived dream frees a slot
        val archived = b.copy(dreams = b.dreams.map { if (it.id == 1L) it.copy(archived = true) else it })
        assertTrue(DreamRules.canAddDream(archived))
    }

    @Test
    fun lowerTarget_waitsUntilTomorrow() {
        val r = DreamRules.apply(book, DreamRules.Change.EditHabit(habit.copy(dailyTarget = 10)), day)
        assertTrue(r.deferred)
        assertEquals(30, r.book.habit(10)!!.dailyTarget) // still 30 today
        assertEquals(10, DreamRules.settle(r.book, day + 1).habit(10)!!.dailyTarget)
        assertEquals(30, DreamRules.settle(r.book, day).habit(10)!!.dailyTarget)
    }

    @Test
    fun higherTarget_appliesNow() {
        val r = DreamRules.apply(book, DreamRules.Change.EditHabit(habit.copy(dailyTarget = 45)), day)
        assertFalse(r.deferred)
        assertEquals(45, r.book.habit(10)!!.dailyTarget)
    }

    @Test
    fun whatCountsAsLighter() {
        assertTrue(DreamRules.isLighter(habit, habit.copy(days = habit.days and 1.inv())))
        assertTrue(DreamRules.isLighter(habit, habit.copy(proof = Proof.HONEST)))
        assertTrue(DreamRules.isLighter(habit, habit.copy(windowStart = null, windowEnd = null)))
        assertTrue(DreamRules.isLighter(habit, habit.copy(windowEnd = 20 * 60)))
        assertTrue(DreamRules.isLighter(habit, habit.copy(unit = HabitUnit.PAGES)))
        assertFalse(DreamRules.isLighter(habit, habit.copy(title = "Listening IELTS")))
        assertFalse(DreamRules.isLighter(habit, habit.copy(windowEnd = 22 * 60)))
        assertFalse(DreamRules.isLighter(habit.copy(windowStart = null, windowEnd = null), habit))
        assertTrue(DreamRules.isLighter(dream, dream.copy(why = "")))
        assertTrue(DreamRules.isLighter(dream, dream.copy(archived = true)))
    }

    @Test
    fun deleteHabit_waitsUntilTomorrow_unlessCreatedToday() {
        val r = DreamRules.apply(book, DreamRules.Change.DeleteHabit(10), day)
        assertTrue(r.deferred)
        assertNotNull(r.book.habit(10))
        assertNull(DreamRules.settle(r.book, day + 1).habit(10))

        val fresh = book.copy(habits = listOf(habit.copy(createdDay = day)))
        val r2 = DreamRules.apply(fresh, DreamRules.Change.DeleteHabit(10), day)
        assertFalse(r2.deferred)
        assertNull(r2.book.habit(10))
    }

    @Test
    fun editsOnTheDayOfCreation_applyNow() {
        val fresh = book.copy(dreams = listOf(dream.copy(createdDay = day)))
        val r = DreamRules.apply(fresh, DreamRules.Change.EditDream(dream.copy(why = "salah ketik diperbaiki")), day)
        assertFalse(r.deferred)
        assertEquals("salah ketik diperbaiki", r.book.dream(1)!!.why)
    }

    @Test
    fun editingWhy_ofAnOldDream_waits() {
        val r = DreamRules.apply(book, DreamRules.Change.EditDream(dream.copy(why = "")), day)
        assertTrue(r.deferred)
        assertEquals(dream.why, r.book.dream(1)!!.why)
    }

    @Test
    fun newerPendingReplacesOlder_forSameTarget() {
        var b = DreamRules.apply(book, DreamRules.Change.EditHabit(habit.copy(dailyTarget = 20)), day).book
        b = DreamRules.apply(b, DreamRules.Change.EditHabit(habit.copy(dailyTarget = 15)), day).book
        assertEquals(1, b.pending.size)
        assertEquals(15, DreamRules.settle(b, day + 1).habit(10)!!.dailyTarget)
    }

    @Test
    fun cannotMoveAHabitToAnotherDreamByEditing() {
        val other = Dream(2, "Sehat", "", createdDay = day - 5)
        val b = book.copy(dreams = book.dreams + other)
        val r = DreamRules.apply(b, DreamRules.Change.EditHabit(habit.copy(dreamId = 2, dailyTarget = 40)), day)
        assertEquals(1L, r.book.habit(10)!!.dreamId)
    }

    @Test
    fun habitForArchivedOrMissingDream_isRejected() {
        val archived = book.copy(dreams = listOf(dream.copy(archived = true)))
        assertNotNull(DreamRules.apply(archived, DreamRules.Change.AddHabit(habit.copy(id = 11)), day).rejected)
        assertNotNull(DreamRules.apply(book, DreamRules.Change.AddHabit(habit.copy(id = 11, dreamId = 99)), day).rejected)
    }

    @Test
    fun progress_andDone() {
        var b = DreamRules.addProgress(book, 10, day, 20, strength = 3)
        assertFalse(DreamRules.isDone(b, habit, day))
        b = DreamRules.addProgress(b, 10, day, 15, strength = 1)
        assertTrue(DreamRules.isDone(b, habit, day))
        assertEquals(35, DreamRules.amount(b, 10, day))
        assertEquals(3, b.log.single().strength) // the strongest proof of the day is kept
        assertEquals(b, DreamRules.addProgress(b, 10, day, -5, 3)) // never negative
        assertEquals(b, DreamRules.addProgress(b, 999, day, 5, 3)) // unknown habit ignored
    }

    @Test
    fun window_wrapsPastMidnight() {
        val late = habit.copy(windowStart = 23 * 60, windowEnd = 60)
        assertTrue(DreamRules.inWindow(late, 23 * 60 + 30))
        assertTrue(DreamRules.inWindow(late, 30))
        assertFalse(DreamRules.inWindow(late, 2 * 60))
        assertFalse(DreamRules.inWindow(habit.copy(windowStart = null, windowEnd = null), 20 * 60))
    }

    @Test
    fun weekdays() {
        assertEquals(3, DayClock.weekdayOf(0)) // 1970-01-01 was a Thursday (0 = Monday)
        val weekdaysOnly = habit.copy(days = 0b0011111)
        val saturday = (0..6).map { day + it }.first { DayClock.weekdayOf(it) == 5 }
        assertFalse(DreamRules.isScheduled(weekdaysOnly, saturday))
        assertTrue(DreamRules.isScheduled(weekdaysOnly, saturday + 2)) // Monday
    }

    @Test
    fun dayStartsAt4am() {
        val offset = 7 * 3_600_000 // WIB
        val midnight = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L + offset, DayClock.DAY_MS) // local 00:00
        val d = DayClock.dayOf(midnight + 3 * 3_600_000, offset) // 03:00 still yesterday
        assertEquals(d + 1, DayClock.dayOf(midnight + 5 * 3_600_000, offset))
        assertEquals(d, DayClock.dayOf(midnight - 3_600_000, offset)) // 23:00 the evening before
    }

    @Test
    fun behindToday_putsOpenWindowFirst() {
        val other = Habit(11, 1, "Baca", HabitUnit.PAGES, 20, createdDay = day - 10)
        val b = book.copy(habits = listOf(other, habit))
        assertEquals(listOf(10L, 11L), DreamRules.behindToday(b, day, 20 * 60).map { it.id })
        val done = DreamRules.addProgress(b, 10, day, 30, 3)
        assertEquals(listOf(11L), DreamRules.behindToday(done, day, 20 * 60).map { it.id })
    }

    @Test
    fun streak_countsCompletedDays() {
        var b = book
        for (d in day - 3 until day) b = DreamRules.addProgress(b, 10, d, 30, 3)
        assertEquals(3, DreamRules.streak(b, 1, day))
        b = DreamRules.addProgress(b, 10, day, 30, 3)
        assertEquals(4, DreamRules.streak(b, 1, day))
        assertEquals(0, DreamRules.streak(book, 1, day))
    }

    @Test
    fun streak_skipsDaysOff() {
        val weekdaysOnly = habit.copy(days = 0b0011111)
        val monday = (0..6).map { day + it }.first { DayClock.weekdayOf(it) == 0 }
        var b = book.copy(habits = listOf(weekdaysOnly))
        for (d in monday - 7 until monday) if (DreamRules.isScheduled(weekdaysOnly, d)) b = DreamRules.addProgress(b, 10, d, 30, 3)
        assertEquals(5, DreamRules.streak(b, 1, monday))
    }

    @Test
    fun lockCopy_usesTheUsersOwnWords() {
        val copy = DreamRules.lockCopy(book, day, 20 * 60, 0)!!
        assertTrue(copy.body, copy.body.contains("0 dari 30 menit"))
        assertTrue(copy.body, copy.body.contains("jam targetnya"))
        assertEquals(dream.why, copy.quote)
        assertNull(DreamRules.lockCopy(DreamBook(), day, 0, 0))
    }

    @Test
    fun fuzz_randomChanges_keepInvariants() {
        val rnd = Random(3)
        var b = DreamBook()
        var today = day
        repeat(3_000) { step ->
            val change = when (rnd.nextInt(5)) {
                0 -> DreamRules.Change.AddDream(Dream(rnd.nextLong(1, 8), "D", "w"))
                1 -> DreamRules.Change.EditDream(Dream(rnd.nextLong(1, 8), "D", if (rnd.nextBoolean()) "w" else "x", archived = rnd.nextInt(10) == 0))
                2 -> DreamRules.Change.AddHabit(Habit(rnd.nextLong(1, 15), rnd.nextLong(1, 8), "H", dailyTarget = rnd.nextInt(1, 60)))
                3 -> DreamRules.Change.EditHabit(Habit(rnd.nextLong(1, 15), rnd.nextLong(1, 8), "H", dailyTarget = rnd.nextInt(1, 60)))
                else -> DreamRules.Change.DeleteHabit(rnd.nextLong(1, 15))
            }
            b = DreamRules.apply(b, change, today).book
            if (step % 50 == 0) {
                today++
                b = DreamRules.settle(b, today)
            }
            assertTrue(b.activeDreams.size <= DreamRules.MAX_ACTIVE_DREAMS)
            assertEquals(b.dreams.size, b.dreams.map { it.id }.toSet().size)
            assertEquals(b.habits.size, b.habits.map { it.id }.toSet().size)
            assertTrue(b.pending.all { it.effectiveDay > today })
            val keys = b.pending.map { it.dream?.id?.let { id -> "d$id" } ?: "h${it.habit?.id ?: it.deleteHabitId}" }
            assertEquals(keys.size, keys.toSet().size)
        }
    }
}

class DreamCodecTest {
    private val book = DreamBook(
        dreams = listOf(
            Dream(1, "Kuliah S2", "Aku ingin\tmembuktikan \\ sesuatu", "IELTS 7.0", 1_900_000_000_000, 5, 100),
            Dream(2, "Sehat", "", archived = true),
        ),
        habits = listOf(
            Habit(10, 1, "Listening", HabitUnit.MINUTES, 30, 0b0011111, 19 * 60, 21 * 60, Proof.DESK, 100),
            Habit(11, 1, "Baca", HabitUnit.PAGES, 20, proof = Proof.PHOTO),
        ),
        log = listOf(HabitDay(10, 101, 25, 3), HabitDay(11, 101, 20, 2)),
        pending = listOf(
            Pending(102, habit = Habit(10, 1, "Listening", HabitUnit.MINUTES, 15, 0b0011111, 19 * 60, 21 * 60, Proof.DESK, 100)),
            Pending(102, deleteHabitId = 11),
            Pending(102, dream = Dream(1, "Kuliah S2", "", "IELTS 7.0", null, 5, 100)),
        ),
    )

    private fun normalized(b: DreamBook) = b.copy(
        dreams = b.dreams.map { it.copy(why = DreamRules.sanitizeText(it.why, DreamRules.MAX_WHY).orEmpty()) },
    )

    @Test
    fun roundTrip() {
        assertEquals(normalized(book), DreamCodec.decode(DreamCodec.encode(book)))
    }

    @Test
    fun emptyAndUnknown() {
        assertEquals(DreamBook(), DreamCodec.decode(null))
        assertEquals(DreamBook(), DreamCodec.decode("dreams-v9\nD\t1\tx\t\t\t\t0\t0\t0"))
    }

    @Test
    fun orphans_areDropped() {
        val raw = "dreams-v1\nH\t10\t99\tX\tMINUTES\t30\t127\t\t\tDESK\t0\nL\t10\t1\t5\t3\nP\t5\tX\t10\n"
        assertEquals(DreamBook(), DreamCodec.decode(raw))
    }

    @Test
    fun badValues_areClampedOrRejected() {
        val raw = "dreams-v1\n" +
            "D\t1\tTitle\t\t\t\t0\t0\t0\n" +
            "H\t10\t1\tX\tWEIRD\t-5\t0\t9999\t30\tLASER\t0\n" +
            "D\t2\t   \t\t\t\t0\t0\t0\n"
        val b = DreamCodec.decode(raw)
        assertEquals(1, b.dreams.size) // blank title rejected
        val h = b.habits.single()
        assertEquals(HabitUnit.TIMES, h.unit)
        assertEquals(1, h.dailyTarget)
        assertEquals(Habit.ALL_DAYS, h.days)
        assertNull(h.windowStart) // 9999 is not a minute of the day
        assertEquals(Proof.HONEST, h.proof)
    }

    @Test
    fun fuzz_neverThrows() {
        val rnd = Random(11)
        val alphabet = "DHLPX\t\n\\01234567-dreams-v1abc".toCharArray()
        repeat(4_000) {
            val s = "dreams-v1\n" + String(CharArray(rnd.nextInt(0, 160)) { alphabet[rnd.nextInt(alphabet.size)] })
            val b = DreamCodec.decode(s)
            assertTrue(b.habits.all { h -> b.dreams.any { it.id == h.dreamId } })
        }
    }
}

class CommitmentTemplatesTest {
    @Test
    fun compose_buildsCleanSentences() {
        val s = CommitmentTemplates.compose("Kuliah S2 di luar negeri", " aku ingin bangga  ", "Waktu tidak kembali.")
        assertEquals("Aku ingin kuliah S2 di luar negeri. aku ingin bangga. Waktu tidak kembali.", s)
        assertEquals("Waktu tidak kembali.", CommitmentTemplates.compose("", "", "Waktu tidak kembali"))
        assertEquals("", CommitmentTemplates.compose(" ", "", ""))
    }

    @Test
    fun everyThemeHasSentences() {
        for (t in CommitmentTemplates.Theme.entries) assertTrue(CommitmentTemplates.WHY.getValue(t).isNotEmpty())
    }

    @Test
    fun noTemplateInsultsTheReader() {
        val all = CommitmentTemplates.WHY.values.flatten() + CommitmentTemplates.COST
        for (s in all) {
            val lower = s.lowercase()
            assertFalse(s, listOf("pemalas", "bodoh", "gagal total", "tidak berguna").any { it in lower })
        }
    }

    @Test
    fun emergencyPhrase_namesTheDream() {
        val p = CommitmentTemplates.emergencyPhrase("Kuliah S2")
        assertTrue(p.contains("Kuliah S2"))
        assertTrue(TypedPhrase(p).wordCount >= 20)
        assertEquals(EmergencyStop.PHRASE, CommitmentTemplates.emergencyPhrase(null))
        assertEquals(EmergencyStop.PHRASE, CommitmentTemplates.emergencyPhrase("  "))
    }
}

class DeskSessionTest {
    private val config = GuardConfig()
    private val upright = Orientation(Pose.UPRIGHT, 10f, 0f)

    private fun face(roll: Float, ratio: Float = 0.12f) = FaceObservation(faceWidthRatio = ratio, rollDeg = roll)

    @Test
    fun judge() {
        assertEquals(DeskVerdict.PRESENT, DeskRules.judge(face(5f), upright, config))
        assertEquals(DeskVerdict.LYING, DeskRules.judge(face(80f), upright, config))
        assertEquals(DeskVerdict.ABSENT, DeskRules.judge(null, upright, config))
        assertEquals(DeskVerdict.ABSENT, DeskRules.judge(face(0f, ratio = 0.02f), upright, config))
        // phone lying flat: head tilt undefined
        assertEquals(DeskVerdict.UNSURE, DeskRules.judge(face(0f), Orientation(Pose.FACE_UP, 80f, Float.NaN), config))
    }

    @Test
    fun gaps_areRandomButBounded() {
        val rnd = Random(1)
        val gaps = (1..5_000).map { DeskRules.nextGapMs(rnd) }
        assertTrue(gaps.all { it in DeskRules.MIN_GAP_MS..DeskRules.MAX_GAP_MS })
        assertTrue(gaps.toSet().size > 1_000) // not a fixed rhythm someone could learn
        val mean = gaps.average()
        assertTrue("mean $mean", mean in 150_000.0..260_000.0)
    }

    @Test
    fun onlyPresentChecksEarnTime() {
        var t = DeskTally(startedMs = 0, targetMs = 30 * 60_000L)
        t = t.record(DeskVerdict.PRESENT, 4 * 60_000L).first
        assertEquals(4, t.creditedMinutes)
        t = t.record(DeskVerdict.ABSENT, 8 * 60_000L).first
        assertEquals(4, t.creditedMinutes)
        t = t.record(DeskVerdict.UNSURE, 10 * 60_000L).first
        t = t.record(DeskVerdict.PRESENT, 13 * 60_000L).first
        assertEquals(7, t.creditedMinutes)
        assertEquals(3, t.checks)
        assertEquals(2f / 3f, t.presence, 0.001f)
    }

    @Test
    fun alarms() {
        var t = DeskTally(0, 60_000L * 60)
        val (t1, a1) = t.record(DeskVerdict.ABSENT, 1_000)
        assertEquals(DeskTally.Action.WARN_ABSENT, a1)
        val (t2, a2) = t1.record(DeskVerdict.ABSENT, 2_000)
        assertEquals(DeskTally.Action.ALARM_ABSENT, a2)
        val (_, a3) = t2.record(DeskVerdict.PRESENT, 3_000)
        assertEquals(DeskTally.Action.CLEAR, a3)
        t = t2
        assertEquals(DeskTally.Action.ALARM_LYING, t.record(DeskVerdict.LYING, 4_000).second)
    }

    @Test
    fun creditCappedAtTarget_andHugeGapsIgnored() {
        var t = DeskTally(0, 10 * 60_000L)
        t = t.record(DeskVerdict.PRESENT, 3 * 3_600_000L).first // phone was off for hours
        assertTrue(t.creditedMs <= DeskRules.MAX_GAP_MS + DeskRules.RECHECK_MS)
        repeat(10) { i -> t = t.record(DeskVerdict.PRESENT, 3 * 3_600_000L + (i + 1) * 5 * 60_000L).first }
        assertEquals(10, t.creditedMinutes)
        assertTrue(t.isComplete)
    }
}
