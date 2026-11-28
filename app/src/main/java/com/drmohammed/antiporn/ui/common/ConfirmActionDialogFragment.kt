package com.drmohammed.antiporn.ui.common

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.drmohammed.antiporn.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Reusable, rotation-safe confirmation and alert [DialogFragment] configured via [Bundle]
 * arguments and delivering outcomes via Fragment Result API.
 */
class ConfirmActionDialogFragment : DialogFragment() {

    private var resultDelivered = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val args = arguments ?: Bundle.EMPTY

        val title = resolveText(args, ARG_TITLE_RES, ARG_TITLE_TEXT)
        val message = resolveText(args, ARG_MESSAGE_RES, ARG_MESSAGE_TEXT)
        val positive = resolveText(args, ARG_POSITIVE_RES, ARG_POSITIVE_TEXT)
            ?: ctx.getString(android.R.string.ok)
        val negative = resolveText(args, ARG_NEGATIVE_RES, ARG_NEGATIVE_TEXT)
        val neutral = resolveText(args, ARG_NEUTRAL_RES, ARG_NEUTRAL_TEXT)
        val cancelableFlag = args.getBoolean(ARG_CANCELABLE, true)
        val positiveDanger = args.getBoolean(ARG_POSITIVE_DANGER, false)

        isCancelable = cancelableFlag

        val builder = MaterialAlertDialogBuilder(ctx)
            .setCancelable(cancelableFlag)
            .setPositiveButton(positive) { _, _ ->
                deliverResult(BUTTON_POSITIVE)
            }

        if (!title.isNullOrBlank()) {
            builder.setTitle(title)
        }
        if (!message.isNullOrBlank()) {
            builder.setMessage(message)
        }
        if (!negative.isNullOrBlank()) {
            builder.setNegativeButton(negative) { _, _ ->
                deliverResult(BUTTON_NEGATIVE)
            }
        }
        if (!neutral.isNullOrBlank()) {
            builder.setNeutralButton(neutral) { _, _ ->
                deliverResult(BUTTON_NEUTRAL)
            }
        }

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(cancelableFlag)
        if (positiveDanger) {
            dialog.setOnShowListener {
                val dangerColor = MaterialColors.getColor(
                    ctx,
                    androidx.appcompat.R.attr.colorError,
                    ContextCompat.getColor(ctx, R.color.status_red)
                )
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(dangerColor)
            }
        }
        return dialog
    }

    private fun deliverResult(button: String) {
        if (resultDelivered) return
        resultDelivered = true
        val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY
        val payload = arguments?.getBundle(ARG_PAYLOAD)
        parentFragmentManager.setFragmentResult(
            reqKey,
            bundleOf(
                RESULT_BUTTON to button,
                RESULT_PAYLOAD to payload
            )
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        deliverResult(BUTTON_CANCELLED)
    }

    private fun resolveText(args: Bundle, resKey: String, textKey: String): String? {
        val explicit = args.getString(textKey)
        if (!explicit.isNullOrBlank()) return explicit
        val resId = args.getInt(resKey, 0)
        return if (resId != 0) getString(resId) else null
    }

    companion object {
        const val DEFAULT_REQUEST_KEY = "confirm_action_dialog_result"
        const val RESULT_BUTTON = "result_button"
        const val RESULT_PAYLOAD = "result_payload"

        const val BUTTON_POSITIVE = "POSITIVE"
        const val BUTTON_NEGATIVE = "NEGATIVE"
        const val BUTTON_NEUTRAL = "NEUTRAL"
        const val BUTTON_CANCELLED = "CANCELLED"

        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_TITLE_RES = "arg_title_res"
        private const val ARG_TITLE_TEXT = "arg_title_text"
        private const val ARG_MESSAGE_RES = "arg_message_res"
        private const val ARG_MESSAGE_TEXT = "arg_message_text"
        private const val ARG_POSITIVE_RES = "arg_positive_res"
        private const val ARG_POSITIVE_TEXT = "arg_positive_text"
        private const val ARG_NEGATIVE_RES = "arg_negative_res"
        private const val ARG_NEGATIVE_TEXT = "arg_negative_text"
        private const val ARG_NEUTRAL_RES = "arg_neutral_res"
        private const val ARG_NEUTRAL_TEXT = "arg_neutral_text"
        private const val ARG_CANCELABLE = "arg_cancelable"
        private const val ARG_POSITIVE_DANGER = "arg_positive_danger"
        private const val ARG_PAYLOAD = "arg_payload"

        fun newInstance(
            requestKey: String = DEFAULT_REQUEST_KEY,
            @StringRes titleRes: Int = 0,
            titleText: String? = null,
            @StringRes messageRes: Int = 0,
            messageText: String? = null,
            @StringRes positiveRes: Int = android.R.string.ok,
            positiveText: String? = null,
            @StringRes negativeRes: Int = 0,
            negativeText: String? = null,
            @StringRes neutralRes: Int = 0,
            neutralText: String? = null,
            cancelable: Boolean = true,
            positiveDanger: Boolean = false,
            payload: Bundle? = null
        ): ConfirmActionDialogFragment {
            return ConfirmActionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_TITLE_RES, titleRes)
                    if (titleText != null) putString(ARG_TITLE_TEXT, titleText)
                    putInt(ARG_MESSAGE_RES, messageRes)
                    if (messageText != null) putString(ARG_MESSAGE_TEXT, messageText)
                    putInt(ARG_POSITIVE_RES, positiveRes)
                    if (positiveText != null) putString(ARG_POSITIVE_TEXT, positiveText)
                    putInt(ARG_NEGATIVE_RES, negativeRes)
                    if (negativeText != null) putString(ARG_NEGATIVE_TEXT, negativeText)
                    putInt(ARG_NEUTRAL_RES, neutralRes)
                    if (neutralText != null) putString(ARG_NEUTRAL_TEXT, neutralText)
                    putBoolean(ARG_CANCELABLE, cancelable)
                    putBoolean(ARG_POSITIVE_DANGER, positiveDanger)
                    if (payload != null) putBundle(ARG_PAYLOAD, payload)
                }
            }
        }
    }
}
