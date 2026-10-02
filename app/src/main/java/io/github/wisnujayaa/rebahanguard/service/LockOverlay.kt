package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import kotlin.math.roundToInt

/**
 * Windows drawn above every app ("Display over other apps"):
 *  - the lock screen: blocks everything until the user sits up;
 *  - a warning banner: a 10-second "sit up now" heads-up that doesn't block anything;
 *  - a ring light: a brief full-brightness white screen that lights the user's face for the
 *    camera in a dark room.
 *
 * Built with plain Views on purpose: a window owned by a Service has no Activity, and plain
 * Views need no lifecycle or saved-state plumbing.
 */
class LockOverlay(
    private val context: Context,
    private val onEmergency: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lockView: View? = null
    private var angleText: TextView? = null
    private var warningView: TextView? = null
    private var ringLightView: View? = null

    val isShowing: Boolean get() = lockView != null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    // ------------------------------------------------------------------ lock screen

    /** Returns false if the window couldn't be shown (permission revoked, system refused). */
    fun show(message: String): Boolean {
        if (lockView != null) return true
        if (!canShow()) return false
        val view = buildLockView(message)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable and touchable (no NOT_FOCUSABLE / NOT_TOUCHABLE flags): it swallows taps
            // and the back button, so nothing underneath can be used.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        return add(view, params).also { if (it) lockView = view }
    }

    fun hide() {
        val view = lockView ?: return
        lockView = null
        angleText = null
        remove(view)
    }

    fun update(screenElevationDeg: Float, thresholdDeg: Float) {
        val text = angleText ?: return
        text.text = if (screenElevationDeg.isFinite()) {
            "Sudut layar ${screenElevationDeg.roundToInt()}°, batasmu ${thresholdDeg.roundToInt()}°"
        } else {
            "Membaca sensor…"
        }
    }

    // ------------------------------------------------------------------ warning banner

    fun showWarning(secondsLeft: Int) {
        if (!canShow()) return
        val existing = warningView
        val text = "Kamu terdeteksi rebahan. Duduk sekarang, layar dikunci dalam $secondsLeft detik."
        if (existing != null) {
            existing.text = text
            return
        }
        val view = TextView(context).apply {
            this.text = text
            setTextColor(INK_SOLID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(LAMP)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // A heads-up only: taps go through to whatever is underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(48f)
            horizontalMargin = 0.04f
        }
        if (add(view, params)) warningView = view
    }

    fun hideWarning() {
        val view = warningView ?: return
        warningView = null
        remove(view)
    }

    // ------------------------------------------------------------------ ring light

    fun showRingLight() {
        if (ringLightView != null || !canShow()) return
        val view = View(context).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE,
        ).apply {
            screenBrightness = 1f // full brightness while this window is visible
        }
        if (add(view, params)) ringLightView = view
    }

    fun hideRingLight() {
        val view = ringLightView ?: return
        ringLightView = null
        remove(view)
    }

    fun hideAll() {
        hide()
        hideWarning()
        hideRingLight()
    }

    // ------------------------------------------------------------------ helpers

    private fun add(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not show overlay window", e)
        false
    }

    private fun remove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove overlay window", e)
        }
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).roundToInt()

    private fun label(text: String, size: Float, color: Int, serif: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size))
        if (serif) typeface = Typeface.SERIF
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.25f)
    }

    private fun buildLockView(message: String): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32f), dp(32f), dp(32f), dp(32f))
            addView(label("Rebahan Guard", 14f, MUTED))
            addView(space(dp(24f)))
            addView(label("Duduk dulu.", 40f, TEXT, serif = true))
            addView(space(dp(16f)))
            addView(label(message, 18f, TEXT, serif = true))
            addView(space(dp(12f)))
            addView(label("Layar ini terbuka sendiri begitu kamu tidak rebahan lagi.", 15f, MUTED))
            addView(space(dp(32f)))
            addView(label("Membaca sensor…", 15f, LAMP).also { angleText = it })
        }

        val emergency = Button(context).apply {
            text = "Darurat"
            isAllCaps = false
            setTextColor(TEXT)
            background = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                setStroke(dp(1f), MUTED)
            }
            setPadding(dp(28f), dp(12f), dp(28f), dp(12f))
            contentDescription = "Darurat: buka telepon dan jeda penjaga 3 menit"
            setOnClickListener { onEmergency() }
        }

        return object : FrameLayout(context) {
            // Swallow the back button so it can't dismiss anything underneath.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                event.keyCode == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
        }.apply {
            setBackgroundColor(INK)
            isClickable = true
            isFocusable = true
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            addView(emergency, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(48f) })
        }
    }

    private fun space(heightPx: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, heightPx)
    }

    private companion object {
        const val TAG = "LockOverlay"
        const val INK = 0xFA10142E.toInt()
        const val INK_SOLID = 0xFF10142E.toInt()
        const val TEXT = 0xFFE9EAF6.toInt()
        const val MUTED = 0xFF9CA3C7.toInt()
        const val LAMP = 0xFFF2D28B.toInt()
    }
}
