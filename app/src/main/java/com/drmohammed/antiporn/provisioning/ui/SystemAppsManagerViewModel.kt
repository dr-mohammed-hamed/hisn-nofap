package com.drmohammed.antiporn.provisioning.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

data class SystemAppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isHidden: Boolean,
    val isSelfApp: Boolean,
    val isCritical: Boolean
)

enum class SystemAppsFilterMode {
    ALL, SYSTEM, FROZEN
}

data class SystemAppsManagerUiState(
    val masterAppList: List<SystemAppItem> = emptyList(),
    val filteredApps: List<SystemAppItem> = emptyList(),
    val currentFilterMode: SystemAppsFilterMode = SystemAppsFilterMode.ALL,
    val currentSearchQuery: String = "",
    val inFlightPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val totalCount: Int = 0,
    val systemCount: Int = 0,
    val frozenCount: Int = 0
)

sealed class SystemAppsManagerEvent {
    data class FreezeSuccess(val appLabel: String, val isFrozen: Boolean) : SystemAppsManagerEvent()
    data class FreezeFailed(val packageName: String, val errorResId: Int) : SystemAppsManagerEvent()
}

/**
 * ViewModel managing installed system/user app list, filter/search state,
 * and Device Owner hide/unhide operations across configuration changes.
 */
class SystemAppsManagerViewModel(
    application: Application
) : AndroidViewModel(application) {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var setApplicationHiddenAction: suspend (Context, String, Boolean) -> DpmExecutionResult<Boolean> =
        { ctx, pkg, hidden -> SafeDpmGateway.setApplicationHidden(ctx, pkg, hidden) }

    @VisibleForTesting
    internal var isApplicationHiddenAction: suspend (Context, String) -> DpmExecutionResult<Boolean> =
        { ctx, pkg -> SafeDpmGateway.isApplicationHidden(ctx, pkg) }

    @VisibleForTesting
    internal var isDeviceOwnerCheck: (Context) -> Boolean = { ctx ->
        SafeDpmGateway.isDeviceOwner(ctx)
    }

    @VisibleForTesting
    internal var queryInstalledAppsAction: (Context) -> List<ApplicationInfo> = { ctx ->
        queryAllInstalledApplications(ctx.packageManager)
    }

    @VisibleForTesting
    internal var loadAppLabelAction: (Context, ApplicationInfo, String) -> String = { ctx, appInfo, pkgName ->
        try {
            appInfo.loadLabel(ctx.packageManager).toString()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed loading label for %s", pkgName)
            pkgName
        }
    }

    private val _uiState = MutableStateFlow(SystemAppsManagerUiState())
    val uiState: StateFlow<SystemAppsManagerUiState> = _uiState.asStateFlow()

    private val _events = Channel<SystemAppsManagerEvent>(Channel.BUFFERED)
    val events: Flow<SystemAppsManagerEvent> = _events.receiveAsFlow()

    private var isInitialized = false

    fun loadInstalledApplicationsIfNeeded() {
        if (isInitialized) return
        isInitialized = true
        loadInstalledApplications()
    }

    fun refreshHiddenStates() {
        if (!isInitialized || _uiState.value.isLoading) return
        val context = getApplication<Application>()
        viewModelScope.launch(ioDispatcher) {
            try {
                val isDeviceOwner = isDeviceOwnerCheck(context)
                val currentList = _uiState.value.masterAppList
                val inFlight = _uiState.value.inFlightPackages
                val updated = currentList.map { item ->
                    if (inFlight.contains(item.packageName)) {
                        item
                    } else {
                        val hidden = if (isDeviceOwner) {
                            isApplicationHiddenAction(context, item.packageName).getOrNull() ?: false
                        } else {
                            false
                        }
                        if (hidden != item.isHidden) item.copy(isHidden = hidden) else item
                    }
                }
                if (updated != currentList) {
                    _uiState.update { current ->
                        buildUpdatedState(current = current, masterList = updated)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed refreshing hidden states")
            }
        }
    }

    fun loadInstalledApplications() {
        _uiState.update { it.copy(isLoading = true) }
        val context = getApplication<Application>()
        val selfPackage = context.packageName

        viewModelScope.launch(ioDispatcher) {
            try {
                val isDeviceOwner = isDeviceOwnerCheck(context)

                val installedApps = queryInstalledAppsAction(context)
                val items = mutableListOf<SystemAppItem>()

                for (appInfo in installedApps) {
                    val pkgName = appInfo.packageName ?: continue
                    val label = loadAppLabelAction(context, appInfo, pkgName)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isHidden = if (isDeviceOwner) {
                        isApplicationHiddenAction(context, pkgName).getOrNull() ?: false
                    } else {
                        false
                    }
                    items.add(
                        SystemAppItem(
                            packageName = pkgName,
                            label = label,
                            isSystem = isSystem,
                            isHidden = isHidden,
                            isSelfApp = pkgName == selfPackage,
                            isCritical = CRITICAL_SYSTEM_PACKAGES.contains(pkgName)
                        )
                    )
                }

                items.sortWith(compareBy({ it.isSelfApp }, { !it.isSystem }, { it.label.lowercase(Locale.ROOT) }))

                _uiState.update { current ->
                    buildUpdatedState(
                        current = current.copy(isLoading = false),
                        masterList = items
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed loading installed applications")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        val trimmed = query.trim()
        _uiState.update { current ->
            if (current.currentSearchQuery == trimmed) {
                current
            } else {
                buildUpdatedState(
                    current = current.copy(currentSearchQuery = trimmed),
                    masterList = current.masterAppList
                )
            }
        }
    }

    fun setFilterMode(mode: SystemAppsFilterMode) {
        _uiState.update { current ->
            if (current.currentFilterMode == mode) {
                current
            } else {
                buildUpdatedState(
                    current = current.copy(currentFilterMode = mode),
                    masterList = current.masterAppList
                )
            }
        }
    }

    fun executeFreezeChange(item: SystemAppItem, targetHidden: Boolean) {
        if (_uiState.value.inFlightPackages.contains(item.packageName)) return
        val appContext = getApplication<Application>()

        _uiState.update { it.copy(inFlightPackages = it.inFlightPackages + item.packageName) }

        viewModelScope.launch(ioDispatcher) {
            try {
                val (setResult, verification) = withContext(NonCancellable) {
                    val set = setApplicationHiddenAction(appContext, item.packageName, targetHidden)
                    val verify = isApplicationHiddenAction(appContext, item.packageName)
                    set to verify
                }
                val actuallyHidden = verification.getOrNull()

                if (setResult.isSuccess && verification.isSuccess && actuallyHidden == targetHidden) {
                    _uiState.update { current ->
                        val updatedMaster = current.masterAppList.map { existing ->
                            if (existing.packageName == item.packageName) {
                                existing.copy(isHidden = targetHidden)
                            } else {
                                existing
                            }
                        }
                        buildUpdatedState(current = current, masterList = updatedMaster)
                    }
                    _events.send(SystemAppsManagerEvent.FreezeSuccess(item.label, targetHidden))
                } else {
                    _events.send(
                        SystemAppsManagerEvent.FreezeFailed(
                            packageName = item.packageName,
                            errorResId = R.string.toast_freeze_protected_error
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed toggling freeze state for %s", item.packageName)
                _events.send(
                    SystemAppsManagerEvent.FreezeFailed(
                        packageName = item.packageName,
                        errorResId = R.string.toast_freeze_protected_error
                    )
                )
            } finally {
                _uiState.update { it.copy(inFlightPackages = it.inFlightPackages - item.packageName) }
            }
        }
    }

    private fun buildUpdatedState(
        current: SystemAppsManagerUiState,
        masterList: List<SystemAppItem>
    ): SystemAppsManagerUiState {
        val query = current.currentSearchQuery.lowercase(Locale.ROOT)
        val filtered = masterList.filter { item ->
            val matchesFilter = when (current.currentFilterMode) {
                SystemAppsFilterMode.ALL -> true
                SystemAppsFilterMode.SYSTEM -> item.isSystem
                SystemAppsFilterMode.FROZEN -> item.isHidden
            }
            val matchesSearch = if (query.isBlank()) {
                true
            } else {
                item.label.lowercase(Locale.ROOT).contains(query) ||
                    item.packageName.lowercase(Locale.ROOT).contains(query)
            }
            matchesFilter && matchesSearch
        }

        return current.copy(
            masterAppList = masterList,
            filteredApps = filtered,
            totalCount = masterList.size,
            systemCount = masterList.count { it.isSystem },
            frozenCount = masterList.count { it.isHidden }
        )
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun queryAllInstalledApplications(pm: PackageManager): List<ApplicationInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query installed applications")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "AntiPorn_SystemAppsVM"

        /**
         * Standard AOSP core system packages that require explicit user warning before freezing
         * to prevent device bootloops or SystemUI black screens.
         */
        private val CRITICAL_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.google.android.packageinstaller",
            "com.google.android.gms",
            "com.android.vending",
            "com.android.phone",
            "com.google.android.dialer",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher"
        )
    }
}
