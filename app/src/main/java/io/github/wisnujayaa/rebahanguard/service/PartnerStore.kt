package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import android.util.Log
import io.github.wisnujayaa.rebahanguard.core.AttemptLimiter
import io.github.wisnujayaa.rebahanguard.core.PinHash
import io.github.wisnujayaa.rebahanguard.core.Totp
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The trusted partner who holds the "off switch".
 *
 *  - Authenticator (TOTP): the shared secret is imported into the Android Keystore as a
 *    non-exportable HMAC key. The app can compute codes with it to *check* the partner's code,
 *    but nobody — not even the phone's owner with a debugger — can read the secret back out to
 *    generate codes for themselves.
 *  - PIN / password: stored only as a salted PBKDF2 hash.
 *
 * Wrong guesses are rate-limited (5 tries, then a 5-minute wait).
 */
object PartnerStore {
    private const val PREFS = "partner"
    private const val KEY_NAME = "name"
    private const val KEY_HAS_TOTP = "has_totp"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_ITER = "pin_iter"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    private const val KEY_LOCK_BOOT = "lock_boot"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "partner_totp"
    private const val TAG = "PartnerStore"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun hasTotp(context: Context): Boolean = prefs(context).getBoolean(KEY_HAS_TOTP, false) && keystoreKey() != null

    fun hasPin(context: Context): Boolean = loadPin(context) != null

    fun hasPartner(context: Context): Boolean = hasTotp(context) || hasPin(context)

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim().take(40)).apply()
    }

    // ------------------------------------------------------------------ setup

    /** Saves the secret the partner just scanned. Returns false if the Keystore refused it. */
    fun saveTotpSecret(context: Context, secret: ByteArray): Boolean = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ks.setEntry(
            ALIAS,
            KeyStore.SecretKeyEntry(SecretKeySpec(secret, KeyProperties.KEY_ALGORITHM_HMAC_SHA1)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build(),
        )
        prefs(context).edit().putBoolean(KEY_HAS_TOTP, true).commit()
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not store the partner key in the Keystore", e)
        false
    } finally {
        secret.fill(0) // the only copy outside the Keystore is wiped
    }

    fun savePin(context: Context, pin: String) {
        val h = PinHash.create(pin)
        prefs(context).edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(h.salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(h.hash, Base64.NO_WRAP))
            .putInt(KEY_PIN_ITER, h.iterations)
            .commit()
    }

    /** Removes the partner completely. Only call after [verify] succeeded. */
    fun remove(context: Context) {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the partner key", e)
        }
        prefs(context).edit().clear().commit()
    }

    // ------------------------------------------------------------------ verification

    sealed interface Result {
        data object Ok : Result
        data object Wrong : Result
        data class LockedOut(val remainingMs: Long) : Result
    }

    /** Checks a code from the partner: a 6-digit authenticator code or their PIN/password. */
    fun verify(context: Context, typed: String): Result {
        val now = SystemClock.elapsedRealtime()
        val limiter = loadLimiter(context)
        if (limiter.isLockedOut(now)) return Result.LockedOut(limiter.remainingLockoutMs(now))

        val input = typed.trim()
        val ok = verifyTotp(input) || (loadPin(context)?.matches(input) == true)
        saveLimiter(context, if (ok) limiter.onSuccess() else limiter.onFailure(now))
        return if (ok) Result.Ok else Result.Wrong
    }

    private fun verifyTotp(input: String): Boolean {
        val key = keystoreKey() ?: return false
        return try {
            Totp.verify({ msg -> Mac.getInstance("HmacSHA1").apply { init(key) }.doFinal(msg) }, input, System.currentTimeMillis() / 1000)
        } catch (e: Exception) {
            Log.w(TAG, "TOTP check failed", e)
            false
        }
    }

    private fun keystoreKey(): SecretKey? = try {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    private fun loadPin(context: Context): PinHash? {
        val p = prefs(context)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return null
        val hash = p.getString(KEY_PIN_HASH, null) ?: return null
        val iterations = p.getInt(KEY_PIN_ITER, 0)
        if (iterations <= 0) return null
        return try {
            PinHash(Base64.decode(salt, Base64.NO_WRAP), Base64.decode(hash, Base64.NO_WRAP), iterations)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun loadLimiter(context: Context): AttemptLimiter {
        val p = prefs(context)
        // The monotonic clock restarts at boot: a lockout from before a restart is reset.
        if (p.getInt(KEY_LOCK_BOOT, -2) != CommitmentStore.bootCount(context)) return AttemptLimiter(failures = p.getInt(KEY_FAILURES, 0))
        return AttemptLimiter(failures = p.getInt(KEY_FAILURES, 0), lockedUntilElapsedMs = p.getLong(KEY_LOCKED_UNTIL, 0))
    }

    private fun saveLimiter(context: Context, l: AttemptLimiter) {
        prefs(context).edit()
            .putInt(KEY_FAILURES, l.failures)
            .putLong(KEY_LOCKED_UNTIL, l.lockedUntilElapsedMs)
            .putInt(KEY_LOCK_BOOT, CommitmentStore.bootCount(context))
            .apply()
    }
}
