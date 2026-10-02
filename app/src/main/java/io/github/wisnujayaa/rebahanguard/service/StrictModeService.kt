package io.github.wisnujayaa.rebahanguard.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Strict mode ("Mode ketat"). Two jobs, nothing else:
 *  1. report which app comes to the front, instantly (focus mode reacts without polling lag);
 *  2. during protected time, step back out of the system pages that would switch the guard off:
 *     this app's "App info" (force stop / uninstall), its Accessibility page and the uninstall
 *     dialog — recognised by the app's name appearing in a Settings or installer window.
 *
 * It reads window titles and the app's own name only; it never reads or stores what the user
 * types or sees in other apps. Safe mode, ADB and factory reset always remain possible.
 */
class StrictModeService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ForegroundApp.pkg.value = pkg
            ForegroundApp.atElapsedMs = SystemClock.elapsedRealtime()
        }
        if (pkg in GUARDED_SYSTEM_PACKAGES && isProtectedNow() && showsThisApp()) {
            Log.i(TAG, "Leaving a page that could switch the guard off")
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun isProtectedNow(): Boolean =
        Protection.isProtected(this) || SessionStore.session.value != null

    /**
     * A page about THIS app that offers a way to switch it off. Harmless pages that mention the
     * app (usage access, display over other apps, battery) stay usable, so granting permissions
     * during a session still works.
     */
    private fun showsThisApp(): Boolean = try {
        val root = rootInActiveWindow ?: return false
        val label = applicationInfo.loadLabel(packageManager).toString()
        root.findAccessibilityNodeInfosByText(label).isNotEmpty() &&
            (DANGER_WORDS + "Gunakan $label" + "Use $label").any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    } catch (e: Exception) {
        false
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "StrictMode"
        /** Buttons on App info and uninstall dialogs, in Indonesian and English. */
        val DANGER_WORDS = listOf(
            "Paksa berhenti", "Force stop", "Uninstall", "Copot", "Hapus instalan", "Hapus pemasangan",
        )
        val GUARDED_SYSTEM_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
        )
    }
}
