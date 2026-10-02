package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TotpTest {
    // Test vectors from RFC 6238, Appendix B (SHA-1 seed), last 6 digits.
    private val rfcSecret = "12345678901234567890".toByteArray()
    private val hmac: (ByteArray) -> ByteArray = { msg ->
        Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(rfcSecret, "HmacSHA1")) }.doFinal(msg)
    }

    @Test
    fun matchesTheRfcTestVectors() {
        val vectors = mapOf(
            59L to "287082",
            1111111109L to "081804",
            1111111111L to "050471",
            1234567890L to "005924",
            2000000000L to "279037",
            20000000000L to "353130",
        )
        for ((t, code) in vectors) assertEquals("t=$t", code, Totp.code(hmac, t))
    }

    @Test
    fun verify_acceptsNeighbouringStepsOnly() {
        val t = 1_234_567_890L
        assertTrue(Totp.verify(hmac, Totp.code(hmac, t), t))
        assertTrue(Totp.verify(hmac, Totp.code(hmac, t - 30), t)) // partner read it a bit late
        assertTrue(Totp.verify(hmac, Totp.code(hmac, t + 30), t)) // clocks slightly apart
        assertFalse(Totp.verify(hmac, Totp.code(hmac, t - 90), t))
        assertFalse(Totp.verify(hmac, Totp.code(hmac, t + 3_600), t)) // codes can't be stockpiled
    }

    @Test
    fun verify_rejectsMalformedInput_andToleratesSpaces() {
        val t = 59L
        assertFalse(Totp.verify(hmac, "", t))
        assertFalse(Totp.verify(hmac, "12345", t))
        assertFalse(Totp.verify(hmac, "1234567", t))
        assertFalse(Totp.verify(hmac, "abcdef", t))
        assertTrue(Totp.verify(hmac, "287 082", t))
    }

    @Test
    fun base32_encodesTheRfcSecret() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(rfcSecret))
        assertEquals("", Base32.encode(ByteArray(0)))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun otpauthUri_hasWhatAuthenticatorsNeed() {
        val uri = Totp.otpauthUri(rfcSecret, "Wisnu")
        assertTrue(uri.startsWith("otpauth://totp/Rebahan%20Guard%3AWisnu?"))
        assertTrue(uri.contains("secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        assertTrue(uri.contains("period=30") && uri.contains("digits=6"))
    }

    @Test
    fun newSecrets_areRandomAndFullLength() {
        val a = Totp.newSecret()
        val b = Totp.newSecret()
        assertEquals(Totp.SECRET_BYTES, a.size)
        assertFalse(a.contentEquals(b))
    }
}

class PinHashTest {
    private val fast = 1_000 // keep tests quick; production uses 120 000

    @Test
    fun matchesOnlyTheRightPin() {
        val h = PinHash.create("rahasia-teman", iterations = fast)
        assertTrue(h.matches("rahasia-teman"))
        assertFalse(h.matches("rahasia-Teman"))
        assertFalse(h.matches(""))
    }

    @Test
    fun samePin_differentSalt_differentHash() {
        val a = PinHash.create("12345678", iterations = fast)
        val b = PinHash.create("12345678", iterations = fast)
        assertFalse(a.hash.contentEquals(b.hash))
    }

    @Test
    fun tooShortPins_areRejected() {
        assertFalse(PinHash.isAcceptable("12345"))
        assertFalse(PinHash.isAcceptable("      "))
        assertTrue(PinHash.isAcceptable("123456"))
        assertThrows(IllegalArgumentException::class.java) { PinHash.create("123", iterations = fast) }
    }
}

class AttemptLimiterTest {
    @Test
    fun locksOutAfterFiveFailures_thenAllowsAgain() {
        var l = AttemptLimiter()
        repeat(4) { l = l.onFailure(0) }
        assertFalse(l.isLockedOut(0))
        l = l.onFailure(1_000)
        assertTrue(l.isLockedOut(1_000))
        assertTrue(l.isLockedOut(1_000 + 5 * 60_000 - 1))
        assertFalse(l.isLockedOut(1_000 + 5 * 60_000))
    }

    @Test
    fun successResetsTheCount() {
        var l = AttemptLimiter()
        repeat(4) { l = l.onFailure(0) }
        l = l.onSuccess()
        l = l.onFailure(0)
        assertFalse(l.isLockedOut(0))
    }
}

class ScheduleTest {
    private val night = Schedule(enabled = true, startMinute = 22 * 60, endMinute = 5 * 60)

    @Test
    fun overnightWindow_wrapsPastMidnight() {
        assertTrue(night.isWithin(22 * 60))
        assertTrue(night.isWithin(23 * 60 + 59))
        assertTrue(night.isWithin(0))
        assertTrue(night.isWithin(4 * 60 + 59))
        assertFalse(night.isWithin(5 * 60))
        assertFalse(night.isWithin(12 * 60))
        assertFalse(night.isWithin(21 * 60 + 59))
    }

    @Test
    fun sameDayWindow_andEdgeCases() {
        val afternoon = Schedule(true, 13 * 60, 15 * 60)
        assertTrue(afternoon.isWithin(14 * 60))
        assertFalse(afternoon.isWithin(16 * 60))
        assertTrue(Schedule(true, 600, 600).isWithin(0)) // 24 h
        assertTrue(Schedule(false, 0, 1).isWithin(12 * 60)) // disabled = always
        assertTrue(night.isWithin(-60)) // 23:00 the previous day
    }

    @Test
    fun formatting() {
        assertEquals("22:00", Schedule.format(22 * 60))
        assertEquals("05:07", Schedule.format(5 * 60 + 7))
        assertEquals("00:00", Schedule.format(Schedule.MINUTES_PER_DAY))
    }
}

class NightStatsTest {
    private val hour = 3_600_000L
    private val jakarta = (7 * hour).toInt()

    @Test
    fun nightRunsFromNoonToNoon() {
        val day = 20_000L * 86_400_000L // 00:00 UTC of some day
        val evening = day + 22 * hour - jakarta // 22:00 local
        val earlyMorning = day + (24 + 3) * hour - jakarta // 03:00 local, next day
        val nextNoon = day + (24 + 12) * hour - jakarta
        assertEquals(NightStats.nightOf(evening, jakarta), NightStats.nightOf(earlyMorning, jakarta))
        assertEquals(NightStats.nightOf(evening, jakarta) + 1, NightStats.nightOf(nextNoon, jakarta))
    }

    @Test
    fun streak_countsCleanGuardedNights_andIgnoresTonightInProgress() {
        val records = listOf(
            NightRecord(10, guarded = true, caught = 2),
            NightRecord(11, guarded = true),
            NightRecord(12, guarded = true),
            NightRecord(13, guarded = true),
        )
        assertEquals(2, NightStats.streak(records, tonight = 13)) // 11, 12 (13 still running)
        assertEquals(3, NightStats.streak(records, tonight = 14))
        val caughtTonight = NightStats.update(records, 14) { it.copy(guarded = true, caught = 1) }
        assertEquals(0, NightStats.streak(caughtTonight, tonight = 14))
    }

    @Test
    fun streak_breaksOnAnUnguardedNight() {
        val records = listOf(NightRecord(1, guarded = true), NightRecord(3, guarded = true))
        assertEquals(1, NightStats.streak(records, tonight = 4))
    }

    @Test
    fun update_keepsOnlyTheLastSixtyNights() {
        var r = emptyList<NightRecord>()
        for (n in 1..100) r = NightStats.update(r, n) { it.copy(guarded = true) }
        assertEquals(NightStats.KEEP_NIGHTS, r.size)
        assertEquals(41, r.first().night)
    }

    @Test
    fun summary_mentionsTheImportantNumbers() {
        val records = listOf(
            NightRecord(20, guarded = true, caught = 1, lockedMs = 4 * 60_000),
            NightRecord(21, guarded = true),
        )
        val text = NightStats.summary(records, tonight = 22, partnerName = "Rafi")
        assertTrue(text.startsWith("Halo Rafi"))
        assertTrue(text.contains("Ketahuan rebahan: 1 kali, total terkunci 4 menit"))
        assertTrue(text.contains("Malam bersih (tidak ketahuan rebahan): 1"))
    }
}
