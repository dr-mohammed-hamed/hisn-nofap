package com.drmohammed.antiporn.ui.control.web

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Dedicated Sub-Screen for Web Filter, DNS, and Browsing Restrictions.
 * Features ultra-minimalist Material 3 UI with Segmented Buttons for Blocked/Allowed lists,
 * zero visual clutter, strict Whitelist Only mode, and full-system VPN filtering scope.
 */
class WebFilterSettingsActivity : AppCompatActivity() {

    private val policyRepository: PolicyRepository by inject()
    private val accountabilityRepository: AccountabilityRepository by inject()

    private enum class UrlTab { BLOCKED, ALLOWED }

    private var isRemote = false
    private var wardId: String = ""
    private lateinit var initialPolicy: DevicePolicyBundle

    private var currentDnsHost = ""
    private var isIncognitoBlocked = true
    private var isTelegramWebBlocked = true
    private var isYouTubeRestricted = false
    private var isFullSystemFiltering = false
    private var isAdBlocking = false
    private var isWhitelistOnlyMode = false
    private val blockedUrls = mutableSetOf<String>()
    private val allowedUrls = mutableSetOf<String>()
    private var currentTab = UrlTab.BLOCKED

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tilDnsHost: TextInputLayout
    private lateinit var etDnsHost: TextInputEditText
    private lateinit var rowDnsCloudflare: View
    private lateinit var rbDnsCloudflare: RadioButton
    private lateinit var rowDnsControlD: View
    private lateinit var rbDnsControlD: RadioButton
    private lateinit var rowDnsAdguard: View
    private lateinit var rbDnsAdguard: RadioButton
    private var isProgrammaticDnsChange = false

    private lateinit var switchBlockIncognito: MaterialSwitch
    private lateinit var switchBlockTelegramWeb: MaterialSwitch
    private lateinit var switchYouTubeRestricted: MaterialSwitch
    private lateinit var switchFullSystemFilter: MaterialSwitch
    private lateinit var switchBlockAds: MaterialSwitch

    private lateinit var toggleGroupUrlType: MaterialButtonToggleGroup
    private lateinit var btnTabBlocked: MaterialButton
    private lateinit var btnTabAllowed: MaterialButton
    private lateinit var layoutWhitelistOnlyMaster: View
    private lateinit var switchWhitelistOnly: MaterialSwitch

    private lateinit var btnAddBlockedUrl: MaterialButton
    private lateinit var tvEmptyBlockedUrls: TextView
    private lateinit var layoutBlockedUrlsList: LinearLayout
    private lateinit var btnSaveWebFilterChanges: MaterialButton

    private enum class VpnConsentSource { FULL_SYSTEM, AD_BLOCKING }

    private var isSaving = false
    private var isProgrammaticSwitchChange = false
    private var pendingVpnConsentSource: VpnConsentSource? = null
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

    private val vpnConsentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val source = pendingVpnConsentSource ?: VpnConsentSource.FULL_SYSTEM
        pendingVpnConsentSource = null
        if (isFinishing || isDestroyed) return@registerForActivityResult
        val consentGranted = runCatching { android.net.VpnService.prepare(this) == null }.getOrDefault(false)
        isProgrammaticSwitchChange = true
        when (source) {
            VpnConsentSource.FULL_SYSTEM -> {
                if (consentGranted) {
                    isFullSystemFiltering = true
                    switchFullSystemFilter.isChecked = true
                } else {
                    isFullSystemFiltering = false
                    switchFullSystemFilter.isChecked = false
                    Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
                }
            }
            VpnConsentSource.AD_BLOCKING -> {
                if (consentGranted) {
                    isAdBlocking = true
                    switchBlockAds.isChecked = true
                } else {
                    isAdBlocking = false
                    switchBlockAds.isChecked = false
                    Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        isProgrammaticSwitchChange = false
        if (consentGranted && source == VpnConsentSource.FULL_SYSTEM && isWhitelistOnlyMode) {
            showWhitelistFullSystemWarningDialog()
        }
        updateSaveButtonState()
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isSaving) return
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
        setContentView(R.layout.activity_web_filter_settings)

        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this) ||
                !SelfControlAccessGateRegistry.isSectionPermitted(this, com.drmohammed.antiporn.ui.control.gate.SelfControlSection.WEB_FILTER))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isRemote) {
            if (wardId.isBlank() || policyJson.isNullOrBlank()) {
                android.util.Log.e("WebFilterSettings", "Refusing remote launch: missing wardId or EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val parsed = DevicePolicyBundle.fromJsonOrNull(policyJson)
            if (parsed == null) {
                android.util.Log.e("WebFilterSettings", "Refusing remote launch: invalid EXTRA_POLICY_JSON")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            initialPolicy = parsed
        } else {
            initialPolicy = policyRepository.getCurrentPolicy()
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        bindInitialPolicy()
        savedInstanceState?.let { restoreDraftState(it) }
        setupListeners()
        updateTabUi()
        updateSaveButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DNS_HOST, currentDnsHost)
        outState.putBoolean(STATE_INCOGNITO_BLOCKED, isIncognitoBlocked)
        outState.putBoolean(STATE_TELEGRAM_WEB_BLOCKED, isTelegramWebBlocked)
        outState.putBoolean(STATE_YOUTUBE_RESTRICTED, isYouTubeRestricted)
        outState.putBoolean(STATE_FULL_SYSTEM_FILTERING, isFullSystemFiltering)
        outState.putBoolean(STATE_AD_BLOCKING, isAdBlocking)
        outState.putBoolean(STATE_WHITELIST_ONLY, isWhitelistOnlyMode)
        outState.putStringArrayList(STATE_BLOCKED_URLS, ArrayList(blockedUrls))
        outState.putStringArrayList(STATE_ALLOWED_URLS, ArrayList(allowedUrls))
        outState.putInt(STATE_CURRENT_TAB, currentTab.ordinal)
        pendingVpnConsentSource?.let { outState.putInt(STATE_PENDING_VPN_CONSENT_SOURCE, it.ordinal) }
    }

    private fun restoreDraftState(state: Bundle) {
        currentDnsHost = state.getString(STATE_DNS_HOST, currentDnsHost)
        isIncognitoBlocked = state.getBoolean(STATE_INCOGNITO_BLOCKED, isIncognitoBlocked)
        isTelegramWebBlocked = state.getBoolean(STATE_TELEGRAM_WEB_BLOCKED, isTelegramWebBlocked)
        isYouTubeRestricted = state.getBoolean(STATE_YOUTUBE_RESTRICTED, isYouTubeRestricted)
        isFullSystemFiltering = state.getBoolean(STATE_FULL_SYSTEM_FILTERING, isFullSystemFiltering)
        isAdBlocking = state.getBoolean(STATE_AD_BLOCKING, isAdBlocking)
        isWhitelistOnlyMode = state.getBoolean(STATE_WHITELIST_ONLY, isWhitelistOnlyMode)
        if (state.containsKey(STATE_PENDING_VPN_CONSENT_SOURCE)) {
            val ord = state.getInt(STATE_PENDING_VPN_CONSENT_SOURCE, -1)
            pendingVpnConsentSource = VpnConsentSource.entries.getOrNull(ord)
        }
        state.getStringArrayList(STATE_BLOCKED_URLS)?.let {
            blockedUrls.clear()
            blockedUrls.addAll(it)
        }
        state.getStringArrayList(STATE_ALLOWED_URLS)?.let {
            allowedUrls.clear()
            allowedUrls.addAll(it)
        }
        val tabOrd = state.getInt(STATE_CURRENT_TAB, currentTab.ordinal)
        currentTab = UrlTab.entries.getOrElse(tabOrd) { UrlTab.BLOCKED }

        isProgrammaticDnsChange = true
        etDnsHost.setText(currentDnsHost)
        isProgrammaticDnsChange = false
        updateTableSelection(currentDnsHost)

        isProgrammaticSwitchChange = true
        switchBlockIncognito.isChecked = isIncognitoBlocked
        switchBlockTelegramWeb.isChecked = isTelegramWebBlocked
        switchYouTubeRestricted.isChecked = isYouTubeRestricted
        switchFullSystemFilter.isChecked = isFullSystemFiltering
        switchBlockAds.isChecked = isAdBlocking
        switchWhitelistOnly.isChecked = isWhitelistOnlyMode
        isProgrammaticSwitchChange = false
        toggleGroupUrlType.check(
            if (currentTab == UrlTab.ALLOWED) R.id.btnTabAllowed else R.id.btnTabBlocked
        )
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this) ||
                !SelfControlAccessGateRegistry.isSectionPermitted(this, com.drmohammed.antiporn.ui.control.gate.SelfControlSection.WEB_FILTER))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootWebFilterSettings)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarWebFilter)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tilDnsHost = findViewById(R.id.tilDnsHost)
        etDnsHost = findViewById(R.id.etDnsHost)
        rowDnsCloudflare = findViewById(R.id.rowDnsCloudflare)
        rbDnsCloudflare = findViewById(R.id.rbDnsCloudflare)
        rowDnsControlD = findViewById(R.id.rowDnsControlD)
        rbDnsControlD = findViewById(R.id.rbDnsControlD)
        rowDnsAdguard = findViewById(R.id.rowDnsAdguard)
        rbDnsAdguard = findViewById(R.id.rbDnsAdguard)

        switchBlockIncognito = findViewById(R.id.switchBlockIncognito)
        switchBlockTelegramWeb = findViewById(R.id.switchBlockTelegramWeb)
        switchYouTubeRestricted = findViewById(R.id.switchYouTubeRestricted)
        switchFullSystemFilter = findViewById(R.id.switchFullSystemFilter)
        switchBlockAds = findViewById(R.id.switchBlockAds)

        toggleGroupUrlType = findViewById(R.id.toggleGroupUrlType)
        btnTabBlocked = findViewById(R.id.btnTabBlocked)
        btnTabAllowed = findViewById(R.id.btnTabAllowed)
        layoutWhitelistOnlyMaster = findViewById(R.id.layoutWhitelistOnlyMaster)
        switchWhitelistOnly = findViewById(R.id.switchWhitelistOnly)

        btnAddBlockedUrl = findViewById(R.id.btnAddBlockedUrl)
        tvEmptyBlockedUrls = findViewById(R.id.tvEmptyBlockedUrls)
        layoutBlockedUrlsList = findViewById(R.id.layoutBlockedUrlsList)
        btnSaveWebFilterChanges = findViewById(R.id.btnSaveWebFilterChanges)
    }

    private fun bindInitialPolicy() {
        currentDnsHost = initialPolicy.dnsHost
        isIncognitoBlocked = initialPolicy.isIncognitoBlocked
        isTelegramWebBlocked = initialPolicy.isTelegramWebBlocked
        isYouTubeRestricted = initialPolicy.isYouTubeRestrictedModeEnabled
        isFullSystemFiltering = initialPolicy.isFullSystemFilteringEnabled
        isAdBlocking = initialPolicy.isAdBlockingEnabled
        isWhitelistOnlyMode = initialPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY

        blockedUrls.clear()
        blockedUrls.addAll(initialPolicy.customBlockedUrls)
        allowedUrls.clear()
        allowedUrls.addAll(initialPolicy.customAllowedUrls)

        isProgrammaticDnsChange = true
        etDnsHost.setText(currentDnsHost)
        isProgrammaticDnsChange = false
        updateTableSelection(currentDnsHost)

        switchBlockIncognito.isChecked = isIncognitoBlocked
        switchBlockTelegramWeb.isChecked = isTelegramWebBlocked
        switchYouTubeRestricted.isChecked = isYouTubeRestricted
        switchFullSystemFilter.isChecked = isFullSystemFiltering
        switchBlockAds.isChecked = isAdBlocking
        switchWhitelistOnly.isChecked = isWhitelistOnlyMode

        if (isWhitelistOnlyMode && blockedUrls.isEmpty() && allowedUrls.isNotEmpty()) {
            currentTab = UrlTab.ALLOWED
            toggleGroupUrlType.check(R.id.btnTabAllowed)
        } else {
            currentTab = UrlTab.BLOCKED
            toggleGroupUrlType.check(R.id.btnTabBlocked)
        }
    }

    private fun updateTableSelection(host: String) {
        val clean = host.trim().lowercase()
        val isCloudflare = clean == DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase()
        val isControlD = clean == DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST.lowercase() ||
                clean == DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST.lowercase()
        val isAdguard = clean == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase()

        rbDnsCloudflare.isChecked = isCloudflare
        rbDnsControlD.isChecked = isControlD
        rbDnsAdguard.isChecked = isAdguard

        rowDnsCloudflare.isActivated = isCloudflare
        rowDnsControlD.isActivated = isControlD
        rowDnsAdguard.isActivated = isAdguard
    }

    private fun setupListeners() {
        val selectPreset = { host: String ->
            isProgrammaticDnsChange = true
            etDnsHost.setText(host)
            etDnsHost.setSelection(host.length)
            isProgrammaticDnsChange = false
            tilDnsHost.error = null
            updateTableSelection(host)
            currentDnsHost = host
            updateSaveButtonState()
        }

        rowDnsCloudflare.setOnClickListener {
            selectPreset(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        rowDnsControlD.setOnClickListener {
            selectPreset(DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST)
        }
        rowDnsAdguard.setOnClickListener {
            selectPreset(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST)
        }

        etDnsHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentDnsHost = s?.toString()?.trim().orEmpty()
                tilDnsHost.error = null
                if (!isProgrammaticDnsChange) {
                    updateTableSelection(currentDnsHost)
                }
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        switchBlockIncognito.setOnCheckedChangeListener { _, isChecked ->
            isIncognitoBlocked = isChecked
            updateSaveButtonState()
        }

        switchBlockTelegramWeb.setOnCheckedChangeListener { _, isChecked ->
            isTelegramWebBlocked = isChecked
            updateSaveButtonState()
        }

        switchYouTubeRestricted.setOnCheckedChangeListener { _, isChecked ->
            isYouTubeRestricted = isChecked
            updateSaveButtonState()
        }

        switchFullSystemFilter.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticSwitchChange) return@setOnCheckedChangeListener
            if (isChecked && !isRemote) {
                if (!ensureVpnConsentOrPrompt(VpnConsentSource.FULL_SYSTEM, switchFullSystemFilter)) {
                    return@setOnCheckedChangeListener
                }
            }
            isFullSystemFiltering = isChecked
            if (isChecked && isWhitelistOnlyMode) {
                showWhitelistFullSystemWarningDialog()
            } else if (isChecked && com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(this)) {
                showUnaffiliatedUsersFilterWarningDialog()
            }
            updateSaveButtonState()
        }

        switchBlockAds.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticSwitchChange) return@setOnCheckedChangeListener
            if (isChecked && !isRemote) {
                if (!ensureVpnConsentOrPrompt(VpnConsentSource.AD_BLOCKING, switchBlockAds)) {
                    return@setOnCheckedChangeListener
                }
            }
            isAdBlocking = isChecked
            if (isChecked && !isFullSystemFiltering && com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(this)) {
                showUnaffiliatedUsersFilterWarningDialog()
            }
            updateSaveButtonState()
        }

        switchWhitelistOnly.setOnCheckedChangeListener { _, isChecked ->
            isWhitelistOnlyMode = isChecked
            if (isChecked && isFullSystemFiltering) {
                showWhitelistFullSystemWarningDialog()
            }
            updateSaveButtonState()
        }

        toggleGroupUrlType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTab = if (checkedId == R.id.btnTabAllowed) UrlTab.ALLOWED else UrlTab.BLOCKED
            updateTabUi()
        }

        btnAddBlockedUrl.setOnClickListener {
            showAddUrlDialog()
        }

        btnSaveWebFilterChanges.setOnClickListener {
            saveChanges()
        }
    }

    private fun updateTabUi() {
        btnTabBlocked.text = getString(R.string.tab_blocked_websites, blockedUrls.size)
        btnTabAllowed.text = getString(R.string.tab_allowed_websites, allowedUrls.size)

        if (currentTab == UrlTab.ALLOWED) {
            layoutWhitelistOnlyMaster.visibility = View.VISIBLE
        } else {
            layoutWhitelistOnlyMaster.visibility = View.GONE
        }
        renderUrlsList()
    }

    private fun showAddUrlDialog() {
        val density = resources.displayMetrics.density
        val til = TextInputLayout(this).apply {
            hint = getString(R.string.dialog_add_url_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(12f * density, 12f * density, 12f * density, 12f * density)
        }
        val input = TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            textDirection = View.TEXT_DIRECTION_LTR
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.tajawal)
        }
        til.addView(input)

        val frame = FrameLayout(this).apply {
            val padHorizontal = (20 * density).toInt()
            val padTop = (8 * density).toInt()
            setPadding(padHorizontal, padTop, padHorizontal, 0)
            addView(til)
        }

        val titleRes = if (currentTab == UrlTab.ALLOWED) R.string.dialog_add_allowed_title else R.string.dialog_add_url_title
        val targetSet = if (currentTab == UrlTab.ALLOWED) allowedUrls else blockedUrls

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(frame)
            .setPositiveButton(R.string.browser_filter_btn_add) { dialog, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val sanitized = if (currentTab == UrlTab.ALLOWED) {
                    BrowserUrlSanitizer.sanitizeForAllowlist(raw)
                } else {
                    BrowserUrlSanitizer.sanitize(raw)
                }
                if (sanitized != null) {
                    if (!targetSet.contains(sanitized)) {
                        targetSet.add(sanitized)
                        // Ensure mutual exclusivity between blocked and allowed sets to prevent DNS vs Browser rule conflicts
                        if (targetSet === allowedUrls) {
                            blockedUrls.remove(sanitized)
                        } else {
                            allowedUrls.remove(sanitized)
                        }
                        updateTabUi()
                        updateSaveButtonState()
                    } else {
                        Toast.makeText(this, R.string.browser_filter_error_duplicate_url, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.browser_filter_error_invalid_url, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
        }
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.show()
    }

    private fun renderUrlsList() {
        layoutBlockedUrlsList.removeAllViews()
        val currentList = if (currentTab == UrlTab.ALLOWED) allowedUrls else blockedUrls

        if (currentList.isEmpty()) {
            tvEmptyBlockedUrls.text = if (currentTab == UrlTab.ALLOWED) {
                getString(R.string.empty_allowed_urls)
            } else {
                getString(R.string.empty_blocked_urls)
            }
            tvEmptyBlockedUrls.visibility = View.VISIBLE
            return
        }
        tvEmptyBlockedUrls.visibility = View.GONE

        val density = resources.displayMetrics.density
        val tajawalTypeface = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.tajawal)

        for (url in currentList.sorted()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }

            val tvUrl = TextView(this).apply {
                text = url
                textSize = 13f
                textDirection = View.TEXT_DIRECTION_LTR
                typeface = tajawalTypeface
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvUrl)

            // Touch target size 48x48dp with ripple to strictly satisfy mobile accessibility standards
            val ivDelete = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                val targetSize = (48 * density).toInt()
                val pad = (14 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(targetSize, targetSize)
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                contentDescription = getString(R.string.browser_filter_item_delete_cd)
                isEnabled = !isSaving
                setOnClickListener {
                    if (isSaving) return@setOnClickListener
                    currentList.remove(url)
                    updateTabUi()
                    updateSaveButtonState()
                }
            }
            row.addView(ivDelete)

            layoutBlockedUrlsList.addView(row)
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val cleanInputDns = currentDnsHost.ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        val initialWasWhitelist = initialPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
        return cleanInputDns != initialPolicy.dnsHost ||
                isIncognitoBlocked != initialPolicy.isIncognitoBlocked ||
                isTelegramWebBlocked != initialPolicy.isTelegramWebBlocked ||
                isYouTubeRestricted != initialPolicy.isYouTubeRestrictedModeEnabled ||
                isFullSystemFiltering != initialPolicy.isFullSystemFilteringEnabled ||
                isAdBlocking != initialPolicy.isAdBlockingEnabled ||
                isWhitelistOnlyMode != initialWasWhitelist ||
                blockedUrls != initialPolicy.customBlockedUrls ||
                allowedUrls != initialPolicy.customAllowedUrls
    }

    private fun updateSaveButtonState() {
        val dirty = hasUnsavedChanges()
        btnSaveWebFilterChanges.isEnabled = dirty && !isSaving
        btnSaveWebFilterChanges.alpha = if (dirty && !isSaving) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty || isSaving
        setInputsEnabled(!isSaving)
    }

    private fun setInputsEnabled(enabled: Boolean) {
        etDnsHost.isEnabled = enabled
        rowDnsCloudflare.isEnabled = enabled
        rowDnsControlD.isEnabled = enabled
        rowDnsAdguard.isEnabled = enabled
        switchBlockIncognito.isEnabled = enabled
        switchBlockTelegramWeb.isEnabled = enabled
        switchYouTubeRestricted.isEnabled = enabled
        switchFullSystemFilter.isEnabled = enabled
        switchBlockAds.isEnabled = enabled
        switchWhitelistOnly.isEnabled = enabled
        btnAddBlockedUrl.isEnabled = enabled
        btnTabBlocked.isEnabled = enabled
        btnTabAllowed.isEnabled = enabled
        for (i in 0 until layoutBlockedUrlsList.childCount) {
            val row = layoutBlockedUrlsList.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                row.getChildAt(j).isEnabled = enabled
            }
        }
    }

    private fun saveChanges() {
        if (isSaving || !hasUnsavedChanges()) return

        // Guard against locking out connectivity when Whitelist Only is selected without allowed domains
        if (isWhitelistOnlyMode && allowedUrls.isEmpty()) {
            activeDialog?.dismiss()
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_empty_whitelist_title)
                .setMessage(R.string.dialog_empty_whitelist_desc)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
            activeDialog = dialog
            dialog.show()
            return
        }

        val dnsToSave = currentDnsHost.trim().ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        if (!dnsToSave.matches(DNS_HOST_REGEX)) {
            tilDnsHost.error = getString(R.string.error_invalid_dns_host)
            etDnsHost.requestFocus()
            return
        }
        tilDnsHost.error = null

        // Snapshot mutable draft state on Main Thread before entering Dispatchers.IO
        val snapIncognito = isIncognitoBlocked
        val snapTelegramWeb = isTelegramWebBlocked
        val snapYouTubeRestricted = isYouTubeRestricted
        val snapFullSystem = isFullSystemFiltering
        val snapAdBlocking = isAdBlocking
        val snapWhitelistOnly = isWhitelistOnlyMode
        val snapBlockedUrls = blockedUrls.toSet()
        val snapAllowedUrls = allowedUrls.toSet()

        isSaving = true
        updateSaveButtonState()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isUnchanged = dnsToSave.equals(initialPolicy.dnsHost, ignoreCase = true)
                val isKnownPreset = DnsFilteringValidator.isKnownFamilyPreset(dnsToSave)
                val validationResult = if (isUnchanged || isKnownPreset) {
                    DnsFilteringValidator.ValidationResult.Valid
                } else {
                    DnsFilteringValidator.validateDnsHost(dnsToSave)
                }
                val probeFailure: Pair<String?, String>? = when (validationResult) {
                    is DnsFilteringValidator.ValidationResult.Valid -> null
                    is DnsFilteringValidator.ValidationResult.UnfilteredPorn ->
                        getString(R.string.dns_probe_error_not_blocking) to getString(
                            R.string.dns_probe_error_not_blocking_detail,
                            validationResult.domain,
                            validationResult.resolvedIp
                        )
                    is DnsFilteringValidator.ValidationResult.Unreachable ->
                        getString(R.string.dns_probe_error_unreachable) to getString(
                            R.string.dns_probe_error_unreachable_detail,
                            validationResult.reason
                        )
                    is DnsFilteringValidator.ValidationResult.ResolverBroken ->
                        getString(R.string.dns_probe_error_broken) to getString(
                            R.string.dns_probe_error_broken_detail,
                            validationResult.reason
                        )
                }
                if (probeFailure != null) {
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        isSaving = false
                        updateSaveButtonState()
                        tilDnsHost.error = probeFailure.first
                        etDnsHost.requestFocus()
                        activeDialog?.dismiss()
                        val dialog = MaterialAlertDialogBuilder(this@WebFilterSettingsActivity)
                            .setTitle(R.string.dns_probe_dialog_failed_title)
                            .setMessage(probeFailure.second)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
                        activeDialog = dialog
                        dialog.show()
                    }
                    return@launch
                }

                // If full system filtering or custom blocked/allowed lists are active, ensure filterMode enables the filtering pipeline
                val filterMode = when {
                    snapWhitelistOnly -> DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
                    snapFullSystem || snapBlockedUrls.isNotEmpty() || snapAllowedUrls.isNotEmpty() -> DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST
                    else -> DevicePolicyBundle.BROWSER_FILTER_OFF
                }

                val pushedPolicy: DevicePolicyBundle? = withContext(Dispatchers.IO + NonCancellable) {
                    if (isRemote) {
                        policyRepository.mergeAndPushRemotePolicyPatch(
                            wardUid = wardId,
                            fallbackPolicy = initialPolicy,
                            guardianUidFallback = accountabilityRepository.getCachedUid(),
                            guardianNameFallback = accountabilityRepository.getMyDisplayName()
                        ) { basePolicy ->
                            basePolicy.copy(
                                dnsHost = dnsToSave,
                                isIncognitoBlocked = snapIncognito,
                                isTelegramWebBlocked = snapTelegramWeb,
                                isYouTubeRestrictedModeEnabled = snapYouTubeRestricted,
                                isFullSystemFilteringEnabled = snapFullSystem,
                                isAdBlockingEnabled = snapAdBlocking,
                                customBlockedUrls = snapBlockedUrls,
                                customAllowedUrls = snapAllowedUrls,
                                browserFilterMode = filterMode
                            )
                        }
                    } else {
                        val localUpdated = policyRepository.getCurrentPolicy().copy(
                            dnsHost = dnsToSave,
                            isIncognitoBlocked = snapIncognito,
                            isTelegramWebBlocked = snapTelegramWeb,
                            isYouTubeRestrictedModeEnabled = snapYouTubeRestricted,
                            isFullSystemFilteringEnabled = snapFullSystem,
                            isAdBlockingEnabled = snapAdBlocking,
                            customBlockedUrls = snapBlockedUrls,
                            customAllowedUrls = snapAllowedUrls,
                            browserFilterMode = filterMode
                        )
                        if (policyRepository.saveAndEnforceSelfPolicy(localUpdated)) localUpdated else null
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSaving = false
                    if (pushedPolicy != null) {
                        val successRes = if (isRemote) R.string.toast_guardian_policy_saved else R.string.toast_self_policy_saved
                        Toast.makeText(applicationContext, successRes, Toast.LENGTH_SHORT).show()
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_UPDATED_POLICY_JSON, pushedPolicy.toJson())
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        backPressedCallback.isEnabled = false
                        finish()
                    } else {
                        updateSaveButtonState()
                        val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                        Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("WebFilterSettings", "Error saving web filter settings", e)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    isSaving = false
                    updateSaveButtonState()
                    val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                    Toast.makeText(applicationContext, failRes, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun ensureVpnConsentOrPrompt(source: VpnConsentSource, toggle: MaterialSwitch): Boolean {
        if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(this)) {
            // Device Owner authorizes Always-On VPN silently via setAlwaysOnVpnPackage()
            // during policy enforcement, bypassing DISALLOW_CONFIG_VPN which would block
            // the interactive VpnService.prepare() consent dialog.
            return true
        }
        if (pendingVpnConsentSource != null) {
            isProgrammaticSwitchChange = true
            toggle.isChecked = false
            isProgrammaticSwitchChange = false
            return false
        }
        val prepareIntent = try {
            android.net.VpnService.prepare(this)
        } catch (_: Exception) {
            isProgrammaticSwitchChange = true
            toggle.isChecked = false
            isProgrammaticSwitchChange = false
            Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
            return false
        }
        if (prepareIntent != null) {
            isProgrammaticSwitchChange = true
            toggle.isChecked = false
            isProgrammaticSwitchChange = false
            pendingVpnConsentSource = source
            runCatching { vpnConsentLauncher.launch(prepareIntent) }
                .onFailure {
                    pendingVpnConsentSource = null
                    Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
                }
            return false
        }
        return true
    }

    private fun showWhitelistFullSystemWarningDialog() {
        activeDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_whitelist_full_system_warning_title)
            .setMessage(R.string.dialog_whitelist_full_system_warning_msg)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
    }

    private fun showUnaffiliatedUsersFilterWarningDialog() {
        activeDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_web_filter_unaffiliated_warning_title)
            .setMessage(R.string.dialog_web_filter_unaffiliated_warning_msg)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
    }

    private fun showDiscardConfirmationDialog() {
        activeDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_unsaved_changes_title)
            .setMessage(R.string.dialog_unsaved_changes_msg)
            .setNegativeButton(R.string.btn_keep_editing) { d, _ ->
                d.dismiss()
            }
            .setPositiveButton(R.string.btn_discard_changes) { d, _ ->
                d.dismiss()
                backPressedCallback.isEnabled = false
                finish()
            }
            .create()
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"

        private val DNS_HOST_REGEX = Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        private const val STATE_DNS_HOST = "state_dns_host"
        private const val STATE_INCOGNITO_BLOCKED = "state_incognito_blocked"
        private const val STATE_TELEGRAM_WEB_BLOCKED = "state_telegram_web_blocked"
        private const val STATE_YOUTUBE_RESTRICTED = "state_youtube_restricted"
        private const val STATE_FULL_SYSTEM_FILTERING = "state_full_system_filtering"
        private const val STATE_AD_BLOCKING = "state_ad_blocking"
        private const val STATE_PENDING_VPN_CONSENT_SOURCE = "state_pending_vpn_consent_source"
        private const val STATE_WHITELIST_ONLY = "state_whitelist_only"
        private const val STATE_BLOCKED_URLS = "state_blocked_urls"
        private const val STATE_ALLOWED_URLS = "state_allowed_urls"
        private const val STATE_CURRENT_TAB = "state_current_tab"
    }
}
