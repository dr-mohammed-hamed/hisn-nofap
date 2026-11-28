package com.drmohammed.antiporn.ui.common

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Rotation-safe [DialogFragment] for selecting an unaffiliated secondary profile
 * and confirming permanent Device Owner profile removal.
 */
class SecondaryProfileResolutionDialogFragment : DialogFragment() {

    private var step: Int = STEP_SELECT_PROFILE
    private var selectedIndex: Int = 0
    private var confirmChecked: Boolean = false
    private var profiles: List<SecondaryProfileDetector.UnaffiliatedProfileInfo> = emptyList()
    private var activeChkConfirm: MaterialCheckBox? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val args = arguments ?: Bundle.EMPTY
        step = savedInstanceState?.getInt(STATE_STEP)
            ?: args.getInt(ARG_STEP, STEP_SELECT_PROFILE)
        selectedIndex = savedInstanceState?.getInt(STATE_SELECTED_INDEX)
            ?: args.getInt(ARG_SELECTED_INDEX, 0)
        confirmChecked = savedInstanceState?.getBoolean(STATE_CONFIRM_CHECKED, false) ?: false

        val profilesJson = args.getString(ARG_PROFILES_JSON).orEmpty()
        val handles = BundleCompat.getParcelableArrayList(
            args,
            ARG_USER_HANDLES,
            UserHandle::class.java
        ).orEmpty()
        profiles = decodeProfiles(profilesJson, handles)

        if (profiles.isEmpty()) {
            dismissAllowingStateLoss()
            return MaterialAlertDialogBuilder(ctx).create()
        }

        return if (step == STEP_CONFIRM_REMOVE) {
            val target = profiles.getOrElse(selectedIndex) { profiles.first() }
            createConfirmDialog(ctx, target)
        } else {
            createSelectionDialog(ctx)
        }
    }

    private fun createSelectionDialog(ctx: Context): AlertDialog {
        val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQUEST_KEY
        val labels = profiles.map { profile ->
            SecondaryProfileDetector.formatProfileDisplayEntry(ctx, profile)
        }.toTypedArray()

        selectedIndex = selectedIndex.coerceIn(0, profiles.lastIndex)

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_select_profile_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which.coerceIn(0, profiles.lastIndex)
            }
            .setPositiveButton(R.string.btn_open_cloned_app_settings) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    reqKey,
                    bundleOf(RESULT_ACTION to ACTION_OPEN_SETTINGS)
                )
            }
            .setNeutralButton(R.string.btn_remove_selected_profile_dpm) { d, _ ->
                val chosen = profiles.getOrElse(selectedIndex) { profiles.first() }
                d.dismiss()
                parentFragmentManager.showSafely(TAG_CONFIRM) {
                    newConfirmInstance(chosen, reqKey)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    @SuppressLint("InflateParams")
    private fun createConfirmDialog(
        ctx: Context,
        profile: SecondaryProfileDetector.UnaffiliatedProfileInfo
    ): AlertDialog {
        val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQUEST_KEY
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_remove_profile, null)
        val tvHeader = dialogView.findViewById<TextView>(R.id.tvProfileRemoveHeader)
        val tvWarning = dialogView.findViewById<TextView>(R.id.tvProfileRemoveWarning)
        val chkConfirm = dialogView.findViewById<MaterialCheckBox>(R.id.chkConfirmProfileRemoval)

        activeChkConfirm = chkConfirm
        tvHeader.text = SecondaryProfileDetector.formatProfileDisplayEntry(ctx, profile)
        tvWarning.text = buildConfirmWarningMessage(ctx, profile)
        chkConfirm.isChecked = confirmChecked

        val confirmDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_confirm_remove_clone_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_remove_selected_profile_dpm) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    reqKey,
                    bundleOf(
                        RESULT_ACTION to ACTION_CONFIRM_REMOVE,
                        RESULT_PROFILE_JSON to encodeProfiles(listOf(profile)),
                        RESULT_USER_HANDLE to profile.userHandle
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        confirmDialog.setOnShowListener {
            val positiveButton = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton?.isEnabled = chkConfirm.isChecked
            chkConfirm.setOnCheckedChangeListener { _, isChecked ->
                confirmChecked = isChecked
                positiveButton?.isEnabled = isChecked
            }
        }
        return confirmDialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
        outState.putBoolean(STATE_CONFIRM_CHECKED, activeChkConfirm?.isChecked ?: confirmChecked)
    }

    override fun onDestroy() {
        activeChkConfirm = null
        super.onDestroy()
    }

    private fun buildConfirmWarningMessage(
        context: Context,
        profile: SecondaryProfileDetector.UnaffiliatedProfileInfo
    ): String {
        return when (profile.profileType) {
            SecondaryProfileDetector.ProfileType.CLONE_APP -> {
                if (profile.clonedAppNames.isNotEmpty()) {
                    context.getString(
                        R.string.dialog_confirm_remove_clone_single_msg,
                        profile.clonedAppNames.joinToString(", "),
                        profile.userId
                    )
                } else {
                    context.getString(R.string.dialog_confirm_remove_empty_clone_msg, profile.userId)
                }
            }
            SecondaryProfileDetector.ProfileType.WORK_PROFILE,
            SecondaryProfileDetector.ProfileType.PRIVATE_SPACE,
            SecondaryProfileDetector.ProfileType.SECONDARY_USER -> {
                val typeLabel = context.getString(SecondaryProfileDetector.getProfileTypeLabelRes(profile.profileType))
                context.getString(R.string.dialog_confirm_remove_work_or_private_msg, typeLabel, profile.userId)
            }
        }
    }

    companion object {
        const val TAG_SELECT = "SecondaryProfileSelectDialog"
        const val TAG_CONFIRM = "SecondaryProfileConfirmDialog"
        const val REQUEST_KEY = "secondary_profile_resolution_request"
        const val RESULT_ACTION = "result_action"
        const val RESULT_PROFILE_JSON = "result_profile_json"
        const val RESULT_USER_HANDLE = "result_user_handle"

        const val ACTION_OPEN_SETTINGS = "action_open_settings"
        const val ACTION_CONFIRM_REMOVE = "action_confirm_remove"

        const val STEP_SELECT_PROFILE = 0
        const val STEP_CONFIRM_REMOVE = 1

        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_STEP = "arg_step"
        private const val ARG_SELECTED_INDEX = "arg_selected_index"
        private const val ARG_PROFILES_JSON = "arg_profiles_json"
        private const val ARG_USER_HANDLES = "arg_user_handles"

        private const val STATE_STEP = "state_step"
        private const val STATE_SELECTED_INDEX = "state_selected_index"
        private const val STATE_CONFIRM_CHECKED = "state_confirm_checked"

        fun newSelectionInstance(
            secondaries: List<SecondaryProfileDetector.UnaffiliatedProfileInfo>,
            requestKey: String = REQUEST_KEY
        ): SecondaryProfileResolutionDialogFragment {
            return SecondaryProfileResolutionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_STEP, STEP_SELECT_PROFILE)
                    putInt(ARG_SELECTED_INDEX, 0)
                    putString(ARG_PROFILES_JSON, encodeProfiles(secondaries))
                    putParcelableArrayList(ARG_USER_HANDLES, ArrayList(secondaries.map { it.userHandle }))
                }
            }
        }

        fun newConfirmInstance(
            profile: SecondaryProfileDetector.UnaffiliatedProfileInfo,
            requestKey: String = REQUEST_KEY
        ): SecondaryProfileResolutionDialogFragment {
            return SecondaryProfileResolutionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_STEP, STEP_CONFIRM_REMOVE)
                    putInt(ARG_SELECTED_INDEX, 0)
                    putString(ARG_PROFILES_JSON, encodeProfiles(listOf(profile)))
                    putParcelableArrayList(ARG_USER_HANDLES, arrayListOf(profile.userHandle))
                }
            }
        }

        fun extractConfirmedProfile(bundle: Bundle): SecondaryProfileDetector.UnaffiliatedProfileInfo? {
            val handle = BundleCompat.getParcelable(bundle, RESULT_USER_HANDLE, UserHandle::class.java)
                ?: return null
            val json = bundle.getString(RESULT_PROFILE_JSON).orEmpty()
            return decodeProfiles(json, listOf(handle)).firstOrNull()
        }

        private fun encodeProfiles(profiles: List<SecondaryProfileDetector.UnaffiliatedProfileInfo>): String {
            val arr = JSONArray()
            for (profile in profiles) {
                arr.put(
                    JSONObject().apply {
                        put("userId", profile.userId)
                        put("profileType", profile.profileType.name)
                        put("clonedAppNames", JSONArray(profile.clonedAppNames))
                        put("clonedPackageNames", JSONArray(profile.clonedPackageNames))
                    }
                )
            }
            return arr.toString()
        }

        private fun decodeProfiles(
            json: String,
            handles: List<UserHandle>
        ): List<SecondaryProfileDetector.UnaffiliatedProfileInfo> {
            if (json.isBlank() || handles.isEmpty()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val handle = handles.getOrNull(i) ?: continue
                        val userId = obj.optInt("userId", -1)
                        val typeName = obj.optString("profileType", "")
                        val profileType = runCatching {
                            SecondaryProfileDetector.ProfileType.valueOf(typeName)
                        }.onFailure {
                            Timber.tag(TAG_SELECT).w(it, "Unknown secondary profileType: %s", typeName)
                        }.getOrNull() ?: continue
                        val appNames = optStringList(obj, "clonedAppNames")
                        val pkgNames = optStringList(obj, "clonedPackageNames")
                        add(
                            SecondaryProfileDetector.UnaffiliatedProfileInfo(
                                userHandle = handle,
                                userId = userId,
                                profileType = profileType,
                                clonedAppNames = appNames,
                                clonedPackageNames = pkgNames
                            )
                        )
                    }
                }
            }.onFailure {
                Timber.tag(TAG_SELECT).w(it, "Failed decoding secondary profiles JSON")
            }.getOrElse { emptyList() }
        }

        private fun optStringList(obj: JSONObject, key: String): List<String> {
            val arr = obj.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank() && v != "null") add(v)
                }
            }
        }
    }
}
