package com.drmohammed.antiporn.provisioning.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.R
import timber.log.Timber

/**
 * Non-focus-stealing floating numeric keypad overlay for entering ADB Wireless Debugging
 * pairing code without dismissing the system pairing dialog in Settings.
 *
 * Employs FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCH_MODAL to guarantee that the underlying
 * Settings activity retains window focus, avoiding dialog cancellation or IME conflicts.
 */
class FloatingPairingOverlay(
    context: Context,
    private val onSubmitCode: (String) -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val themedContext = ContextThemeWrapper(appContext, R.style.Theme_Antiporn)
    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var tvDigits: TextView? = null
    private var tvStatus: TextView? = null
    private var btnSubmit: TextView? = null
    private var progressBar: ProgressBar? = null
    private var keypadGrid: GridLayout? = null
    private val digits = StringBuilder()

    @Volatile
    var isShowing: Boolean = false
        private set

    fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(pairingPort: Int) {
        mainHandler.post {
            if (!canShow() || wm == null) {
                Timber.tag(TAG).w("Cannot show FloatingPairingOverlay: canDrawOverlays=${canShow()}, wm=${wm != null}")
                return@post
            }
            if (isShowing && rootView != null) {
                tvStatus?.text = appContext.getString(R.string.provisioning_prompt_desc, pairingPort)
                tvStatus?.setTextColor(ContextCompat.getColor(appContext, R.color.md_theme_dark_onSurfaceVariant))
                return@post
            }
            try {
                val container = buildOverlayView(pairingPort)
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

                val d = appContext.resources.displayMetrics.density
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    flags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (16 * d).toInt()
                }

                wm.addView(container, params)
                rootView = container
                isShowing = true
                Timber.tag(TAG).i("FloatingPairingOverlay displayed successfully for port $pairingPort")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed showing FloatingPairingOverlay")
                isShowing = false
                rootView = null
            }
        }
    }

    fun showSubmittingState() {
        mainHandler.post {
            keypadGrid?.visibility = View.GONE
            btnSubmit?.isEnabled = false
            btnSubmit?.alpha = 0.5f
            progressBar?.visibility = View.VISIBLE
            tvStatus?.text = appContext.getString(R.string.provisioning_in_progress_title)
            tvStatus?.setTextColor(ContextCompat.getColor(appContext, R.color.status_green))
        }
    }

    fun showError(message: String) {
        mainHandler.post {
            progressBar?.visibility = View.GONE
            keypadGrid?.visibility = View.VISIBLE
            btnSubmit?.isEnabled = digits.length == 6
            btnSubmit?.alpha = if (digits.length == 6) 1.0f else 0.5f
            tvStatus?.text = message
            tvStatus?.setTextColor(ContextCompat.getColor(appContext, R.color.status_red))
        }
    }

    fun dismiss() {
        mainHandler.post {
            val view = rootView
            rootView = null
            isShowing = false
            digits.clear()
            if (view != null && wm != null) {
                try {
                    wm.removeViewImmediate(view)
                    Timber.tag(TAG).i("FloatingPairingOverlay dismissed cleanly")
                } catch (_: Exception) {
                    try {
                        wm.removeView(view)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun buildOverlayView(pairingPort: Int): View {
        val d = appContext.resources.displayMetrics.density
        val pad = (14 * d).toInt()

        val colorSurface = ContextCompat.getColor(appContext, R.color.shield_hero_bg)
        val colorPrimary = ContextCompat.getColor(appContext, R.color.status_green)
        val colorOnSurface = ContextCompat.getColor(appContext, R.color.white)
        val colorOnSurfaceVariant = ContextCompat.getColor(appContext, R.color.md_theme_dark_onSurfaceVariant)
        val colorKeyBg = ContextCompat.getColor(appContext, R.color.md_theme_dark_surfaceVariant)
        val colorDisplayBg = ContextCompat.getColor(appContext, R.color.md_theme_dark_surfaceContainer)

        val card = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * d
                setColor(colorSurface)
                setStroke((1.5f * d).toInt(), colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (12 * d).toInt()
                setMargins(margin, 0, margin, 0)
            }
        }

        // Header: Title + Close Icon
        val header = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(themedContext).apply {
            text = appContext.getString(R.string.provisioning_prompt_title)
            setTextColor(colorOnSurface)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(themedContext).apply {
            text = "✕"
            setTextColor(colorOnSurfaceVariant)
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = (48 * d).toInt()
            minHeight = (48 * d).toInt()
            setOnClickListener {
                dismiss()
                onDismissed()
            }
        }
        header.addView(title)
        header.addView(btnClose)
        card.addView(header)

        // Status text
        val status = TextView(themedContext).apply {
            text = appContext.getString(R.string.provisioning_prompt_desc, pairingPort)
            setTextColor(colorOnSurfaceVariant)
            textSize = 12f
            setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
        }
        tvStatus = status
        card.addView(status)

        // 6-digit display
        val digitsDisplay = TextView(themedContext).apply {
            text = "_ _ _ _ _ _"
            setTextColor(colorOnSurface)
            textSize = 24f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(colorDisplayBg)
            }
        }
        tvDigits = digitsDisplay
        card.addView(digitsDisplay)

        // Progress bar for async submission
        val pb = ProgressBar(themedContext).apply {
            visibility = View.GONE
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        }
        progressBar = pb
        card.addView(pb)

        // Built-in Numeric Keypad (No IME / No soft keyboard steal)
        val grid = GridLayout(themedContext).apply {
            columnCount = 3
            rowCount = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * d).toInt()
            }
        }

        val clearLabel = appContext.getString(R.string.provisioning_overlay_clear)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", clearLabel, "0", "⌫")

        for (key in keys) {
            val keyBtn = TextView(themedContext).apply {
                text = key
                setTextColor(colorOnSurface)
                textSize = if (key == clearLabel) 13f else 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                height = (48 * d).toInt()
                background = GradientDrawable().apply {
                    cornerRadius = 10f * d
                    setColor(colorKeyBg)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    val m = (3 * d).toInt()
                    setMargins(m, m, m, m)
                }
                setOnClickListener {
                    when (key) {
                        clearLabel -> digits.clear()
                        "⌫" -> if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
                        else -> if (digits.length < 6) digits.append(key)
                    }
                    refreshDigitsUi()
                }
            }
            grid.addView(keyBtn)
        }
        keypadGrid = grid
        card.addView(grid)

        // Submit Button (Custom styled View to avoid Material inflate issues on ApplicationContext)
        val submit = TextView(themedContext).apply {
            text = appContext.getString(R.string.provisioning_action_submit)
            setTextColor(colorOnSurface)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            height = (48 * d).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(colorPrimary)
            }
            alpha = 0.5f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * d).toInt()
            }
            setOnClickListener {
                if (digits.length == 6) {
                    val code = digits.toString()
                    showSubmittingState()
                    onSubmitCode(code)
                }
            }
        }
        btnSubmit = submit
        card.addView(submit)

        refreshDigitsUi()
        return card
    }

    private fun refreshDigitsUi() {
        val padded = (0 until 6).joinToString(" ") { idx ->
            if (idx < digits.length) digits[idx].toString() else "_"
        }
        tvDigits?.text = padded
        val ready = digits.length == 6
        btnSubmit?.isEnabled = ready
        btnSubmit?.alpha = if (ready) 1.0f else 0.5f
    }

    companion object {
        private const val TAG = "AntiPorn_FloatPair"
    }
}
