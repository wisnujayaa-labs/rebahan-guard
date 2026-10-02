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
 * A full-screen window drawn above every app (home screen and Settings included) while the user
 * is lying in bed. It is a "Display over other apps" window rather than the system lock screen:
 * the lock screen opens with a fingerprint in a second, this one only goes away when the sensors
 * say the user sat up.
 *
 * Built with plain Views on purpose: a window owned by a Service has no Activity, and plain
 * Views need no lifecycle or saved-state plumbing.
 */
class LockOverlay(
    private val context: Context,
    private val onEmergency: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var angleText: TextView? = null

    val isShowing: Boolean get() = root != null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Returns false if the window couldn't be shown (permission revoked, system refused). */
    fun show(): Boolean {
        if (root != null) return true
        if (!canShow()) return false
        val view = buildView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable and touchable (no NOT_FOCUSABLE / NOT_TOUCHABLE flags): it swallows taps
            // and the back button, so nothing underneath can be used.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            windowManager.addView(view, params)
            root = view
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not show lock overlay", e)
            false
        }
    }

    fun hide() {
        val view = root ?: return
        root = null
        angleText = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove lock overlay", e)
        }
    }

    fun update(screenElevationDeg: Float, thresholdDeg: Float) {
        val text = angleText ?: return
        text.text = if (screenElevationDeg.isFinite()) {
            "Sudut layar ${screenElevationDeg.roundToInt()}°, batasmu ${thresholdDeg.roundToInt()}°"
        } else {
            "Membaca sensor…"
        }
    }

    private fun buildView(): View {
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).roundToInt()

        fun label(text: String, size: Float, color: Int, serif: Boolean = false) = TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size))
            if (serif) typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32f), dp(32f), dp(32f), dp(32f))
            addView(label("Rebahan Guard", 14f, MUTED))
            addView(space(dp(24f)))
            addView(label("Duduk dulu.", 40f, TEXT, serif = true))
            addView(space(dp(12f)))
            addView(label("Layar ini terbuka sendiri begitu kamu tidak rebahan lagi.", 16f, MUTED))
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
        const val TEXT = 0xFFE9EAF6.toInt()
        const val MUTED = 0xFF9CA3C7.toInt()
        const val LAMP = 0xFFF2D28B.toInt()
    }
}
