package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import io.github.wisnujayaa.rebahanguard.core.Place
import io.github.wisnujayaa.rebahanguard.core.PlaceRules

/** Saved places for "Tempat" habits: one per line, tab-separated. */
object PlaceStore {
    private const val FILE = "places.txt"

    fun load(context: Context): List<Place> = TextFile.read(context, FILE).orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 7) return@mapNotNull null
        try {
            Place(
                id = f[0].toLong(),
                name = f[1].take(60).ifBlank { return@mapNotNull null },
                lat = f[2].toDouble().takeIf { it.isFinite() && it in -90.0..90.0 } ?: return@mapNotNull null,
                lon = f[3].toDouble().takeIf { it.isFinite() && it in -180.0..180.0 } ?: return@mapNotNull null,
                accuracyM = f[4].toFloat().coerceIn(1f, 500f),
                bssids = PlaceRules.normalizeBssids(f[5].split(',')),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun save(context: Context, places: List<Place>) {
        TextFile.write(context, FILE, places.joinToString("\n") {
            listOf(it.id, it.name.replace('\t', ' ').replace('\n', ' '), it.lat, it.lon, it.accuracyM, it.bssids.joinToString(","), "").joinToString("\t")
        })
    }
}
