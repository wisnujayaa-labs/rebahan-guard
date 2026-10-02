package io.github.wisnujayaa.rebahanguard.service

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import io.github.wisnujayaa.rebahanguard.core.AttemptCount
import io.github.wisnujayaa.rebahanguard.core.FocusRules
import io.github.wisnujayaa.rebahanguard.core.PassBook

/** Focus-mode settings and counters (SharedPreferences, sanitised on the way out). */
object FocusStore {
    private const val PREFS = "focus"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    fun blocked(c: Context): Set<String> =
        prefs(c).getStringSet("blocked", null)?.filter { it.matches(PKG) }?.toSet() ?: FocusRules.DEFAULT_BLOCKED

    fun setBlocked(c: Context, pkgs: Set<String>) =
        prefs(c).edit().putStringSet("blocked", pkgs.filter { it.matches(PKG) }.toSet()).apply()

    fun passes(c: Context): PassBook = prefs(c).let {
        PassBook(it.getInt("pass_day", 0), it.getInt("pass_used", 0).coerceAtLeast(0), it.getLong("pass_until", 0))
    }

    fun savePasses(c: Context, b: PassBook) =
        prefs(c).edit().putInt("pass_day", b.day).putInt("pass_used", b.used).putLong("pass_until", b.activeUntilElapsedMs).apply()

    fun attempts(c: Context): AttemptCount = prefs(c).let { AttemptCount(it.getInt("att_day", 0), it.getInt("att_count", 0).coerceAtLeast(0)) }

    fun saveAttempts(c: Context, a: AttemptCount) = prefs(c).edit().putInt("att_day", a.day).putInt("att_count", a.count).apply()

    /** Usage Access ("Akses penggunaan") lets the guard see which app is in front. */
    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isStrictEnabled(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith(c.packageName + "/") }
    }

    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
}
