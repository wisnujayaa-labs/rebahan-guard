package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentTest {
    private val hour = Commitment.HOUR_MS
    private val wall0 = 1_800_000_000_000L // some date in 2027
    private val c = Commitment.start(nowWallMs = wall0, nowElapsedMs = 50_000, bootCount = 7, hours = 8)

    @Test
    fun activeForTheChosenDuration() {
        assertTrue(c.isActive(wall0 + 7 * hour, 50_000 + 7 * hour, 7))
        assertFalse(c.isActive(wall0 + 8 * hour, 50_000 + 8 * hour, 7))
        assertEquals(hour, c.remainingMs(wall0 + 7 * hour, 50_000 + 7 * hour, 7))
    }

    @Test
    fun movingTheClockForward_doesNotEndItEarly() {
        // User sets the phone's date to next week: the monotonic clock still says 1 hour passed.
        assertTrue(c.isActive(wall0 + 24 * 7 * hour, 50_000 + hour, 7))
        assertEquals(7 * hour, c.remainingMs(wall0 + 24 * 7 * hour, 50_000 + hour, 7))
    }

    @Test
    fun movingTheClockBack_doesNotExtendIt() {
        assertFalse(c.isActive(wall0 - 30 * hour, 50_000 + 9 * hour, 7))
    }

    @Test
    fun afterARestart_fallsBackToTheWallClock() {
        assertTrue(c.isActive(wall0 + 3 * hour, 10_000, 8))
        assertFalse(c.isActive(wall0 + 9 * hour, 10_000, 8))
    }

    @Test
    fun afterARestartWithTheClockMovedBack_keepsTheFullDuration() {
        assertEquals(8 * hour, c.remainingMs(wall0 - hour, 10_000, 8))
    }

    @Test
    fun remaining_isAlwaysWithinZeroAndDuration() {
        val rnd = kotlin.random.Random(5)
        repeat(50_000) {
            val r = c.remainingMs(
                wall0 + rnd.nextLong(-100 * hour, 100 * hour),
                rnd.nextLong(0, 100 * hour),
                rnd.nextInt(5, 10),
            )
            assertTrue(r in 0..c.durationMs)
        }
    }

    @Test
    fun hours_areClamped() {
        assertEquals(Commitment.MIN_HOURS * hour, Commitment.start(wall0, 0, 1, -5).durationMs)
        assertEquals(Commitment.MAX_HOURS * hour, Commitment.start(wall0, 0, 1, 1_000).durationMs)
    }

    @Test
    fun corruptedStoredCommitments_areRejected() {
        assertNull(Commitment.sanitize(null))
        assertNull(Commitment.sanitize(c.copy(durationMs = 100 * hour)))
        assertNull(Commitment.sanitize(c.copy(durationMs = 0)))
        assertNull(Commitment.sanitize(c.copy(startWallMs = -1)))
        assertEquals(c, Commitment.sanitize(c))
    }
}

class EmergencyStopTest {
    private val alone = EmergencyStopRules.WAIT_ALONE_MS
    private val partner = EmergencyStopRules.WAIT_WITH_PARTNER_MS

    @Test
    fun needsBothTheWaitAndTheWholeSentence() {
        assertFalse(EmergencyStop.canStop(alone - 1, EmergencyStop.PHRASE, hasPartner = false))
        assertFalse(EmergencyStop.canStop(alone, "saya mau tidur", hasPartner = false))
        assertTrue(EmergencyStop.canStop(alone, EmergencyStop.PHRASE, hasPartner = false))
    }

    @Test
    fun withAPartner_theWaitIsMuchLonger() {
        assertFalse(EmergencyStop.canStop(alone, EmergencyStop.PHRASE, hasPartner = true))
        assertFalse(EmergencyStop.canStop(partner - 1, EmergencyStop.PHRASE, hasPartner = true))
        assertTrue(EmergencyStop.canStop(partner, EmergencyStop.PHRASE, hasPartner = true))
    }

    @Test
    fun sentence_isAboutStudying_andLong() {
        assertTrue(EmergencyStop.PHRASE.contains("belajar"))
        assertTrue(EmergencyStop.wordCount >= 25)
    }

    @Test
    fun phrase_ignoresCaseSpacingAndPunctuation() {
        val messy = "  " + EmergencyStop.PHRASE.uppercase().replace(" ", "   ").replace(".", "!") + " "
        assertTrue(EmergencyStop.phraseMatches(messy))
    }

    @Test
    fun phrase_mustContainAllTheWordsInOrder() {
        assertFalse(EmergencyStop.phraseMatches(""))
        val words = EmergencyStop.PHRASE.split(" ")
        assertFalse(EmergencyStop.phraseMatches(words.dropLast(1).joinToString(" ")))
        assertFalse(EmergencyStop.phraseMatches(words.reversed().joinToString(" ")))
    }

    @Test
    fun correctWords_countsTheMatchingPrefix() {
        val words = EmergencyStop.PHRASE.split(" ")
        assertEquals(0, EmergencyStop.correctWords(""))
        assertEquals(3, EmergencyStop.correctWords(words.take(3).joinToString(" ")))
        assertEquals(3, EmergencyStop.correctWords(words.take(3).joinToString(" ") + " salah ketik"))
        assertEquals(EmergencyStop.wordCount, EmergencyStop.correctWords(EmergencyStop.PHRASE))
    }

    @Test
    fun lockMessages_rotateSafely() {
        for (i in -20L..20L) assertTrue(LockMessages.pick(i).isNotBlank())
        assertTrue(LockMessages.ALL.any { it.contains("belajar") })
    }
}

class InterruptionLedgerTest {
    @Test
    fun countsOnlyUncleanEndsDuringACommitment() {
        assertEquals(1, InterruptionLedger.onGuardStart(0, sessionWasOpen = true, commitmentActive = true))
        assertEquals(0, InterruptionLedger.onGuardStart(0, sessionWasOpen = false, commitmentActive = true))
        assertEquals(0, InterruptionLedger.onGuardStart(0, sessionWasOpen = true, commitmentActive = false))
        assertEquals(0, InterruptionLedger.onGuardStart(-3, sessionWasOpen = false, commitmentActive = false))
    }
}
