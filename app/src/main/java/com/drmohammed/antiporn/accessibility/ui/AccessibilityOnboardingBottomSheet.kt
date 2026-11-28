package com.drmohammed.antiporn.accessibility.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.databinding.BottomSheetAccessibilityOnboardingBinding
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import timber.log.Timber

/**
 * نافذة سفلية إرشادية (Material 3) لمساعدة المستخدمين غير التقنيين (خصوصاً في أجهزة QR OOBE)
 * على تفعيل خدمة إمكانية الوصول في 3 خطوات واضحة، مع إرشاد شرطي لإعدادات أندرويد 13+ المقيدة.
 */
class AccessibilityOnboardingBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAccessibilityOnboardingBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAccessibilityOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)

        val reason = arguments?.getString(ARG_REASON).orEmpty()
        val isFromGuardian = arguments?.getBoolean(ARG_IS_FROM_GUARDIAN, false) ?: false

        binding.tvOnboardingReason.text = resolveReasonText(reason, isFromGuardian)

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.cardRestrictedSettingsHelper.visibility = View.VISIBLE
            binding.btnOpenAppInfo.setOnClickListener {
                openAppInfoSettings()
            }
        } else {
            binding.cardRestrictedSettingsHelper.visibility = View.GONE
        }

        binding.btnOnboardingLater.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        val isNowActive = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(ctx)
        if (isNowActive && isAdded) {
            dismissAllowingStateLoss()
        }
    }

    private fun resolveReasonText(reason: String, isFromGuardian: Boolean): String {
        if (isFromGuardian || reason == REASON_GUARDIAN) {
            return getString(R.string.a11y_onboarding_reason_guardian)
        }
        return when (reason) {
            REASON_PAGE_SHIELD -> getString(R.string.a11y_onboarding_reason_page_shield)
            REASON_WAQFA_INSTANT -> getString(R.string.a11y_onboarding_reason_waqfa)
            REASON_VISION_AI -> getString(R.string.a11y_onboarding_reason_vision_ai)
            else -> getString(R.string.a11y_onboarding_reason_general)
        }
    }

    private fun openAccessibilitySettings() {
        val ctx = context ?: return
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct accessibility settings intent failed, falling back to general settings")
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Could not open system settings")
                Toast.makeText(ctx, R.string.waqfa_could_not_open_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAppInfoSettings() {
        val ctx = context ?: return
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed opening app info settings for restricted settings unlock")
            Toast.makeText(ctx, R.string.waqfa_could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "A11yOnboardingSheet"
        const val REASON_PAGE_SHIELD = "PAGE_SHIELD"
        const val REASON_WAQFA_INSTANT = "WAQFA_INSTANT"
        const val REASON_VISION_AI = "VISION_AI"
        const val REASON_GUARDIAN = "GUARDIAN"
        const val REASON_GENERAL = "GENERAL"

        private const val ARG_REASON = "arg_reason"
        private const val ARG_IS_FROM_GUARDIAN = "arg_is_from_guardian"

        fun newInstance(
            reason: String,
            isFromGuardian: Boolean = false
        ): AccessibilityOnboardingBottomSheet {
            return AccessibilityOnboardingBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_REASON, reason)
                    putBoolean(ARG_IS_FROM_GUARDIAN, isFromGuardian)
                }
            }
        }

        fun reasonFromSet(
            reasons: Set<AccessibilityRequirementReason>,
            isFromGuardian: Boolean = false
        ): String {
            if (isFromGuardian) return REASON_GUARDIAN
            return when {
                reasons.contains(AccessibilityRequirementReason.PAGE_SHIELD) -> REASON_PAGE_SHIELD
                reasons.contains(AccessibilityRequirementReason.WAQFA_INSTANT) -> REASON_WAQFA_INSTANT
                reasons.contains(AccessibilityRequirementReason.VISION_AI) -> REASON_VISION_AI
                else -> REASON_GENERAL
            }
        }

        /**
         * يعرض النافذة بأمان مع منع التكرار أو انهيار تغيّر الحالة بعد حفظها.
         */
        fun showIfNotShowing(
            fragmentManager: FragmentManager,
            reason: String,
            isFromGuardian: Boolean = false
        ): Boolean {
            if (fragmentManager.isStateSaved) return false
            if (fragmentManager.findFragmentByTag(TAG) != null) return false
            return try {
                newInstance(reason = reason, isFromGuardian = isFromGuardian)
                    .show(fragmentManager, TAG)
                true
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Could not show AccessibilityOnboardingBottomSheet")
                false
            }
        }
    }
}
