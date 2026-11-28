package com.drmohammed.antiporn.ui.control.other

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.accountability.GuardianRemoteTeardownUiHelper
import com.drmohammed.antiporn.ui.accountability.RemoteTeardownDialogFragment
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Dedicated Sub-Screen for Other Settings (Date & Time Lock, Timezone, Total Internet Cutoff,
 * and Guardian-only Immediate Device Owner Removal Danger Zone).
 */
class OtherSettingsActivity : AppCompatActivity() {

    private var isRemote = false
    private var wardId: String = ""
    private var wardName: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle
    private val teardownUiHelper = GuardianRemoteTeardownUiHelper()

    private var isDateTimeLocked = false
    private var isClearAppDataAllowed = true
    private var selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID
    private var isTotalInternetKillEnabled = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchLockDateTime: MaterialSwitch
    private lateinit var tvTimezoneBadge: TextView
    private lateinit var tilTimezonePicker: TextInputLayout
    private lateinit var actvTimezonePicker: AutoCompleteTextView
    private lateinit var btnAutoDetectTimezone: MaterialButton
    private lateinit var btnResetToMakkah: MaterialButton
    private lateinit var switchAllowAppsDataClearing: MaterialSwitch
    private lateinit var switchTotalInternetKill: MaterialSwitch
    private lateinit var btnSaveOtherSettingsChanges: MaterialButton

    private var isSaving = false
    private var isDangerZoneBusy = false
    private var isProgrammaticUpdate = false
    private var lastAutoDetectClickTime = 0L
    private var hasPendingResultOk = false
    private var pendingResultPolicyJson: String? = null
    private var dangerZoneBinding: GuardianRemoteTeardownUiHelper.OtherSettingsDangerZoneBinding? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (hasUnsavedChanges()) {
                showDiscardConfirmationDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_other_settings)

        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        wardName = intent.getStringExtra(EXTRA_WARD_NAME).orEmpty()
            .ifBlank { getString(R.string.status_ward_badge) }
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isRemote) {
            if (wardId.isBlank() || policyJson.isNullOrBlank()) {
                Timber.tag(TAG).e("Refusing remote launch: missing wardId or EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish(); return
            }
            initialPolicy = DevicePolicyBundle.fromJsonOrNull(policyJson) ?: run {
                Timber.tag(TAG).e("Refusing remote launch: invalid EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish(); return
            }
        } else {
            initialPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        val restoredInitialPolicyJson = savedInstanceState?.getString(STATE_INITIAL_POLICY_JSON)
        if (!restoredInitialPolicyJson.isNullOrBlank()) {
            initialPolicy = DevicePolicyBundle.fromJsonOrNull(restoredInitialPolicyJson) ?: initialPolicy
        }
        hasPendingResultOk = savedInstanceState?.getBoolean(STATE_HAS_PENDING_RESULT_OK, false) == true
        pendingResultPolicyJson = savedInstanceState?.getString(STATE_PENDING_RESULT_POLICY_JSON)
        if (hasPendingResultOk) {
            val resIntent = Intent().apply {
                pendingResultPolicyJson?.let { putExtra(EXTRA_UPDATED_POLICY_JSON, it) }
            }
            setResult(Activity.RESULT_OK, resIntent)
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        setupFragmentResultListeners()
        initViews()
        bindInitialPolicy()
        savedInstanceState?.let { restoreDraftState(it) }
        setupListeners()
        if (isRemote) {
            dangerZoneBinding = teardownUiHelper.bindOtherSettingsDangerZone(
                activity = this,
                wardId = wardId,
                wardName = wardName,
                policyProvider = { initialPolicy },
                onPolicyUpdated = { updated ->
                    initialPolicy = updated
                    hasPendingResultOk = true
                    pendingResultPolicyJson = updated.toJson()
                    isTotalInternetKillEnabled = updated.isInstantInternetKillEnabled
                    applyDraftToViews()
                    updateSaveButtonState()
                },
                isExternalBusy = { isSaving },
                onBusyChanged = { busy ->
                    isDangerZoneBusy = busy
                    updateSaveButtonState()
                },
                onAlreadyCompleted = { handleRemoteTeardownCompleted() }
            )
        }
        updateSaveButtonState()
    }

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(RemoteTeardownDialogFragment.REQUEST_KEY, this) { _, bundle ->
            when (bundle.getString(RemoteTeardownDialogFragment.RESULT_ACTION)) {
                RemoteTeardownDialogFragment.ACTION_CONFIRM_TEARDOWN -> dangerZoneBinding?.executeImmediateTeardown?.invoke()
                RemoteTeardownDialogFragment.ACTION_CANCEL_PENDING_TEARDOWN -> dangerZoneBinding?.executeCancelTeardown?.invoke()
            }
        }

        supportFragmentManager.setFragmentResultListener(REQ_TOTAL_INTERNET_KILL_CONFIRM, this) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                isTotalInternetKillEnabled = true
                updateSaveButtonState()
            } else {
                revertTotalInternetKillSwitch()
            }
        }

        supportFragmentManager.setFragmentResultListener(REQ_DISCARD_CHANGES, this) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                backPressedCallback.isEnabled = false
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_INITIAL_POLICY_JSON, initialPolicy.toJson())
        outState.putBoolean(STATE_HAS_PENDING_RESULT_OK, hasPendingResultOk)
        pendingResultPolicyJson?.let { outState.putString(STATE_PENDING_RESULT_POLICY_JSON, it) }
        outState.putBoolean(STATE_DATE_TIME_LOCKED, isDateTimeLocked)
        outState.putBoolean(STATE_CLEAR_APP_DATA_ALLOWED, isClearAppDataAllowed)
        outState.putString(STATE_TIMEZONE_ID, selectedTimeZoneId)
        outState.putBoolean(STATE_TOTAL_INTERNET_KILL, isTotalInternetKillEnabled)
    }

    private fun restoreDraftState(state: Bundle) {
        isDateTimeLocked = state.getBoolean(STATE_DATE_TIME_LOCKED, isDateTimeLocked)
        isClearAppDataAllowed = state.getBoolean(STATE_CLEAR_APP_DATA_ALLOWED, isClearAppDataAllowed)
        selectedTimeZoneId = state.getString(STATE_TIMEZONE_ID, selectedTimeZoneId).ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
        isTotalInternetKillEnabled = state.getBoolean(STATE_TOTAL_INTERNET_KILL, isTotalInternetKillEnabled)
        applyDraftToViews()
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !SelfControlAccessGateRegistry.getActiveGate().canAccess(this)) {
            Toast.makeText(this, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        } else if (isRemote) {
            teardownUiHelper.syncRemoteCompletionStateOnResume(this, wardId, wardName) {
                handleRemoteTeardownCompleted()
            }
        }
    }

    private fun handleRemoteTeardownCompleted() {
        if (isFinishing || isDestroyed) return
        setResult(Activity.RESULT_OK)
        backPressedCallback.isEnabled = false
        Toast.makeText(applicationContext, R.string.toast_guardian_supervision_ended, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootOtherSettings)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarOtherSettings)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        switchLockDateTime = findViewById<MaterialSwitch>(R.id.switchLockDateTime).apply { isSaveEnabled = false }
        tvTimezoneBadge = findViewById(R.id.tvTimezoneBadge)
        tilTimezonePicker = findViewById(R.id.tilTimezonePicker)
        actvTimezonePicker = findViewById(R.id.actvTimezonePicker)
        btnAutoDetectTimezone = findViewById(R.id.btnAutoDetectTimezone)
        btnResetToMakkah = findViewById(R.id.btnResetToMakkah)
        switchAllowAppsDataClearing = findViewById<MaterialSwitch>(R.id.switchAllowAppsDataClearing).apply { isSaveEnabled = false }
        switchTotalInternetKill = findViewById<MaterialSwitch>(R.id.switchTotalInternetKill).apply { isSaveEnabled = false }
        btnSaveOtherSettingsChanges = findViewById(R.id.btnSaveOtherSettingsChanges)

        val timezoneOptions = IslamicTimeZoneRegistry.getAllOptions()
        actvTimezonePicker.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, timezoneOptions))
    }

    private fun bindInitialPolicy() {
        isDateTimeLocked = initialPolicy.isDateTimeLocked
        isClearAppDataAllowed = initialPolicy.isClearAppDataAllowed
        selectedTimeZoneId = initialPolicy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
        isTotalInternetKillEnabled = initialPolicy.isInstantInternetKillEnabled
        applyDraftToViews()
    }

    private fun applyDraftToViews() {
        isProgrammaticUpdate = true
        switchLockDateTime.isChecked = isDateTimeLocked
        switchAllowAppsDataClearing.isChecked = isClearAppDataAllowed
        switchTotalInternetKill.isChecked = isTotalInternetKillEnabled
        updateTimezoneViews()
        isProgrammaticUpdate = false
    }

    private fun setupListeners() {
        switchLockDateTime.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUpdate) return@setOnCheckedChangeListener
            isDateTimeLocked = isChecked
            updateSaveButtonState()
        }

        switchAllowAppsDataClearing.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUpdate) return@setOnCheckedChangeListener
            isClearAppDataAllowed = isChecked
            updateSaveButtonState()
        }

        actvTimezonePicker.setOnItemClickListener { parent, _, position, _ ->
            val option = parent.getItemAtPosition(position) as? IslamicTimeZoneRegistry.TimeZoneOption
            if (option != null && option.id != selectedTimeZoneId) {
                selectedTimeZoneId = option.id
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        btnAutoDetectTimezone.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAutoDetectClickTime < 800L) return@setOnClickListener
            lastAutoDetectClickTime = now
            val detected = IslamicTimeZoneRegistry.detectCurrentDeviceOption()
            Toast.makeText(this, getString(R.string.timezone_detected_toast, detected.displayText), Toast.LENGTH_SHORT).show()
            if (selectedTimeZoneId != detected.id) {
                selectedTimeZoneId = detected.id
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        btnResetToMakkah.setOnClickListener {
            if (selectedTimeZoneId != CurfewConfig.DEFAULT_TIMEZONE_ID) {
                selectedTimeZoneId = CurfewConfig.DEFAULT_TIMEZONE_ID
                updateTimezoneViews()
                updateSaveButtonState()
            }
        }

        switchTotalInternetKill.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUpdate) return@setOnCheckedChangeListener
            if (isChecked) {
                supportFragmentManager.showSafely(TAG_TOTAL_INTERNET_KILL_CONFIRM) {
                    ConfirmActionDialogFragment.newInstance(
                        requestKey = REQ_TOTAL_INTERNET_KILL_CONFIRM,
                        titleRes = R.string.dialog_total_internet_kill_title,
                        messageRes = R.string.dialog_total_internet_kill_message,
                        positiveRes = android.R.string.ok,
                        negativeRes = android.R.string.cancel
                    )
                }
            } else {
                isTotalInternetKillEnabled = false
                updateSaveButtonState()
            }
        }

        btnSaveOtherSettingsChanges.setOnClickListener { saveChanges() }
    }

    private fun revertTotalInternetKillSwitch() {
        isProgrammaticUpdate = true
        switchTotalInternetKill.isChecked = false
        isTotalInternetKillEnabled = false
        isProgrammaticUpdate = false
    }

    private fun updateTimezoneViews() {
        val option = IslamicTimeZoneRegistry.findByIanaId(selectedTimeZoneId)
        val cityName = option.majorCities.split("/").firstOrNull()?.trim() ?: option.majorCities
        tvTimezoneBadge.text = getString(R.string.guardian_timezone_badge_format, option.getLiveOffsetLabel(), cityName)
        actvTimezonePicker.setText(option.displayText, false)
    }

    private fun hasUnsavedChanges(): Boolean {
        return isDateTimeLocked != initialPolicy.isDateTimeLocked ||
                isClearAppDataAllowed != initialPolicy.isClearAppDataAllowed ||
                selectedTimeZoneId != initialPolicy.timeZoneId ||
                isTotalInternetKillEnabled != initialPolicy.isInstantInternetKillEnabled
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        val canSave = dirty && !isSaving && !isDangerZoneBusy
        btnSaveOtherSettingsChanges.isEnabled = canSave
        btnSaveOtherSettingsChanges.alpha = if (canSave) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty
    }

    private fun saveChanges() {
        if (isSaving || isDangerZoneBusy || !hasUnsavedChanges()) return
        isSaving = true
        updateSaveButtonState()
        dangerZoneBinding?.refresh?.invoke()

        val updatedPolicy = initialPolicy.copy(
            isDateTimeLocked = isDateTimeLocked,
            isClearAppDataAllowed = isClearAppDataAllowed,
            timeZoneId = selectedTimeZoneId,
            isInstantInternetKillEnabled = isTotalInternetKillEnabled
        )

        val app = applicationContext
        val policyRepo = PolicyRepository.getInstance(app)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalSavedPolicy = withContext(NonCancellable) {
                    if (isRemote) {
                        if (wardId.isBlank()) {
                            Timber.tag(TAG).e("Refusing remote push: wardId is blank")
                            null
                        } else {
                            val accountRepo = AccountabilityRepository.getInstance(app)
                            policyRepo.mergeAndPushRemotePolicyPatch(
                                wardUid = wardId,
                                fallbackPolicy = initialPolicy,
                                guardianUidFallback = accountRepo.getCachedUid(),
                                guardianNameFallback = accountRepo.getMyDisplayName()
                            ) { latest ->
                                latest.copy(
                                    isDateTimeLocked = isDateTimeLocked,
                                    isClearAppDataAllowed = isClearAppDataAllowed,
                                    timeZoneId = selectedTimeZoneId,
                                    isInstantInternetKillEnabled = isTotalInternetKillEnabled,
                                    version = latest.version + 1,
                                    updatedAt = TrustedTimeProvider.currentTimeMillis(app)
                                )
                            }
                        }
                    } else {
                        if (policyRepo.saveAndEnforceSelfPolicy(updatedPolicy)) updatedPolicy else null
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSaving = false
                    dangerZoneBinding?.refresh?.invoke()
                    if (finalSavedPolicy != null) {
                        val successRes = if (isRemote) R.string.toast_guardian_policy_saved else R.string.toast_self_policy_saved
                        Toast.makeText(applicationContext, successRes, Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_UPDATED_POLICY_JSON, finalSavedPolicy.toJson()))
                        backPressedCallback.isEnabled = false
                        finish()
                    } else {
                        showSaveFailed()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.tag(TAG).e(e, "Error saving other settings")
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSaving = false
                    dangerZoneBinding?.refresh?.invoke()
                    showSaveFailed()
                }
            }
        }
    }

    private fun showSaveFailed() {
        updateSaveButtonState()
        val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
        Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
    }

    private fun showDiscardConfirmationDialog() {
        supportFragmentManager.showSafely(TAG_DISCARD_CHANGES) {
            ConfirmActionDialogFragment.newInstance(
                requestKey = REQ_DISCARD_CHANGES,
                titleRes = R.string.dialog_unsaved_changes_title,
                messageRes = R.string.dialog_unsaved_changes_msg,
                positiveRes = R.string.btn_discard_changes,
                negativeRes = R.string.btn_keep_editing
            )
        }
    }

    override fun onDestroy() {
        teardownUiHelper.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OtherSettings"
        private const val REQ_TOTAL_INTERNET_KILL_CONFIRM = "req_other_total_internet_kill"
        private const val TAG_TOTAL_INTERNET_KILL_CONFIRM = "tag_other_total_internet_kill"
        private const val REQ_DISCARD_CHANGES = "req_other_discard_changes"
        private const val TAG_DISCARD_CHANGES = "tag_other_discard_changes"
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"

        private const val STATE_INITIAL_POLICY_JSON = "state_initial_policy_json"
        private const val STATE_HAS_PENDING_RESULT_OK = "state_has_pending_result_ok"
        private const val STATE_PENDING_RESULT_POLICY_JSON = "state_pending_result_policy_json"
        private const val STATE_DATE_TIME_LOCKED = "state_date_time_locked"
        private const val STATE_CLEAR_APP_DATA_ALLOWED = "state_clear_app_data_allowed"
        private const val STATE_TIMEZONE_ID = "state_timezone_id"
        private const val STATE_TOTAL_INTERNET_KILL = "state_total_internet_kill"
    }
}
