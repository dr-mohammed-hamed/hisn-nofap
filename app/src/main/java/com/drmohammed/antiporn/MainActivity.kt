package com.drmohammed.antiporn

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.notification.router.NotificationActionRouter
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.ActivityMainBinding
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.ota.model.OtaVersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.accountability.AccountabilityFragment
import com.drmohammed.antiporn.ui.control.SelfControlFragment
import com.drmohammed.antiporn.ui.home.HomeFragment
import com.drmohammed.antiporn.ui.main.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.settings.SettingsFragment
import com.drmohammed.antiporn.ui.splash.SplashAnimationController
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Main application dashboard activity hosting the Material 3 Bottom Navigation Hub.
 * Coordinates system services and manages the 4 primary tabs via ViewPager2:
 * 1. Home (Hero Shield & 1-Hour Nap)
 * 2. Self Control (Policies, Curfew, Restrictions)
 * 3. Accountability (Partner Hub & Pairing)
 * 4. Settings (AI Detection, OTA Updates, Google Account)
 */
class MainActivity : AppCompatActivity(),
    HomeFragment.HomeActionListener,
    SettingsFragment.SettingsActionListener,
    AccountabilityFragment.AccountabilityNavigationListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewPager: ViewPager2
    private lateinit var navBar: NavigationBarView
    private val mainViewModel: MainViewModel by viewModel()

    private var splashController: SplashAnimationController? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastAiToggleMs = 0L
    private var hasShownOtaPrompt = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewPager.currentItem != 0) {
                viewPager.setCurrentItem(0, true) // Smooth scroll back to Home tab
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenAiProjectionService::class.java).apply {
                action = ScreenAiProjectionService.ACTION_START
                putExtra(ScreenAiProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenAiProjectionService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                revertScreenAiPolicyToDisabled()
                if (isFinishing || isDestroyed) return@launch
                refreshSettingsAiUi()
            }
        }
    }

    private val uninstallStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Uninstallation status handled reactively by child fragments
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::navBar.isInitialized) {
            outState.putInt(KEY_SELECTED_TAB, navBar.selectedItemId)
        }
        outState.putBoolean(KEY_HAS_SHOWN_OTA_PROMPT, hasShownOtaPrompt)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasShownOtaPrompt = savedInstanceState?.getBoolean(KEY_HAS_SHOWN_OTA_PROMPT, false) ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewPager = binding.viewPagerMain
        navBar = binding.bottomNavigationView ?: binding.navigationRailView
            ?: throw IllegalStateException("NavigationBarView missing from activity_main layout")

        val isRailMode = binding.navigationRailView != null
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutMainRoot) { root, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (isRailMode) {
                val isRtl = ViewCompat.getLayoutDirection(root) == ViewCompat.LAYOUT_DIRECTION_RTL ||
                    resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                if (isRtl) {
                    navBar.setPadding(0, insets.top, insets.right, insets.bottom)
                    viewPager.setPadding(insets.left, insets.top, 0, insets.bottom)
                } else {
                    navBar.setPadding(insets.left, insets.top, 0, insets.bottom)
                    viewPager.setPadding(0, insets.top, insets.right, insets.bottom)
                }
            } else {
                viewPager.setPadding(insets.left, insets.top, insets.right, 0)
                navBar.setPadding(insets.left, 0, insets.right, insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }

        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        viewPager.isUserInputEnabled = true

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        supportFragmentManager.setFragmentResultListener(REQ_OTA_UPDATE_CONFIRM, this) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                val infoJson = bundle.getBundle(ConfirmActionDialogFragment.RESULT_PAYLOAD)
                    ?.getString(KEY_OTA_INFO_JSON)
                    ?: return@setFragmentResultListener
                val info = runCatching { OtaVersionInfo.fromJson(infoJson) }
                    .onFailure { Timber.tag(TAG).w(it, "Failed parsing OTA info JSON from dialog payload") }
                    .getOrNull()
                    ?: return@setFragmentResultListener
                val otaRepo = OtaRepository.getInstance(applicationContext)
                lifecycleScope.launch {
                    otaRepo.downloadAndInstall(applicationContext, info, showNotification = true)
                }
            }
        }

        setupNavigation(savedInstanceState)
        handleIntentNavigation(intent)
        setupOtaTracking()

        splashController = SplashAnimationController(this).apply {
            showIfColdBoot()
        }
    }

    private inner class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> SelfControlFragment()
                2 -> AccountabilityFragment()
                3 -> SettingsFragment()
                else -> throw IllegalArgumentException("Invalid pager position: $position")
            }
        }
    }

    private fun positionToMenuId(position: Int): Int = MainViewModel.positionToMenuId(position)

    private fun menuIdToPosition(menuId: Int): Int = MainViewModel.menuIdToPosition(menuId)

    private fun setupNavigation(savedInstanceState: Bundle?) {
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val targetMenuId = positionToMenuId(position)
                if (navBar.selectedItemId != targetMenuId) {
                    navBar.selectedItemId = targetMenuId
                }
                mainViewModel.selectTab(position)
                backPressedCallback.isEnabled = (position != 0)
            }
        })

        navBar.setOnItemSelectedListener { item ->
            val targetPosition = menuIdToPosition(item.itemId)
            if (viewPager.currentItem != targetPosition) {
                viewPager.setCurrentItem(targetPosition, false)
            }
            mainViewModel.selectTab(targetPosition)
            true
        }

        val restoredTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.nav_home) ?: R.id.nav_home
        val targetPosition = menuIdToPosition(restoredTabId)
        viewPager.setCurrentItem(targetPosition, false)
        navBar.selectedItemId = restoredTabId
        mainViewModel.selectTab(targetPosition)
        backPressedCallback.isEnabled = (targetPosition != 0)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.navigationEvents.collect { event ->
                    when (event) {
                        is MainViewModel.MainNavigationEvent.OpenHome -> navigateToTab(R.id.nav_home)
                        is MainViewModel.MainNavigationEvent.OpenSelfControl -> navigateToTab(R.id.nav_self_control)
                        is MainViewModel.MainNavigationEvent.OpenAccountability -> navigateToTab(R.id.nav_accountability)
                        is MainViewModel.MainNavigationEvent.OpenSettings -> navigateToTab(R.id.nav_settings)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentNavigation(intent)
    }

    private fun handleIntentNavigation(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(NotificationActionRouter.EXTRA_NAVIGATE_TAB)) {
            val tabPos = intent.getIntExtra(NotificationActionRouter.EXTRA_NAVIGATE_TAB, 0)
            intent.removeExtra(NotificationActionRouter.EXTRA_NAVIGATE_TAB)
            val menuId = positionToMenuId(tabPos)
            navigateToTab(menuId)
        }
        if (intent.getBooleanExtra("EXTRA_REQUEST_AI_TOGGLE", false)) {
            intent.removeExtra("EXTRA_REQUEST_AI_TOGGLE")
            onToggleAiRequested(true)
        }
    }

    fun navigateToTab(tabId: Int) {
        val targetPosition = menuIdToPosition(tabId)
        if (viewPager.currentItem != targetPosition) {
            viewPager.setCurrentItem(targetPosition, false)
        }
        if (navBar.selectedItemId != tabId) {
            navBar.selectedItemId = tabId
        }
        mainViewModel.selectTab(targetPosition)
    }

    override fun onNavigateToSelfControl() {
        navigateToTab(R.id.nav_self_control)
    }

    override fun onStart() {
        super.onStart()
        val statusFilter = IntentFilter(AppSuspensionShield.ACTION_UNINSTALL_STATUS)
        ContextCompat.registerReceiver(this, uninstallStatusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        val isDeviceOwner = ProtectionEngine.isDeviceOwner(this)
        if (isDeviceOwner) {
            CurfewScheduler.scheduleNextAlarms(this)
            CurfewEnforcer.syncBlockStateAsync(this)
            lifecycleScope.launch(Dispatchers.IO) {
                PolicyRepository.getInstance(this@MainActivity).retryPendingGuardianTeardownIfNeeded()
                if (!ProtectionEngine.isSystemFullyProtected(this@MainActivity)) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            triggerEnforcement(showToast = false)
                        }
                    }
                } else if (!com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress) {
                    com.drmohammed.antiporn.watchdog.WatchdogOrchestrator.ensureActive(this@MainActivity)
                }
            }
            com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(this).syncZeroTrustBaselineAndReconcileAsync()
            com.drmohammed.antiporn.core.engine.SystemAppsHealingManager.healPreInstalledStoresAsync(this)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                com.drmohammed.antiporn.core.engine.DeviceOwnerTeardownManager
                    .flushPendingCloudTeardownAckIfNeeded(this@MainActivity)
            }
        }
        AppInventoryManager.getInstance(this).syncInventoryToCloudAsync()
    }

    override fun onPause() {
        super.onPause()
        splashController?.cancel()
        splashController = null
    }

    override fun onDestroy() {
        splashController?.cancel()
        splashController = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(uninstallStatusReceiver)
        } catch (e: Exception) {
            Timber.tag(TAG).d("Uninstall status receiver not registered: %s", e.message)
        }
    }

    override fun onEnforceRequested() {
        triggerEnforcement(showToast = true)
    }

    override fun onToggleAiRequested(enable: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAiToggleMs < 1000L) return
        lastAiToggleMs = now

        val policyRepo = PolicyRepository.getInstance(applicationContext)
        val currentPolicy = policyRepo.getCurrentPolicy()
        if (currentPolicy.isSupervised && !currentPolicy.isSelfControlAllowed) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            refreshSettingsAiUi()
            return
        }

        val updatedPolicy = currentPolicy.copy(
            isScreenAiEnabledGlobally = enable,
            screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
        )

        lifecycleScope.launch {
            if (enable) {
                val savedPolicy = policyRepo.mergeAndSaveSelfPolicyPatch { latest ->
                    latest.copy(
                        isScreenAiEnabledGlobally = true,
                        screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
                    )
                }
                if (isFinishing || isDestroyed) return@launch
                if (savedPolicy == null) {
                    Toast.makeText(this@MainActivity, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
                    refreshSettingsAiUi()
                    return@launch
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val reconcileResult = withContext(Dispatchers.IO) {
                        AccessibilityPolicyCoordinator.reconcile(this@MainActivity, savedPolicy)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    when (reconcileResult) {
                        is AccessibilityReconcileResult.AlreadyActive,
                        is AccessibilityReconcileResult.EnabledSilently -> {
                            if (ScreenAiProjectionService.isRunning) {
                                stopService(Intent(this@MainActivity, ScreenAiProjectionService::class.java))
                                withTimeoutOrNull(2_000L) {
                                    ScreenAiProjectionService.isRunningFlow.first { !it }
                                }
                            }
                            HisnAccessibilityService.instance?.syncStandaloneAiState(true)
                            if (isFinishing || isDestroyed) return@launch
                            refreshSettingsAiUi()
                            return@launch
                        }
                        else -> {
                            // Fall back to MediaProjection flow on QR OOBE / manual activation without forcing A11y
                            launchMediaProjectionFlow()
                        }
                    }
                } else {
                    launchMediaProjectionFlow()
                }
            } else {
                stopService(Intent(this@MainActivity, ScreenAiProjectionService::class.java))
                val savedPolicy = policyRepo.mergeAndSaveSelfPolicyPatch { latest ->
                    latest.copy(
                        isScreenAiEnabledGlobally = false,
                        screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
                    )
                } ?: updatedPolicy
                withContext(Dispatchers.IO) {
                    AccessibilityPolicyCoordinator.reconcile(this@MainActivity, savedPolicy)
                }
                if (!isFinishing && !isDestroyed) {
                    refreshSettingsAiUi()
                }
            }
        }
    }

    private suspend fun launchMediaProjectionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            revertScreenAiPolicyToDisabled()
            if (isFinishing || isDestroyed) return
            refreshSettingsAiUi()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
            Toast.makeText(this, R.string.overlay_permission_prompt, Toast.LENGTH_LONG).show()
            return
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager != null) {
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            revertScreenAiPolicyToDisabled()
            if (isFinishing || isDestroyed) return
            refreshSettingsAiUi()
            Toast.makeText(this, R.string.ai_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun revertScreenAiPolicyToDisabled() {
        val policyRepo = PolicyRepository.getInstance(applicationContext)
        val current = policyRepo.getCurrentPolicy()
        if (current.isScreenAiEnabledGlobally) {
            val reverted = policyRepo.mergeAndSaveSelfPolicyPatch { latest ->
                latest.copy(
                    isScreenAiEnabledGlobally = false,
                    screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
                )
            } ?: current.copy(
                isScreenAiEnabledGlobally = false,
                screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
            )
            withContext(Dispatchers.IO) {
                AccessibilityPolicyCoordinator.reconcile(this@MainActivity, reverted)
            }
        }
    }

    private fun refreshSettingsAiUi() {
        supportFragmentManager.fragments
            .filterIsInstance<SettingsFragment>()
            .forEach { it.updateAiUi() }
    }

    override fun onCheckOtaRequested() {
        val otaRepo = OtaRepository.getInstance(applicationContext)
        when (val currentState = otaRepo.updateState.value) {
            is OtaUpdateState.UpdateAvailable -> {
                showOtaUpdateDialog(currentState.info, otaRepo)
            }
            is OtaUpdateState.Downloading, is OtaUpdateState.Verifying, is OtaUpdateState.Installing, is OtaUpdateState.Checking -> {
                // In progress
            }
            else -> {
                lifecycleScope.launch {
                    otaRepo.checkForUpdates(applicationContext)
                }
            }
        }
    }

    override fun onLinkGoogleRequested() {
        lifecycleScope.launch {
            val googleAuthManager = com.drmohammed.antiporn.cloud.auth.GoogleAuthManager.getInstance(applicationContext)
            val result = googleAuthManager.signInOrLinkWithGoogle(this@MainActivity)
            if (isFinishing || isDestroyed) return@launch
            result.fold(
                onSuccess = {
                    Toast.makeText(this@MainActivity, R.string.toast_account_secured_success, Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_account_secured_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }



    private fun getCurrentAppVersion(): Pair<String, Long> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val code = PackageInfoCompat.getLongVersionCode(packageInfo)
            val name = packageInfo.versionName ?: "1.0"
            Pair(name, code)
        } catch (e: Exception) {
            Pair("1.0", 1L)
        }
    }

    private fun setupOtaTracking() {
        val otaRepo = OtaRepository.getInstance(applicationContext)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                otaRepo.updateState.collect { state ->
                    if (!isFinishing && !isDestroyed) {
                        if (state is OtaUpdateState.UpdateAvailable && !hasShownOtaPrompt && !supportFragmentManager.isStateSaved) {
                            hasShownOtaPrompt = true
                            showOtaUpdateDialog(state.info, otaRepo)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            delay(1200L)
            if (!isFinishing && !isDestroyed) {
                otaRepo.checkForUpdates(applicationContext)
            }
        }
    }

    private fun showOtaUpdateDialog(info: OtaVersionInfo, otaRepo: OtaRepository) {
        if (isFinishing || isDestroyed) return
        val message = getString(R.string.ota_update_available, info.versionName) +
            (if (!info.releaseNotes.isNullOrBlank()) "\n\n${info.releaseNotes}\n" else "") +
            "\n\n" + getString(R.string.ota_dialog_prompt)
        supportFragmentManager.showSafely(TAG_OTA_UPDATE_CONFIRM) {
            ConfirmActionDialogFragment.newInstance(
                requestKey = REQ_OTA_UPDATE_CONFIRM,
                titleRes = R.string.card_ota_title,
                messageText = message,
                positiveRes = R.string.btn_download_update,
                negativeRes = android.R.string.cancel,
                payload = bundleOf(KEY_OTA_INFO_JSON to info.toJson())
            )
        }
    }

    private fun triggerEnforcement(showToast: Boolean) {
        ProtectionEngine.applyAllAsync(this) { results ->
            if (!isFinishing && !isDestroyed) {
                val allSuccess = results.all { it.success }
                val message = if (allSuccess) {
                    getString(R.string.toast_enforce_success, DnsShield.getEffectiveDnsHost(this@MainActivity))
                } else {
                    results.firstOrNull { !it.success }?.message
                        ?: getString(R.string.status_need_enforce_desc)
                }

                if (showToast) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_MainActivity"
        private const val KEY_SELECTED_TAB = "key_selected_tab"
        private const val KEY_HAS_SHOWN_OTA_PROMPT = "key_has_shown_ota_prompt"
        private const val REQ_OTA_UPDATE_CONFIRM = "req_ota_update_confirm"
        private const val TAG_OTA_UPDATE_CONFIRM = "tag_ota_update_confirm"
        private const val KEY_OTA_INFO_JSON = "key_ota_info_json"
    }
}
