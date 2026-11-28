package com.drmohammed.antiporn.provisioning.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import timber.log.Timber

internal data class WizardClickCallbacks(
    val onConnectWifi: () -> Unit,
    val onOpenDevOptions: () -> Unit,
    val onLaunchAdjacentSettings: () -> Unit,
    val onPairAndActivate: () -> Unit
)

/**
 * Binds and updates views for [ProvisioningWizardActivity], keeping standard and
 * split-screen layouts synchronized without embedding business or coroutine logic.
 */
internal class ProvisioningWizardViewBinder {

    private lateinit var step1Circle: FrameLayout
    private lateinit var step1Number: TextView
    private lateinit var step1Check: ImageView
    private lateinit var step1Label: TextView
    private lateinit var stepperLine1: View
    private lateinit var step2Circle: FrameLayout
    private lateinit var step2Number: TextView
    private lateinit var step2Label: TextView

    private lateinit var cardWifiWarning: MaterialCardView
    private lateinit var btnConnectWifi: MaterialButton
    private lateinit var cardOemWarning: MaterialCardView
    private lateinit var tvOemWarningTitle: TextView
    private lateinit var tvOemWarningDesc: TextView
    private var ivOemWarningIcon: ImageView? = null
    private lateinit var cardOemPermissionWarning: MaterialCardView
    private lateinit var tvOemPermissionWarningTitle: TextView
    private lateinit var tvOemPermissionWarningDesc: TextView

    private lateinit var layoutStandardWizard: View
    private lateinit var layoutSplitScreenMode: View
    private lateinit var tvSplitRadarStatus: TextView
    private lateinit var ivSplitRadarIcon: ImageView
    private lateinit var etSplitPairingCode: TextInputEditText
    private lateinit var btnSplitPairAndActivate: MaterialButton
    private lateinit var btnSplitReopenSettings: MaterialButton

    private lateinit var cardNotificationTip: View
    private lateinit var btnTrySplitScreen: MaterialButton
    private lateinit var cardXiaomiSplitHint: View
    private lateinit var btnStartXiaomiSplit: MaterialButton
    private lateinit var tvCableQuickHint: TextView

    private lateinit var btnCopyWebAdbLink: MaterialButton
    private lateinit var btnOpenWebAdb: MaterialButton

    private lateinit var cardStep2: MaterialCardView
    private lateinit var tvStep2Desc: TextView
    private lateinit var btnOpenDevOptions: MaterialButton
    private lateinit var cardStep3: MaterialCardView
    private lateinit var tvRadarStatus: TextView
    private lateinit var ivRadarIcon: ImageView
    private lateinit var etPairingCode: TextInputEditText
    private lateinit var btnPairAndActivate: MaterialButton

    private var layoutStepper: View? = null
    private var cardLegacyPureSite: View? = null
    private var btnOpenPureSite: View? = null

    fun bindViews(activity: AppCompatActivity, onInputsChanged: () -> Unit) {
        activity.findViewById<View>(R.id.scrollWizard)?.let { scrollView ->
            ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
        step1Circle = activity.findViewById(R.id.step1Circle)
        step1Number = activity.findViewById(R.id.step1Number)
        step1Check = activity.findViewById(R.id.step1Check)
        step1Label = activity.findViewById(R.id.step1Label)
        stepperLine1 = activity.findViewById(R.id.stepperLine1)
        step2Circle = activity.findViewById(R.id.step2Circle)
        step2Number = activity.findViewById(R.id.step2Number)
        step2Label = activity.findViewById(R.id.step2Label)

        cardWifiWarning = activity.findViewById(R.id.cardWifiWarning)
        btnConnectWifi = activity.findViewById(R.id.btnConnectWifi)
        cardOemWarning = activity.findViewById(R.id.cardOemWarning)
        tvOemWarningTitle = activity.findViewById(R.id.tvOemWarningTitle)
        tvOemWarningDesc = activity.findViewById(R.id.tvOemWarningDesc)
        ivOemWarningIcon = activity.findViewById(R.id.ivOemWarningIcon)
        cardOemPermissionWarning = activity.findViewById(R.id.cardOemPermissionWarning)
        tvOemPermissionWarningTitle = activity.findViewById(R.id.tvOemPermissionWarningTitle)
        tvOemPermissionWarningDesc = activity.findViewById(R.id.tvOemPermissionWarningDesc)

        layoutStandardWizard = activity.findViewById(R.id.layoutStandardWizard)
        layoutSplitScreenMode = activity.findViewById(R.id.layoutSplitScreenMode)
        tvSplitRadarStatus = activity.findViewById(R.id.tvSplitRadarStatus)
        ivSplitRadarIcon = activity.findViewById(R.id.ivSplitRadarIcon)
        etSplitPairingCode = activity.findViewById(R.id.etSplitPairingCode)
        btnSplitPairAndActivate = activity.findViewById(R.id.btnSplitPairAndActivate)
        btnSplitReopenSettings = activity.findViewById(R.id.btnSplitReopenSettings)

        cardNotificationTip = activity.findViewById(R.id.cardNotificationTip)
        btnTrySplitScreen = activity.findViewById(R.id.btnTrySplitScreen)
        cardXiaomiSplitHint = activity.findViewById(R.id.cardXiaomiSplitHint)
        btnStartXiaomiSplit = activity.findViewById(R.id.btnStartXiaomiSplit)
        tvCableQuickHint = activity.findViewById(R.id.tvCableQuickHint)

        cardStep2 = activity.findViewById(R.id.cardStep2)
        tvStep2Desc = activity.findViewById(R.id.tvStep2Desc)
        btnOpenDevOptions = activity.findViewById(R.id.btnOpenDevOptions)
        cardStep3 = activity.findViewById(R.id.cardStep3)
        ivRadarIcon = activity.findViewById(R.id.ivRadarIcon)
        tvRadarStatus = activity.findViewById(R.id.tvRadarStatus)
        etPairingCode = activity.findViewById(R.id.etPairingCode)
        btnPairAndActivate = activity.findViewById(R.id.btnPairAndActivate)

        btnCopyWebAdbLink = activity.findViewById(R.id.btnCopyWebAdbLink)
        btnOpenWebAdb = activity.findViewById(R.id.btnOpenWebAdb)
        layoutStepper = activity.findViewById(R.id.layoutStepper)
        cardLegacyPureSite = activity.findViewById(R.id.cardLegacyPureSite)
        btnOpenPureSite = activity.findViewById(R.id.btnOpenPureSite)

        etPairingCode.doAfterTextChanged {
            syncInputText(etSplitPairingCode, it?.toString().orEmpty())
            onInputsChanged()
        }
        etSplitPairingCode.doAfterTextChanged {
            syncInputText(etPairingCode, it?.toString().orEmpty())
            onInputsChanged()
        }
    }

    private fun syncInputText(target: TextInputEditText, value: String) {
        if (target.text?.toString() != value) {
            target.setText(value)
            target.setSelection(value.length)
        }
    }

    fun bindListeners(callbacks: WizardClickCallbacks) {
        btnConnectWifi.setOnClickListener { callbacks.onConnectWifi() }
        btnOpenDevOptions.setOnClickListener { callbacks.onOpenDevOptions() }
        btnTrySplitScreen.setOnClickListener { callbacks.onLaunchAdjacentSettings() }
        btnStartXiaomiSplit.setOnClickListener { callbacks.onLaunchAdjacentSettings() }
        btnSplitReopenSettings.setOnClickListener { callbacks.onLaunchAdjacentSettings() }
        tvCableQuickHint.setOnClickListener { openExternalUrl(it.context, R.string.provisioning_cable_quick_url) }
        btnCopyWebAdbLink.setOnClickListener { copyWebAdbLink(it.context) }
        btnOpenWebAdb.setOnClickListener { openExternalUrl(it.context, R.string.provisioning_webadb_url) }
        btnPairAndActivate.setOnClickListener { callbacks.onPairAndActivate() }
        btnSplitPairAndActivate.setOnClickListener { btnPairAndActivate.performClick() }
    }

    fun currentPairingCode(): String = PairingCodeReceiver.sanitizePairingCode(etPairingCode.text?.toString())

    fun updateWifiWarning(isValid: Boolean) {
        cardWifiWarning.visibility = if (isValid) View.GONE else View.VISIBLE
        btnOpenDevOptions.isEnabled = isValid
    }

    fun updateDevOptionsStatus(context: Context, devEnabled: Boolean, portsDiscovered: Boolean) {
        btnOpenDevOptions.setText(if (devEnabled) R.string.provisioning_btn_open_dev_options else R.string.provisioning_btn_enable_dev_options)
        tvStep2Desc.setText(if (devEnabled) R.string.provisioning_step2_desc else R.string.provisioning_step2_desc_disabled)
        updateStepperUi(context, portsDiscovered)
    }

    fun updateStepperUi(context: Context, portsDiscovered: Boolean) {
        val colorPrimary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(context, R.color.md_theme_light_primary))
        val colorOnPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, ContextCompat.getColor(context, R.color.white))
        val colorOnSurfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(context, R.color.md_theme_light_onSurfaceVariant))
        val colorOutlineVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, ContextCompat.getColor(context, R.color.md_theme_light_outlineVariant))
        val colorGreen = ContextCompat.getColor(context, R.color.status_green)

        step1Circle.setBackgroundResource(if (portsDiscovered) R.drawable.bg_stepper_circle_done else R.drawable.bg_stepper_circle_active)
        step1Number.visibility = if (portsDiscovered) View.GONE else View.VISIBLE
        step1Check.visibility = if (portsDiscovered) View.VISIBLE else View.GONE
        step1Label.setTextColor(if (portsDiscovered) colorGreen else colorPrimary)
        stepperLine1.setBackgroundColor(if (portsDiscovered) colorGreen else colorOutlineVariant)
        cardStep2.strokeColor = if (portsDiscovered) colorGreen else colorPrimary

        step2Circle.setBackgroundResource(if (portsDiscovered) R.drawable.bg_stepper_circle_active else R.drawable.bg_stepper_circle_inactive)
        step2Number.setTextColor(if (portsDiscovered) colorOnPrimary else colorOnSurfaceVariant)
        step2Label.setTextColor(if (portsDiscovered) colorPrimary else colorOnSurfaceVariant)
        cardStep3.strokeColor = if (portsDiscovered) colorPrimary else colorOutlineVariant
    }

    fun updateRadarUi(context: Context, discoveredPairingPort: Int) {
        val colorGreen = ContextCompat.getColor(context, R.color.status_green)
        val colorOrange = ContextCompat.getColor(context, R.color.status_orange)
        val (text, color) = if (discoveredPairingPort > 0) {
            context.getString(R.string.provisioning_radar_port_found, discoveredPairingPort) to colorGreen
        } else {
            context.getString(R.string.provisioning_radar_waiting) to colorOrange
        }
        applyRadarStatus(tvRadarStatus, ivRadarIcon, text, color)
        applyRadarStatus(tvSplitRadarStatus, ivSplitRadarIcon, text, color)
        updateStepperUi(context, discoveredPairingPort > 0)
    }

    private fun applyRadarStatus(statusView: TextView, iconView: ImageView, text: CharSequence, color: Int) {
        statusView.text = text
        statusView.setTextColor(color)
        iconView.setColorFilter(color)
    }

    fun updateMultiWindowUi(isInMultiWindow: Boolean) {
        layoutSplitScreenMode.visibility = if (isInMultiWindow) View.VISIBLE else View.GONE
        layoutStandardWizard.visibility = if (isInMultiWindow) View.GONE else View.VISIBLE
        if (isInMultiWindow) {
            syncInputText(etSplitPairingCode, etPairingCode.text?.toString().orEmpty())
        } else {
            syncInputText(etPairingCode, etSplitPairingCode.text?.toString().orEmpty())
        }
    }

    fun setPromotionButtonsBusy(@StringRes busyTextRes: Int?) {
        if (busyTextRes != null) {
            btnPairAndActivate.isEnabled = false
            btnSplitPairAndActivate.isEnabled = false
            btnPairAndActivate.setText(busyTextRes)
            btnSplitPairAndActivate.setText(busyTextRes)
        } else {
            btnPairAndActivate.setText(R.string.provisioning_btn_pair_and_activate)
            btnSplitPairAndActivate.setText(R.string.provisioning_btn_pair_and_activate)
        }
    }

    fun validateInputs(isPromoting: Boolean, portsReady: Boolean, isWifiValid: Boolean) {
        if (isPromoting) {
            btnPairAndActivate.isEnabled = false
            btnSplitPairAndActivate.isEnabled = false
            return
        }
        val isValid = currentPairingCode().length == 6 && portsReady && isWifiValid
        btnPairAndActivate.isEnabled = isValid
        btnSplitPairAndActivate.isEnabled = isValid
    }

    fun setupOemGuidance(context: Context) {
        val isXiaomi = OemProvisioningHelper.isXiaomi()
        cardXiaomiSplitHint.visibility = if (isXiaomi) View.VISIBLE else View.GONE
        cardNotificationTip.visibility = if (isXiaomi) View.GONE else View.VISIBLE

        val warnings = OemProvisioningHelper.getAllOemWarnings(context)
        val primaryWarning = warnings.getOrNull(0)
        if (primaryWarning != null) {
            cardOemWarning.visibility = View.VISIBLE
            tvOemWarningTitle.setText(primaryWarning.titleRes)
            tvOemWarningDesc.setText(primaryWarning.descRes)
            val tintColor = if (primaryWarning.isCritical) {
                MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, ContextCompat.getColor(context, R.color.status_red))
            } else {
                MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(context, R.color.md_theme_light_primary))
            }
            cardOemWarning.strokeColor = tintColor
            tvOemWarningTitle.setTextColor(tintColor)
            ivOemWarningIcon?.setColorFilter(tintColor)
        } else {
            cardOemWarning.visibility = View.GONE
        }

        val secondaryWarning = warnings.getOrNull(1)
        if (secondaryWarning != null) {
            cardOemPermissionWarning.visibility = View.VISIBLE
            tvOemPermissionWarningTitle.setText(secondaryWarning.titleRes)
            tvOemPermissionWarningDesc.setText(secondaryWarning.descRes)
        } else {
            cardOemPermissionWarning.visibility = View.GONE
        }
    }

    fun setupLegacyPureSite() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            layoutStepper?.visibility = View.GONE
            cardStep2.visibility = View.GONE
            cardStep3.visibility = View.GONE
            cardLegacyPureSite?.visibility = View.VISIBLE
            btnOpenPureSite?.setOnClickListener { openExternalUrl(it.context, R.string.provisioning_webadb_url) }
        }
    }

    private fun openExternalUrl(context: Context, @StringRes urlRes: Int) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, context.getString(urlRes).toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed opening external URL for res=$urlRes")
        }
    }

    private fun copyWebAdbLink(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("WebADB Install URL", context.getString(R.string.provisioning_webadb_url)))
        Toast.makeText(context, R.string.provisioning_webadb_link_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AntiPorn_WizBinder"
    }
}
