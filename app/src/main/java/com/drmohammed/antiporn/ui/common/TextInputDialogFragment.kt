package com.drmohammed.antiporn.ui.common

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Reusable, rotation-safe text input [DialogFragment] configured via [Bundle] arguments
 * and communicating outcomes via Fragment Result API.
 */
class TextInputDialogFragment : DialogFragment() {

    private var activeInputLayout: TextInputLayout? = null
    private var activeEditText: EditText? = null
    private var resultDelivered = false

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val args = arguments ?: Bundle.EMPTY

        val titleText = resolveText(args, ARG_TITLE_RES, ARG_TITLE_TEXT)
        val messageText = resolveText(args, ARG_MESSAGE_RES, ARG_MESSAGE_TEXT)
        val hintText = resolveText(args, ARG_HINT_RES, ARG_HINT_TEXT)
        val positiveText = resolveText(args, ARG_POSITIVE_RES, ARG_POSITIVE_TEXT)
            ?: ctx.getString(R.string.btn_save)
        val negativeText = resolveText(args, ARG_NEGATIVE_RES, ARG_NEGATIVE_TEXT)
            ?: ctx.getString(R.string.dialog_btn_cancel)

        val customLayoutMode = args.getInt(ARG_CUSTOM_LAYOUT_MODE, CUSTOM_LAYOUT_NONE)
        val initialText = savedInstanceState?.getString(STATE_CURRENT_TEXT)
            ?: args.getString(ARG_INITIAL_TEXT).orEmpty()
        val restoredError = savedInstanceState?.getString(STATE_ERROR_TEXT)

        val contentView: View
        if (customLayoutMode == CUSTOM_LAYOUT_PARTNER_CODE) {
            contentView = layoutInflater.inflate(R.layout.dialog_add_partner, null)
            activeInputLayout = contentView.findViewById(R.id.tilPartnerCode)
            activeEditText = contentView.findViewById(R.id.etPartnerCode)
        } else {
            val density = resources.displayMetrics.density
            val horizontalPad = (20 * density).toInt()
            val topPad = (8 * density).toInt()

            val til = TextInputLayout(ctx).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                if (!hintText.isNullOrBlank()) {
                    hint = hintText
                }
            }
            val inputTypeVal = args.getInt(ARG_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            val singleLine = args.getBoolean(ARG_SINGLE_LINE, true)
            val maxLength = args.getInt(ARG_MAX_LENGTH, -1)
            val ltrTextDirection = args.getBoolean(ARG_LTR_TEXT_DIRECTION, false)

            val et = TextInputEditText(til.context).apply {
                inputType = inputTypeVal
                imeOptions = EditorInfo.IME_ACTION_DONE
                isSingleLine = singleLine
                if (singleLine) {
                    maxLines = 1
                }
                if (ltrTextDirection) {
                    textDirection = View.TEXT_DIRECTION_LTR
                }
                if (maxLength > 0) {
                    filters = arrayOf(InputFilter.LengthFilter(maxLength))
                }
            }
            til.addView(et)

            val frame = FrameLayout(ctx).apply {
                setPadding(horizontalPad, topPad, horizontalPad, 0)
                addView(til)
            }
            contentView = NestedScrollView(ctx).apply {
                isFillViewport = true
                addView(frame)
            }
            activeInputLayout = til
            activeEditText = et
        }

        activeEditText?.apply {
            setText(initialText)
            setSelection(text?.length ?: 0)
        }
        if (!restoredError.isNullOrBlank()) {
            activeInputLayout?.error = restoredError
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setView(contentView)
            .setPositiveButton(positiveText, null)
            .setNegativeButton(negativeText) { _, _ ->
                deliverCancelled()
            }
        if (!titleText.isNullOrBlank()) {
            builder.setTitle(titleText)
        }
        if (!messageText.isNullOrBlank()) {
            builder.setMessage(messageText)
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn?.setOnClickListener {
                onConfirmClicked(dialog, hintText)
            }
            activeEditText?.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    positiveBtn?.performClick()
                    true
                } else {
                    false
                }
            }
        }
        return dialog
    }

    private fun onConfirmClicked(dialog: AlertDialog, fallbackHint: String?) {
        val args = arguments ?: Bundle.EMPTY
        val raw = activeEditText?.text?.toString().orEmpty()
        val trimmed = raw.trim()
        val validationMode = args.getInt(ARG_VALIDATION_MODE, VALIDATION_NONE)

        val errorMsg: String? = when (validationMode) {
            VALIDATION_NON_BLANK -> {
                if (trimmed.isBlank()) fallbackHint ?: getString(R.string.error_unknown) else null
            }
            VALIDATION_PARTNER_CODE_6 -> {
                if (!PARTNER_CODE_REGEX.matches(trimmed)) {
                    getString(R.string.dialog_add_partner_code_hint)
                } else null
            }
            VALIDATION_INT_RANGE_1_180 -> {
                val parsed = trimmed.toIntOrNull()
                if (parsed == null || parsed !in 1..180) {
                    getString(R.string.waqfa_error_invalid_duration)
                } else null
            }
            VALIDATION_WEBSITE_BLOCK -> {
                if (BrowserUrlSanitizer.sanitize(raw) == null) {
                    getString(R.string.browser_filter_error_invalid_url)
                } else null
            }
            VALIDATION_WEBSITE_ALLOW -> {
                if (BrowserUrlSanitizer.sanitizeForAllowlist(raw) == null) {
                    getString(R.string.browser_filter_error_invalid_url)
                } else null
            }
            else -> null
        }

        if (errorMsg != null) {
            activeInputLayout?.error = errorMsg
            return
        }

        activeInputLayout?.error = null
        deliverConfirmed(trimmed)
        dialog.dismiss()
    }

    private fun deliverConfirmed(value: String) {
        if (resultDelivered) return
        resultDelivered = true
        val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY
        val payload = arguments?.getBundle(ARG_PAYLOAD)
        parentFragmentManager.setFragmentResult(
            reqKey,
            bundleOf(
                RESULT_CONFIRMED to true,
                RESULT_CANCELLED to false,
                RESULT_TEXT to value,
                RESULT_PAYLOAD to payload
            )
        )
    }

    private fun deliverCancelled() {
        if (resultDelivered) return
        resultDelivered = true
        val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY
        val payload = arguments?.getBundle(ARG_PAYLOAD)
        parentFragmentManager.setFragmentResult(
            reqKey,
            bundleOf(
                RESULT_CONFIRMED to false,
                RESULT_CANCELLED to true,
                RESULT_TEXT to "",
                RESULT_PAYLOAD to payload
            )
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        deliverCancelled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        activeEditText?.let { outState.putString(STATE_CURRENT_TEXT, it.text?.toString().orEmpty()) }
        outState.putString(STATE_ERROR_TEXT, activeInputLayout?.error?.toString())
    }

    override fun onDestroy() {
        activeEditText = null
        activeInputLayout = null
        super.onDestroy()
    }

    private fun resolveText(args: Bundle, resKey: String, textKey: String): String? {
        val explicit = args.getString(textKey)
        if (!explicit.isNullOrBlank()) return explicit
        val resId = args.getInt(resKey, 0)
        return if (resId != 0) getString(resId) else null
    }

    companion object {
        const val DEFAULT_REQUEST_KEY = "text_input_dialog_result"
        const val RESULT_TEXT = "result_text"
        const val RESULT_CONFIRMED = "result_confirmed"
        const val RESULT_CANCELLED = "result_cancelled"
        const val RESULT_PAYLOAD = "result_payload"

        const val CUSTOM_LAYOUT_NONE = 0
        const val CUSTOM_LAYOUT_PARTNER_CODE = 1

        const val VALIDATION_NONE = 0
        const val VALIDATION_NON_BLANK = 1
        const val VALIDATION_PARTNER_CODE_6 = 2
        const val VALIDATION_INT_RANGE_1_180 = 3
        const val VALIDATION_WEBSITE_BLOCK = 4
        const val VALIDATION_WEBSITE_ALLOW = 5

        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_TITLE_RES = "arg_title_res"
        private const val ARG_TITLE_TEXT = "arg_title_text"
        private const val ARG_MESSAGE_RES = "arg_message_res"
        private const val ARG_MESSAGE_TEXT = "arg_message_text"
        private const val ARG_HINT_RES = "arg_hint_res"
        private const val ARG_HINT_TEXT = "arg_hint_text"
        private const val ARG_INITIAL_TEXT = "arg_initial_text"
        private const val ARG_POSITIVE_RES = "arg_positive_res"
        private const val ARG_POSITIVE_TEXT = "arg_positive_text"
        private const val ARG_NEGATIVE_RES = "arg_negative_res"
        private const val ARG_NEGATIVE_TEXT = "arg_negative_text"
        private const val ARG_INPUT_TYPE = "arg_input_type"
        private const val ARG_MAX_LENGTH = "arg_max_length"
        private const val ARG_SINGLE_LINE = "arg_single_line"
        private const val ARG_LTR_TEXT_DIRECTION = "arg_ltr_text_direction"
        private const val ARG_CUSTOM_LAYOUT_MODE = "arg_custom_layout_mode"
        private const val ARG_VALIDATION_MODE = "arg_validation_mode"
        private const val ARG_PAYLOAD = "arg_payload"

        private const val STATE_CURRENT_TEXT = "state_current_text"
        private const val STATE_ERROR_TEXT = "state_error_text"
        private val PARTNER_CODE_REGEX = Regex("^[A-Za-z0-9]{6}$")

        fun newInstance(
            requestKey: String = DEFAULT_REQUEST_KEY,
            @StringRes titleRes: Int = 0,
            titleText: String? = null,
            @StringRes messageRes: Int = 0,
            messageText: String? = null,
            @StringRes hintRes: Int = 0,
            hintText: String? = null,
            initialText: String? = null,
            @StringRes positiveRes: Int = R.string.btn_save,
            positiveText: String? = null,
            @StringRes negativeRes: Int = R.string.dialog_btn_cancel,
            negativeText: String? = null,
            inputType: Int = InputType.TYPE_CLASS_TEXT,
            maxLength: Int = -1,
            singleLine: Boolean = true,
            ltrTextDirection: Boolean = false,
            customLayoutMode: Int = CUSTOM_LAYOUT_NONE,
            validationMode: Int = VALIDATION_NONE,
            payload: Bundle? = null
        ): TextInputDialogFragment {
            return TextInputDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_TITLE_RES, titleRes)
                    if (titleText != null) putString(ARG_TITLE_TEXT, titleText)
                    putInt(ARG_MESSAGE_RES, messageRes)
                    if (messageText != null) putString(ARG_MESSAGE_TEXT, messageText)
                    putInt(ARG_HINT_RES, hintRes)
                    if (hintText != null) putString(ARG_HINT_TEXT, hintText)
                    if (initialText != null) putString(ARG_INITIAL_TEXT, initialText)
                    putInt(ARG_POSITIVE_RES, positiveRes)
                    if (positiveText != null) putString(ARG_POSITIVE_TEXT, positiveText)
                    putInt(ARG_NEGATIVE_RES, negativeRes)
                    if (negativeText != null) putString(ARG_NEGATIVE_TEXT, negativeText)
                    putInt(ARG_INPUT_TYPE, inputType)
                    putInt(ARG_MAX_LENGTH, maxLength)
                    putBoolean(ARG_SINGLE_LINE, singleLine)
                    putBoolean(ARG_LTR_TEXT_DIRECTION, ltrTextDirection)
                    putInt(ARG_CUSTOM_LAYOUT_MODE, customLayoutMode)
                    putInt(ARG_VALIDATION_MODE, validationMode)
                    if (payload != null) putBundle(ARG_PAYLOAD, payload)
                }
            }
        }
    }
}
