package io.github.wisnujayaa.rebahanguard.core

/**
 * Ready-made sentences for people who can't (yet) put their reason into words. All of them are
 * first person, about a choice rather than about being a bad person (guilt that points at an
 * action makes people repair it; shame that points at the self makes them avoid — here, by
 * uninstalling), and they look forward.
 */
object CommitmentTemplates {
    enum class Theme(val label: String) {
        SELF("Untuk diriku"),
        FAMILY("Untuk keluarga"),
        FUTURE("Untuk masa depan"),
        FAITH("Untuk Tuhan"),
    }

    val WHY: Map<Theme, List<String>> = mapOf(
        Theme.SELF to listOf(
            "Aku ingin membuktikan pada diriku sendiri bahwa aku bisa lebih dari ini.",
            "Aku lelah menjadi orang yang selalu bilang “besok saja”.",
            "Aku ingin bangga pada caraku menghabiskan hari ini.",
        ),
        Theme.FAMILY to listOf(
            "Ada orang-orang yang berkorban supaya aku bisa sampai di sini. Rebahan bukan balasan yang pantas.",
            "Aku ingin suatu hari orang tuaku tidak perlu khawatir lagi tentang aku.",
        ),
        Theme.FUTURE to listOf(
            "Diriku lima tahun lagi sedang menunggu keputusanku hari ini.",
            "Aku tidak mau setahun lagi menyesal karena hari-hari ini habis di kasur.",
            "Hal kecil yang kukerjakan hari ini adalah satu-satunya jembatan menuju impianku.",
        ),
        Theme.FAITH to listOf(
            "Waktu adalah amanah, dan aku akan ditanya ke mana ia kuhabiskan.",
            "Demi masa, sungguh manusia dalam kerugian, kecuali yang beriman dan beramal saleh. (QS Al-‘Asr)",
            "Manfaatkan waktu luangmu sebelum datang waktu sibukmu. (Hadis tentang lima perkara)",
        ),
    )

    /** What is lost by putting it off — the third step of the writer. */
    val COST = listOf(
        "Setiap hari yang kutunda membuat impian ini terasa makin jauh.",
        "Kalau terus begini, aku akan menjadi orang yang hanya bisa bercerita tentang rencana.",
        "Waktu yang kubuang di kasur tidak akan pernah kembali.",
    )

    /** "Aku ingin <dream>. <why> <cost>" — trimmed, without doubled spaces or full stops. */
    fun compose(dream: String, why: String, cost: String): String {
        fun sentence(s: String): String {
            val t = s.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) return ""
            return if (t.last() in ".!?)") t else "$t."
        }
        val opening = dream.trim().takeIf { it.isNotEmpty() }?.let { sentence("Aku ingin ${it.replaceFirstChar { c -> c.lowercaseChar() }}") }.orEmpty()
        return listOf(opening, sentence(why), sentence(cost)).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The emergency-stop sentence, naming the dream that is being traded away. */
    fun emergencyPhrase(dreamTitle: String?): String {
        val target = dreamTitle?.trim()?.takeIf { it.isNotEmpty() }
        return if (target == null) {
            EmergencyStop.PHRASE
        } else {
            "Saya sadar bahwa saya memilih rebahan daripada melangkah menuju $target. " +
                "Waktu yang saya buang hari ini tidak akan pernah kembali, " +
                "dan saya sendiri yang akan menanggung akibatnya."
        }
    }
}
