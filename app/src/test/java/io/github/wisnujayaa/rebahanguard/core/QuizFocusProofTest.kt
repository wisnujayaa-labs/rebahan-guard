package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClozeTest {
    private val page = """
        12
        Sebuah graf terhubung memiliki sirkuit Euler jika dan hanya jika setiap simpulnya berderajat genap.
        Teorema ini pertama kali dibuktikan oleh Leonhard Euler pada tahun 1736 ketika membahas jembatan Königsberg.
        Lin-
        tasan Euler berbeda dengan sirkuit Euler karena tidak harus kembali ke simpul awal.
        Ok.
    """.trimIndent()

    @Test
    fun sentences_dropFragmentsAndJoinHyphenation() {
        val s = Cloze.sentences(page)
        assertEquals(3, s.size)
        assertTrue(s.any { it.startsWith("Lintasan Euler") })
        assertFalse(s.any { it == "Ok." })
    }

    @Test
    fun make_blanksAContentWord() {
        val cards = Cloze.make(page, Random(1), max = 3)
        assertEquals(3, cards.size)
        for ((prompt, answer) in cards) {
            assertTrue(prompt, prompt.contains(Cloze.BLANK))
            assertFalse(prompt, prompt.contains(answer))
            assertTrue(answer.length >= 4)
        }
        assertTrue(cards.any { it.second == "1736" }) // numbers are prime candidates
    }

    @Test
    fun make_onGarbage_returnsNothing() {
        assertTrue(Cloze.make("", Random(1)).isEmpty())
        assertTrue(Cloze.make("|||| ~~~ 123 ;;;", Random(1)).isEmpty())
    }

    @Test
    fun grading() {
        assertTrue(Cloze.isCorrect("euler", "Euler"))
        assertTrue(Cloze.isCorrect(" Königsberg ", "Konigsberg"))
        assertTrue(Cloze.isCorrect("simpl", "simpul")) // one typo forgiven
        assertFalse(Cloze.isCorrect("simp", "simpul"))
        assertFalse(Cloze.isCorrect("1737", "1736")) // short: no typo allowance
        assertFalse(Cloze.isCorrect("", "graf"))
        assertEquals(3, Cloze.levenshtein("kitten", "sitting"))
    }

    @Test
    fun fuzz_neverThrows() {
        val rnd = Random(5)
        val alphabet = "abc DEF 12.!?\n-ÄÖ".toCharArray()
        repeat(2_000) {
            val text = String(CharArray(rnd.nextInt(0, 400)) { alphabet[rnd.nextInt(alphabet.size)] })
            for ((p, a) in Cloze.make(text, rnd)) {
                assertTrue(p.contains(Cloze.BLANK))
                assertTrue(a.isNotBlank())
            }
        }
    }
}

class SpacingTest {
    private val card = QuizCard(1, 10, "x ____ y", "z", createdDay = 100)

    @Test
    fun rightAnswers_spaceOut_wrongResets() {
        var c = Spacing.answer(card, true, 100)
        assertEquals(101, c.dueDay)
        c = Spacing.answer(c, true, 101)
        assertEquals(104, c.dueDay)
        c = Spacing.answer(c, true, 104)
        assertEquals(111, c.dueDay)
        c = Spacing.answer(c, false, 111)
        assertEquals(0, c.box)
        assertEquals(112, c.dueDay)
        repeat(20) { c = Spacing.answer(c, true, c.dueDay) }
        assertEquals(Spacing.INTERVALS.size - 1, c.box) // capped
    }

    @Test
    fun due_andRetention() {
        val cards = listOf(card, card.copy(id = 2, dueDay = 105), card.copy(id = 3, dueDay = 99))
        assertEquals(listOf(3L, 1L), Spacing.due(cards, 100).map { it.id })
        assertNull(Spacing.retention(cards))
        assertEquals(0.75f, Spacing.retention(listOf(card.copy(correct = 3, wrong = 1)))!!, 0.001f)
    }

    @Test
    fun codec_roundTripAndRejectsJunk() {
        val cards = listOf(card, card.copy(id = 2, prompt = "tab\there ____", answer = "a\\b", box = 2, correct = 4))
        assertEquals(cards, QuizCodec.decode(QuizCodec.encode(cards)))
        assertTrue(QuizCodec.decode("quiz-v1\n1\t1\tno blank\tx\t0\t0\t0\t0\t0\n").isEmpty())
        assertTrue(QuizCodec.decode("nope").isEmpty())
    }
}

class PageProgressTest {
    private val pageA = (1..40).joinToString(" ") { "kata$it" }
    private val pageB = (30..70).joinToString(" ") { "kata$it" }

    @Test
    fun newPage_vsSamePage() {
        assertTrue(PageProgress.isNewPage(null, pageA))
        assertFalse(PageProgress.isNewPage(pageA, pageA))
        assertTrue(PageProgress.isNewPage(pageA, pageB))
        assertFalse(PageProgress.isNewPage(null, "dinding putih"))
    }

    @Test
    fun pageNumber() {
        assertEquals(12, PageProgress.pageNumber("12\nIsi halaman\nlagi"))
        assertEquals(87, PageProgress.pageNumber("Isi\nlagi\n87"))
        assertNull(PageProgress.pageNumber("Bab 3\nTahun 1736 adalah"))
    }
}

class FocusRulesTest {
    @Test
    fun neverBlocksPhoneOrSelf() {
        val blocked = setOf("com.instagram.android", "com.android.dialer", "me.app")
        assertTrue(FocusRules.isBlocked("com.instagram.android", blocked, "me.app"))
        assertFalse(FocusRules.isBlocked("com.android.dialer", blocked, "me.app"))
        assertFalse(FocusRules.isBlocked("me.app", blocked, "me.app"))
        assertFalse(FocusRules.isBlocked(null, blocked, "me.app"))
        assertFalse(FocusRules.isBlocked("org.wikipedia", blocked, "me.app"))
    }

    @Test
    fun passes_twoPerDay_afterWaiting() {
        var b = PassBook()
        assertNull(b.take(today = 5, waitedMs = 10_000, nowElapsedMs = 0)) // didn't wait
        b = b.take(5, 30_000, 1_000)!!
        assertTrue(b.isActive(1_000 + 4 * 60_000))
        assertFalse(b.isActive(1_000 + 6 * 60_000))
        b = b.take(5, 30_000, 10_000_000)!!
        assertNull(b.take(5, 30_000, 20_000_000))
        assertEquals(0, b.left(5))
        assertEquals(2, b.left(6)) // a new day
        assertNotNull(b.take(6, 30_000, 30_000_000))
    }

    @Test
    fun pass_withBogusFutureEnd_isNotActive() {
        assertFalse(PassBook(1, 1, activeUntilElapsedMs = Long.MAX_VALUE).isActive(0))
    }

    @Test
    fun focusTime() {
        assertFalse(FocusRules.isFocusTime(false, false, false, false))
        assertTrue(FocusRules.isFocusTime(false, true, false, false))
        assertTrue(FocusRules.isFocusTime(false, false, true, false))
    }

    @Test
    fun attempts() {
        val a = AttemptCount().add(3).add(3)
        assertEquals(2, a.on(3))
        assertEquals(0, a.on(4))
        assertEquals(1, a.add(4).on(4))
    }
}

class ActivityProofTest {
    @Test
    fun cadence() {
        assertEquals(StepProof.Kind.RUN, StepProof.kind(4_200, 28 * 60_000L)) // 150/min
        assertEquals(StepProof.Kind.WALK, StepProof.kind(3_000, 30 * 60_000L)) // 100/min
        assertEquals(StepProof.Kind.NONE, StepProof.kind(500, 30 * 60_000L))
        assertEquals(StepProof.Kind.NONE, StepProof.kind(200, 30_000)) // too short to judge
    }

    @Test
    fun stepDelta_handlesReboot() {
        assertEquals(0, StepProof.delta(null, 5000f))
        assertEquals(120, StepProof.delta(5000f, 5120f))
        assertEquals(30, StepProof.delta(5000f, 30f)) // counter reset by a reboot
        assertEquals(0, StepProof.delta(5000f, Float.NaN))
    }

    private val perpus = Place(1, "Perpustakaan UI", -6.3653, 106.8290, 25f, setOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02", "aa:bb:cc:dd:ee:03"))

    @Test
    fun place_byWifi_evenWithoutGps() {
        assertTrue(PlaceRules.isAt(perpus, null, null, null, setOf("AA:BB:CC:DD:EE:01", "aa:bb:cc:dd:ee:02", "11:22:33:44:55:66")))
        assertFalse(PlaceRules.isAt(perpus, null, null, null, setOf("11:22:33:44:55:66")))
    }

    @Test
    fun place_byGps_withAccuracyAwareRadius() {
        assertTrue(PlaceRules.isAt(perpus, -6.3656, 106.8292, 20f, emptySet())) // ~40 m away
        assertFalse(PlaceRules.isAt(perpus, -6.3700, 106.8290, 20f, emptySet())) // ~520 m away
        assertTrue(PlaceRules.isAt(perpus, -6.3700, 106.8290, 400f, emptySet())) // very unsure fix
        assertFalse(PlaceRules.isAt(perpus, Double.NaN, 106.8, 10f, emptySet()))
    }

    @Test
    fun bssids_areValidatedAndPrivacyMacsDropped() {
        assertEquals(setOf("aa:bb:cc:dd:ee:01"), PlaceRules.normalizeBssids(listOf("AA:BB:CC:DD:EE:01", "02:00:00:00:00:00", "junk")))
    }

    @Test
    fun distance() {
        assertTrue(kotlin.math.abs(PlaceRules.distanceM(0.0, 0.0, 1.0, 0.0) - 111_195.0) < 200.0)
    }

    @Test
    fun evidenceReport() {
        val dream = Dream(1, "S2", "")
        val run = Habit(10, 1, "Lari", HabitUnit.STEPS, 3000, proof = Proof.MOVE)
        val wash = Habit(11, 1, "Cuci", HabitUnit.TIMES, 1, proof = Proof.HONEST)
        var b = DreamBook(listOf(dream), listOf(run, wash))
        b = DreamRules.addProgress(b, 10, 100, 4000, 3)
        b = DreamRules.addProgress(b, 10, 101, 1000, 3) // not done
        b = DreamRules.addProgress(b, 11, 101, 1, 1)
        assertEquals(0.5f, EvidenceReport.provenShare(b, 95, 101)!!, 0.001f)
        val s = EvidenceReport.summary(b, 101, "Raka")
        assertTrue(s, s.contains("Lari: 1 hari (Sensor)"))
        assertTrue(s, s.contains("Cuci: 1 hari (Jujur)"))
        assertTrue(s, s.contains("50%"))
    }
}
