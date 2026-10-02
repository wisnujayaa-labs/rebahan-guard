package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.wisnujayaa.rebahanguard.R
import io.github.wisnujayaa.rebahanguard.core.TypedPhrase
import kotlin.math.roundToInt

/**
 * Windows drawn above every app ("Display over other apps"):
 *  - the lock screen: blocks everything until the user sits up (and serves any penalty);
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
    /** A volume key was pressed while locked (the key itself is swallowed). */
    private val onVolumeKey: (down: Boolean) -> Unit = {},
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lockView: View? = null
    private var kickerText: TextView? = null
    private var titleText: TextView? = null
    private var bodyText: TextView? = null
    private var quoteText: TextView? = null
    private var angleText: TextView? = null
    private var phraseBox: LinearLayout? = null
    private var phraseProgress: TextView? = null
    private var warningView: TextView? = null
    private var ringLightView: View? = null

    private val serif: Typeface = font(R.font.fraunces_regular) ?: Typeface.SERIF
    private val serifItalic: Typeface = font(R.font.fraunces_italic) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    val isShowing: Boolean get() = lockView != null

    /** True while the "type the sentence" box is open. */
    val isAskingPhrase: Boolean get() = phraseBox?.visibility == View.VISIBLE

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    // ------------------------------------------------------------------ lock screen

    /** Returns false if the window couldn't be shown (permission revoked, system refused). */
    fun show(message: String): Boolean {
        if (lockView != null) {
            showLying(message)
            return true
        }
        if (!canShow()) return false
        val view = buildLockView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable and touchable (no NOT_FOCUSABLE / NOT_TOUCHABLE flags): it swallows taps,
            // the back button and the volume keys, so nothing underneath can be used.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val ok = add(view, params)
        if (ok) {
            lockView = view
            showLying(message)
        }
        return ok
    }

    /** The normal lock: the user is lying, here is why it matters. */
    fun showLying(message: String) {
        kickerText?.text = "Terkunci"
        titleText?.text = "Bangun dulu."
        bodyText?.text = message
        quoteText?.visibility = View.GONE
        hidePhrase()
    }

    /** The volume was turned down: say what it cost. */
    fun showTamper(attempts: Int, extraMinutes: Int, needsPhrase: Boolean) {
        kickerText?.text = "Terkunci · mengecilkan volume: ${attempts}×"
        titleText?.text = "Mengecilkan suara tidak menghapus kenyataan."
        bodyText?.text = buildString {
            append("Volume dikembalikan ke 100%. Percobaan ini menambah $extraMinutes menit kunci ")
            append("dan masuk laporan untuk temanmu.")
            if (needsPhrase) append(" Untuk membuka nanti, kamu juga harus mengetik kalimat komitmen.")
        }
        quoteText?.visibility = View.GONE
    }

    /** Sat up, but a penalty is still being served. */
    fun showPenalty(secondsLeft: Long, needsPhrase: Boolean) {
        kickerText?.text = "Hukuman berjalan"
        titleText?.text = "Belum selesai."
        bodyText?.text = buildString {
            append("Kamu sudah duduk, tapi tadi mencoba membungkam alarm. Kunci bertahan ")
            append("${secondsLeft / 60}:${"%02d".format(secondsLeft % 60)} lagi.")
            if (needsPhrase) append(" Setelah itu, ketik kalimat komitmenmu.")
        }
        angleText?.text = "Layar tetap terkunci selama layar menyala. Mematikan layar menghentikan hitungannya."
        hidePhrase()
    }

    /** Last step for repeat offenders: type the sentence to leave. */
    fun askPhrase(phrase: TypedPhrase, onMatched: () -> Unit) {
        val box = phraseBox ?: return
        kickerText?.text = "Satu langkah lagi"
        titleText?.text = "Tulis ulang janjimu."
        bodyText?.text = "Kata demi kata. Besar-kecil huruf dan tanda baca tidak dihitung."
        quoteText?.text = "“${phrase.text}”"
        quoteText?.visibility = View.VISIBLE
        angleText?.text = ""
        if (box.visibility == View.VISIBLE) return
        box.visibility = View.VISIBLE
        val input = box.getChildAt(0) as EditText
        input.setText("")
        phraseProgress?.text = "0 dari ${phrase.wordCount} kata benar"
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString().orEmpty()
                phraseProgress?.text = "${phrase.correctWords(typed)} dari ${phrase.wordCount} kata benar"
                if (phrase.matches(typed)) onMatched()
            }
        })
        input.requestFocus()
    }

    private fun hidePhrase() {
        val box = phraseBox ?: return
        if (box.visibility == View.GONE) return
        box.visibility = View.GONE
        // A fresh EditText next time, so no stale listener from an earlier lock survives.
        box.removeViewAt(0)
        box.addView(newPhraseInput(), 0)
    }

    fun hide() {
        val view = lockView ?: return
        lockView = null
        kickerText = null
        titleText = null
        bodyText = null
        quoteText = null
        angleText = null
        phraseBox = null
        phraseProgress = null
        remove(view)
    }

    fun update(screenElevationDeg: Float, thresholdDeg: Float) {
        if (isAskingPhrase) return
        val text = angleText ?: return
        text.text = if (screenElevationDeg.isFinite()) {
            "Duduk tegak untuk membuka · sudut ${screenElevationDeg.roundToInt()}°, batas ${thresholdDeg.roundToInt()}°"
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
                setColor(PAPER)
                setStroke(dp(1.5f), GOLD)
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

    private fun font(id: Int): Typeface? = try {
        ResourcesCompat.getFont(context, id)
    } catch (e: Exception) {
        null
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).roundToInt()

    private fun label(size: Float, color: Int, face: Typeface? = null, spacing: Float = 1.3f) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size))
        if (face != null) typeface = face
        gravity = Gravity.START
        setLineSpacing(0f, spacing)
    }

    private fun newPhraseInput() = EditText(context).apply {
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        hint = "Ketik di sini…"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setStroke(dp(1f), HAIRLINE)
        }
    }

    private fun buildLockView(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(40f), dp(28f), dp(24f))
            addView(label(12f, MUTED).apply { letterSpacing = 0.08f }.also { kickerText = it })
            addView(space(dp(28f)))
            addView(label(36f, TEXT, serif, 1.12f).also { titleText = it })
            addView(space(dp(16f)))
            addView(label(16f, BODY).also { bodyText = it })
            addView(space(dp(20f)))
            addView(label(18f, TEXT, serifItalic).apply {
                visibility = View.GONE
                setPadding(0, dp(4f), 0, dp(4f))
            }.also { quoteText = it })
            addView(space(dp(16f)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(newPhraseInput())
                addView(label(12f, GOLD).apply { setPadding(0, dp(8f), 0, 0) }.also { phraseProgress = it })
            }.also { phraseBox = it })
            addView(space(dp(20f)))
            addView(label(12f, MUTED).apply { text = "Membaca sensor…" }.also { angleText = it })
        }

        val emergency = Button(context).apply {
            text = "Darurat"
            isAllCaps = false
            setTextColor(MUTED)
            background = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                setStroke(dp(1f), HAIRLINE)
            }
            setPadding(dp(28f), dp(10f), dp(28f), dp(10f))
            contentDescription = "Darurat: buka telepon dan jeda penjaga 3 menit"
            setOnClickListener { onEmergency() }
        }

        return object : FrameLayout(context) {
            // Swallow the back and volume keys: they can't dismiss anything or silence the alarm.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> true
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onVolumeKey(true)
                    true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> true
                else -> super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(INK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            addView(ScrollView(context).apply {
                isFillViewport = true
                addView(column)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { bottomMargin = dp(96f) })
            addView(emergency, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(36f) })
            requestFocus()
        }
    }

    private fun space(heightPx: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, heightPx)
    }

    private companion object {
        const val TAG = "LockOverlay"
        const val INK = 0xFC14161B.toInt()
        const val INK_SOLID = 0xFF1C1F26.toInt()
        const val PAPER = 0xFFF5F0E6.toInt()
        const val TEXT = 0xFFEDE6D8.toInt()
        const val BODY = 0xFFB9B2A4.toInt()
        const val MUTED = 0xFF9A9488.toInt()
        const val GOLD = 0xFFD4A84B.toInt()
        const val HAIRLINE = 0xFF3A3D45.toInt()
    }
}
