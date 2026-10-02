package io.github.wisnujayaa.rebahanguard.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Time-based one-time passwords (RFC 6238), compatible with Google Authenticator and friends.
 *
 * The HMAC is passed in as a function so the Android layer can compute it with a key that lives
 * in the Android Keystore and can never be read back out, not even by the phone's owner.
 */
object Totp {
    const val STEP_SECONDS = 30L
    const val DIGITS = 6
    const val SECRET_BYTES = 20

    /** One 6-digit code for the 30-second time step containing [epochSeconds]. */
    fun code(hmacSha1: (ByteArray) -> ByteArray, epochSeconds: Long): String =
        codeForCounter(hmacSha1, Math.floorDiv(epochSeconds, STEP_SECONDS))

    fun codeForCounter(hmacSha1: (ByteArray) -> ByteArray, counter: Long): String {
        val hash = hmacSha1(ByteBuffer.allocate(8).putLong(counter).array())
        // Dynamic truncation (RFC 4226 §5.3).
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /**
     * Accepts the current code and the ones just before/after it, to tolerate a small clock
     * difference between the two phones and the time it takes to read the code out loud.
     */
    fun verify(hmacSha1: (ByteArray) -> ByteArray, typed: String, epochSeconds: Long, window: Int = 1): Boolean {
        val digits = typed.filter { it.isDigit() }
        if (digits.length != DIGITS) return false
        val counter = Math.floorDiv(epochSeconds, STEP_SECONDS)
        var ok = false
        for (delta in -window..window) {
            // Constant-time comparison, and no early exit, so timing reveals nothing.
            ok = ok or MessageDigest.isEqual(codeForCounter(hmacSha1, counter + delta).toByteArray(), digits.toByteArray())
        }
        return ok
    }

    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

    /** The `otpauth://` link encoded in the QR code an authenticator app scans. */
    fun otpauthUri(secret: ByteArray, account: String, issuer: String = "Rebahan Guard"): String {
        val label = urlEncode("$issuer:$account")
        return "otpauth://totp/$label?secret=${Base32.encode(secret)}&issuer=${urlEncode(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** RFC 4648 Base32 without padding, as used by authenticator apps. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}

/**
 * The partner's password (PIN or words), stored only as a salted PBKDF2 hash. Even someone who
 * reads the app's storage gets a hash that is slow to brute-force, never the password itself.
 */
data class PinHash(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
    fun matches(typed: String): Boolean =
        MessageDigest.isEqual(derive(typed, salt, iterations), hash)

    override fun equals(other: Any?): Boolean =
        other is PinHash && salt.contentEquals(other.salt) && hash.contentEquals(other.hash) && iterations == other.iterations

    override fun hashCode(): Int = 31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + iterations

    companion object {
        const val MIN_LENGTH = 6
        const val DEFAULT_ITERATIONS = 120_000

        fun isAcceptable(pin: String): Boolean = pin.length in MIN_LENGTH..64 && pin.isNotBlank()

        fun create(pin: String, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): PinHash {
            require(isAcceptable(pin)) { "PIN must be $MIN_LENGTH–64 characters" }
            val salt = ByteArray(16).also { random.nextBytes(it) }
            return PinHash(salt, derive(pin, salt, iterations), iterations)
        }

        private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}

/**
 * Slows down guessing: after [maxFailures] wrong codes in a row, further attempts are refused for
 * [lockoutMs]. With a 6-digit code that makes brute force take years instead of minutes.
 * Uses the monotonic clock, so changing the phone's time doesn't reset it.
 */
data class AttemptLimiter(
    val failures: Int = 0,
    val lockedUntilElapsedMs: Long = 0,
    val maxFailures: Int = 5,
    val lockoutMs: Long = 5 * 60_000L,
) {
    fun isLockedOut(nowElapsedMs: Long): Boolean = nowElapsedMs < lockedUntilElapsedMs

    fun remainingLockoutMs(nowElapsedMs: Long): Long = (lockedUntilElapsedMs - nowElapsedMs).coerceAtLeast(0)

    fun onFailure(nowElapsedMs: Long): AttemptLimiter {
        val f = failures + 1
        return if (f >= maxFailures) copy(failures = 0, lockedUntilElapsedMs = nowElapsedMs + lockoutMs) else copy(failures = f)
    }

    fun onSuccess(): AttemptLimiter = copy(failures = 0, lockedUntilElapsedMs = 0)
}
