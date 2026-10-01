package io.github.wisnujayaa.rebahanguard.core

/**
 * Validates the stored alarm sound reference before it is handed to the media system.
 *
 * Only `content://` (system ringtones, media and files picked through the document picker)
 * and `android.resource://` URIs are accepted. Anything else — `file://` paths, `http://`
 * URLs, malformed or oversized strings from a corrupted settings file — falls back to the
 * default alarm sound instead of being played.
 */
object AlarmSoundPolicy {
    private val ALLOWED_SCHEMES = setOf("content", "android.resource")
    private const val MAX_LENGTH = 2_048

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > MAX_LENGTH) return null
        if (raw.any { it.isISOControl() || it.isWhitespace() }) return null
        val separator = raw.indexOf("://")
        if (separator <= 0) return null
        val scheme = raw.substring(0, separator).lowercase()
        if (scheme !in ALLOWED_SCHEMES) return null
        if (raw.length == separator + 3) return null // nothing after "://"
        return raw
    }
}
