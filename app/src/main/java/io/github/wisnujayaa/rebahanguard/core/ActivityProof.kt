package io.github.wisnujayaa.rebahanguard.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Gerak": a movement habit is proven by the step counter, not by a tick. Cadence (steps per
 * minute) separates running from a stroll: walking is typically ~100–120, running ~150+.
 */
object StepProof {
    const val RUN_CADENCE = 140
    const val WALK_CADENCE = 90

    enum class Kind { NONE, WALK, RUN }

    fun cadence(steps: Int, movingMs: Long): Int =
        if (movingMs < 60_000 || steps <= 0) 0 else (steps * 60_000L / movingMs).toInt()

    fun kind(steps: Int, movingMs: Long): Kind {
        val c = cadence(steps, movingMs)
        return when {
            c >= RUN_CADENCE -> Kind.RUN
            c >= WALK_CADENCE -> Kind.WALK
            else -> Kind.NONE
        }
    }

    /**
     * Step counters report a running total since boot; a reboot resets it. Returns the steps
     * taken between two readings, treating a drop as a reset (count from zero again).
     */
    fun delta(previousTotal: Float?, currentTotal: Float): Int {
        if (!currentTotal.isFinite() || currentTotal < 0) return 0
        val prev = previousTotal ?: return 0
        if (!prev.isFinite()) return 0
        return if (currentTotal >= prev) (currentTotal - prev).toInt() else currentTotal.toInt()
    }
}

/**
 * "Tempat": a place remembered by what the phone measured while the user was there — the GPS
 * fix (including its usual indoor offset) and which Wi-Fi access points were in range. Wi-Fi
 * is often more precise than GPS indoors: each access point has a unique address (BSSID).
 */
data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val bssids: Set<String>,
)

object PlaceRules {
    const val MIN_RADIUS_M = 100.0
    const val MIN_WIFI_OVERLAP = 0.3f

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun wifiOverlap(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return (a intersect b).size.toFloat() / (a union b).size
    }

    /**
     * At the place if the Wi-Fi fingerprint matches, or the GPS fix is within a radius that
     * grows with how unsure both fixes are.
     */
    fun isAt(place: Place, lat: Double?, lon: Double?, accuracyM: Float?, bssids: Set<String>): Boolean {
        if (wifiOverlap(place.bssids, bssids.map { it.lowercase() }.toSet()) >= MIN_WIFI_OVERLAP) return true
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return false
        val acc = (accuracyM ?: 50f).toDouble().coerceIn(0.0, 500.0)
        val radius = max(MIN_RADIUS_M, (place.accuracyM + acc) * 1.5)
        return distanceM(place.lat, place.lon, lat, lon) <= radius
    }

    fun normalizeBssids(raw: Collection<String>): Set<String> =
        raw.map { it.lowercase().trim() }.filter { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) && it != "02:00:00:00:00:00" }.toSet()
}

/** How a week of habit work was proven — what the partner report shows. */
object EvidenceReport {
    data class Line(val title: String, val days: Int, val strength: Int)

    fun label(strength: Int): String = when (strength) {
        3 -> "Sensor"
        2 -> "Bukti"
        1 -> "Jujur"
        else -> "—"
    }

    /** Share (0..1) of done habit-days proven by sensors or evidence (strength ≥ 2). */
    fun provenShare(book: DreamBook, fromDay: Int, toDay: Int): Float? {
        val done = book.log.filter { it.day in fromDay..toDay && book.habit(it.habitId)?.let { h -> it.amount >= h.dailyTarget } == true }
        if (done.isEmpty()) return null
        return done.count { it.strength >= 2 }.toFloat() / done.size
    }

    fun lines(book: DreamBook, fromDay: Int, toDay: Int): List<Line> = book.habits.map { h ->
        val days = book.log.filter { it.habitId == h.id && it.day in fromDay..toDay && it.amount >= h.dailyTarget }
        Line(h.title, days.size, days.maxOfOrNull { it.strength } ?: 0)
    }

    fun summary(book: DreamBook, today: Int, partnerName: String?): String = buildString {
        val from = today - 6
        if (!partnerName.isNullOrBlank()) append("Halo $partnerName, ")
        append("ini laporan kebiasaanku 7 hari terakhir:\n")
        for (l in lines(book, from, today)) append("• ${l.title}: ${l.days} hari (${label(l.strength)})\n")
        provenShare(book, from, today)?.let { append("• Terbukti sensor atau bukti: ${(it * 100).toInt()}%\n") }
    }
}
