package io.github.wisnujayaa.rebahanguard.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.wisnujayaa.rebahanguard.R
import io.github.wisnujayaa.rebahanguard.core.FocusRules
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow

/** Foreground app as reported instantly by the accessibility service (strict mode). */
object ForegroundApp {
    val pkg = MutableStateFlow<String?>(null)
    @Volatile var atElapsedMs = 0L
    @Volatile var atWallMs = 0L
}

/**
 * Focus mode: while there is work to do, distracting apps are covered by a calm full-screen
 * window. Leaving is one tap ("Kembali bekerja"); staying needs a deliberate pause — wait 30
 * seconds for one of two daily 5-minute passes. Every attempt is counted and shown.
 *
 * The foreground app comes from Usage Access (polled every second) or, in strict mode, from
 * the accessibility service (instant).
 */
class AppBlocker(
    private val context: Context,
    /** Why focus is on right now, or null when it isn't. */
    private val focusReason: () -> String?,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var shownFor: String? = null
    private var foreground: String? = null
    private var lastQueryMs = 0L
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        hide()
    }

    private fun check() {
        val reason = focusReason()
        if (reason == null || !FocusStore.enabled(context) || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val pkg = currentForeground()
        val blocked = FocusRules.isBlocked(pkg, FocusStore.blocked(context), context.packageName)
        val passActive = FocusStore.passes(context).isActive(SystemClock.elapsedRealtime())
        when {
            !blocked || passActive -> hide()
            shownFor != pkg -> {
                val today = DreamStore.today()
                val attempts = FocusStore.attempts(context).add(today)
                FocusStore.saveAttempts(context, attempts)
                show(pkg!!, reason, attempts.on(today), FocusStore.passes(context).left(today))
            }
        }
    }

    private var foregroundAtWallMs = 0L

    /** The newest of: the last usage "resumed" event, and the strict-mode report. */
    private fun currentForeground(): String? {
        val strictPkg = ForegroundApp.pkg.value
        val strictAt = ForegroundApp.atWallMs
        fun preferStrict() {
            if (strictPkg != null && strictAt > foregroundAtWallMs) {
                foreground = strictPkg
                foregroundAtWallMs = strictAt
            }
        }
        if (!FocusStore.hasUsageAccess(context)) {
            preferStrict()
            return foreground
        }
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = if (lastQueryMs == 0L) end - 60_000 else lastQueryMs - 2_000
            lastQueryMs = end
            val events = usm.queryEvents(begin, end)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= foregroundAtWallMs) {
                    foreground = e.packageName
                    foregroundAtWallMs = e.timeStamp
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Usage query failed", e)
        }
        preferStrict()
        return foreground
    }

    private fun label(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        "Aplikasi ini"
    }

    // ------------------------------------------------------------------ the window

    private fun show(pkg: String, reason: String, attempts: Int, passesLeft: Int) {
        hide()
        val serif = try { ResourcesCompat.getFont(context, R.font.fraunces_regular) } catch (e: Exception) { null } ?: Typeface.SERIF
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(56f), dp(28f), dp(28f))
        }
        fun text(t: String, size: Float, color: Int, face: Typeface? = null) = TextView(context).apply {
            text = t
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (face != null) typeface = face
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(14f))
        }
        column.addView(text("Mode fokus · $reason", 12f, MUTED))
        column.addView(text("${label(pkg)} bisa menunggu.", 34f, TEXT, serif))
        column.addView(text("Pekerjaanmu tidak. Hari ini kamu sudah mencoba membuka aplikasi pengalih $attempts kali.", 15f, BODY))

        val back = pill("Kembali bekerja", filled = true) {
            hide()
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Could not go home", e)
            }
        }
        val ask = pill(if (passesLeft > 0) "Minta 5 menit · tunggu 30 detik · sisa $passesLeft" else "Jatah 5 menit hari ini sudah habis", filled = false) {}
        if (passesLeft > 0) {
            ask.setOnClickListener {
                ask.isEnabled = false
                val started = SystemClock.elapsedRealtime()
                val countdown = object : Runnable {
                    override fun run() {
                        if (view == null) return
                        val waited = SystemClock.elapsedRealtime() - started
                        val left = ((FocusRules.PASS_WAIT_MS - waited + 999) / 1000).coerceAtLeast(0)
                        if (left > 0) {
                            ask.text = "Tunggu $left detik… pikirkan lagi"
                            handler.postDelayed(this, 1_000)
                        } else {
                            val granted = FocusStore.passes(context).take(DreamStore.today(), waited, SystemClock.elapsedRealtime())
                            if (granted != null) FocusStore.savePasses(context, granted)
                            hide()
                        }
                    }
                }
                handler.post(countdown)
            }
        }
        column.addView(back)
        column.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10f)) })
        column.addView(ask)

        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                event.keyCode == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
        }.apply {
            setBackgroundColor(INK)
            isClickable = true
            isFocusable = true
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(root, params)
            view = root
            shownFor = pkg
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the focus window", e)
        }
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(if (filled) INK_SOLID else MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = GradientDrawable().apply {
            cornerRadius = dp(28f).toFloat()
            if (filled) setColor(TEXT) else setStroke(dp(1f), HAIRLINE)
        }
        setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        setOnClickListener { onClick() }
    }

    fun hide() {
        val v = view ?: run { shownFor = null; return }
        view = null
        shownFor = null
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the focus window", e)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).roundToInt()

    private companion object {
        const val TAG = "AppBlocker"
        const val POLL_MS = 1_000L
        const val INK = 0xFC14161B.toInt()
        const val INK_SOLID = 0xFF14161B.toInt()
        const val TEXT = 0xFFEDE6D8.toInt()
        const val BODY = 0xFFB9B2A4.toInt()
        const val MUTED = 0xFF9A9488.toInt()
        const val HAIRLINE = 0xFF3A3D45.toInt()
    }
}
