package com.drmohammed.antiporn.provisioning.ui

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone, lightweight System Apps Manager & Freezer Activity.
 * Runs seamlessly during OOBE Setup Wizard without relying on network or MainActivity state.
 *
 * Implements a Generic Device Owner App Freezer adhering strictly to Google Play Protect compliance.
 */
class SystemAppsManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AntiPorn_SystemApps"

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

    private val inFlightPackages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    data class AppItem(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val isHidden: Boolean,
        val isSelfApp: Boolean,
        val isCritical: Boolean
    )

    private enum class FilterMode {
        ALL, SYSTEM, FROZEN
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipFilterAll: Chip
    private lateinit var chipFilterSystem: Chip
    private lateinit var chipFilterFrozen: Chip
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var rvAppsList: RecyclerView

    private lateinit var adapter: SystemAppsAdapter
    private val masterAppList = mutableListOf<AppItem>()
    private var currentFilterMode = FilterMode.ALL
    private var currentSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_apps_manager)

        findViewById<View>(R.id.layoutRootSystemAppsManager)?.let { rootView ->
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }

        initViews()
        setupListeners()
        loadInstalledApplications()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarSystemAppsManager)
        etSearch = findViewById(R.id.etSearchSystemApps)
        chipGroupFilter = findViewById(R.id.chipGroupFilterSystemApps)
        chipFilterAll = findViewById(R.id.chipFilterAll)
        chipFilterSystem = findViewById(R.id.chipFilterSystem)
        chipFilterFrozen = findViewById(R.id.chipFilterFrozen)
        progressBar = findViewById(R.id.pbLoadingSystemApps)
        tvEmptyState = findViewById(R.id.tvEmptySystemApps)
        rvAppsList = findViewById(R.id.rvSystemAppsList)

        // Initialize chip labels with 0 count to prevent raw format string flashing
        chipFilterAll.text = getString(R.string.chip_filter_all, 0)
        chipFilterSystem.text = getString(R.string.chip_filter_system, 0)
        chipFilterFrozen.text = getString(R.string.chip_filter_frozen, 0)

        toolbar.setNavigationOnClickListener {
            finish()
        }

        adapter = SystemAppsAdapter(
            context = this,
            onToggleFreeze = { item ->
                handleToggleFreeze(item)
            }
        )
        rvAppsList.layoutManager = LinearLayoutManager(this)
        rvAppsList.adapter = adapter
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyCurrentFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilterMode = when {
                checkedIds.contains(R.id.chipFilterSystem) -> FilterMode.SYSTEM
                checkedIds.contains(R.id.chipFilterFrozen) -> FilterMode.FROZEN
                else -> FilterMode.ALL
            }
            applyCurrentFilters()
        }
    }

    private fun loadInstalledApplications() {
        progressBar.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE
        rvAppsList.visibility = View.GONE

        val context = applicationContext
        val selfPackage = context.packageName

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val isDeviceOwner = SafeDpmGateway.isDeviceOwner(context)
            val dpm = if (isDeviceOwner) context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager else null
            val admin = if (isDeviceOwner) SafeDpmGateway.getAdminComponent(context) else null

            val installedApps = queryAllInstalledApplications(pm)
            val items = mutableListOf<AppItem>()

            for (appInfo in installedApps) {
                val pkgName = appInfo.packageName ?: continue
                val label = try {
                    appInfo.loadLabel(pm).toString()
                } catch (_: Throwable) {
                    pkgName
                }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isHidden = if (dpm != null && admin != null) {
                    try {
                        dpm.isApplicationHidden(admin, pkgName)
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    false
                }
                val isSelf = pkgName == selfPackage
                val isCritical = CRITICAL_SYSTEM_PACKAGES.contains(pkgName)

                items.add(
                    AppItem(
                        packageName = pkgName,
                        label = label,
                        isSystem = isSystem,
                        isHidden = isHidden,
                        isSelfApp = isSelf,
                        isCritical = isCritical
                    )
                )
            }

            // Sort: System apps first, then alphabetical by Arabic/English label
            items.sortWith(compareBy({ it.isSelfApp }, { !it.isSystem }, { it.label.lowercase(Locale.ROOT) }))

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                masterAppList.clear()
                masterAppList.addAll(items)
                progressBar.visibility = View.GONE
                rvAppsList.visibility = View.VISIBLE
                updateFilterChipCounts()
                applyCurrentFilters()
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun queryAllInstalledApplications(pm: PackageManager): List<ApplicationInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query installed applications", e)
            emptyList()
        }
    }

    private fun updateFilterChipCounts() {
        val totalCount = masterAppList.size
        val systemCount = masterAppList.count { it.isSystem }
        val frozenCount = masterAppList.count { it.isHidden }

        chipFilterAll.text = getString(R.string.chip_filter_all, totalCount)
        chipFilterSystem.text = getString(R.string.chip_filter_system, systemCount)
        chipFilterFrozen.text = getString(R.string.chip_filter_frozen, frozenCount)
    }

    private fun applyCurrentFilters() {
        val query = currentSearchQuery.lowercase(Locale.ROOT)
        val filtered = masterAppList.filter { item ->
            val matchesFilter = when (currentFilterMode) {
                FilterMode.ALL -> true
                FilterMode.SYSTEM -> item.isSystem
                FilterMode.FROZEN -> item.isHidden
            }

            val matchesSearch = if (query.isBlank()) {
                true
            } else {
                item.label.lowercase(Locale.ROOT).contains(query) ||
                    item.packageName.lowercase(Locale.ROOT).contains(query)
            }

            matchesFilter && matchesSearch
        }

        adapter.submitList(filtered)
        tvEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleToggleFreeze(item: AppItem) {
        if (item.isSelfApp) {
            Toast.makeText(this, R.string.self_app_protected_hint, Toast.LENGTH_SHORT).show()
            adapter.notifyItemChangedByPackage(item.packageName)
            return
        }

        if (inFlightPackages.contains(item.packageName)) {
            return
        }

        val targetHidden = !item.isHidden

        // Critical package safety guardrail: warn user if trying to freeze a critical system service
        if (targetHidden && item.isCritical) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.critical_system_warning_title)
                .setMessage(getString(R.string.critical_system_warning_message, item.label))
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                    adapter.notifyItemChangedByPackage(item.packageName)
                }
                .setPositiveButton(R.string.btn_confirm_freeze) { dialog, _ ->
                    dialog.dismiss()
                    executeFreezeChange(item, targetHidden)
                }
                .setOnCancelListener {
                    adapter.notifyItemChangedByPackage(item.packageName)
                }
                .show()
        } else {
            executeFreezeChange(item, targetHidden)
        }
    }

    private fun executeFreezeChange(item: AppItem, targetHidden: Boolean) {
        val appContext = applicationContext
        inFlightPackages.add(item.packageName)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                SafeDpmGateway.setApplicationHidden(appContext, item.packageName, targetHidden)
                val verification = SafeDpmGateway.isApplicationHidden(appContext, item.packageName)
                val actuallyHidden = verification.getOrNull() ?: false

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    if (actuallyHidden == targetHidden) {
                        val updatedIndex = masterAppList.indexOfFirst { it.packageName == item.packageName }
                        if (updatedIndex != -1) {
                            masterAppList[updatedIndex] = item.copy(isHidden = actuallyHidden)
                        }
                        updateFilterChipCounts()
                        applyCurrentFilters()

                        val msg = if (actuallyHidden) {
                            getString(R.string.toast_freeze_success, item.label)
                        } else {
                            getString(R.string.toast_unfreeze_success, item.label)
                        }
                        Toast.makeText(this@SystemAppsManagerActivity, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        adapter.notifyItemChangedByPackage(item.packageName)
                        Toast.makeText(this@SystemAppsManagerActivity, R.string.toast_freeze_protected_error, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                inFlightPackages.remove(item.packageName)
            }
        }
    }

    // =========================================================================
    // RecyclerView Adapter
    // =========================================================================

    private class SystemAppsAdapter(
        private val context: Context,
        private val onToggleFreeze: (AppItem) -> Unit
    ) : ListAdapter<AppItem, SystemAppsAdapter.AppViewHolder>(AppDiffCallback()) {

        private val iconCache = ConcurrentHashMap<String, Drawable>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_system_app, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        fun notifyItemChangedByPackage(packageName: String) {
            val position = currentList.indexOfFirst { it.packageName == packageName }
            if (position != -1) {
                notifyItemChanged(position)
            }
        }

        inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ShapeableImageView = itemView.findViewById(R.id.ivAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvAppBadge)
            private val tvStatusSubtitle: TextView = itemView.findViewById(R.id.tvAppStatusSubtitle)
            private val switchFrozen: MaterialSwitch = itemView.findViewById(R.id.switchAppFrozen)

            fun bind(item: AppItem) {
                tvName.text = item.label
                tvPackage.text = item.packageName

                // Badge display
                if (item.isSelfApp) {
                    tvBadge.visibility = View.VISIBLE
                    tvBadge.setText(R.string.self_app_protected_hint)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.shield_emerald_radiant))
                } else if (item.isSystem) {
                    tvBadge.visibility = View.VISIBLE
                    tvBadge.setText(R.string.badge_system_app)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.shield_amber_calm))
                } else {
                    tvBadge.visibility = View.VISIBLE
                    tvBadge.setText(R.string.badge_user_app)
                    tvBadge.setTextColor(
                        MaterialColors.getColor(
                            itemView,
                            androidx.appcompat.R.attr.colorPrimary,
                            ContextCompat.getColor(context, R.color.md_theme_light_primary)
                        )
                    )
                }

                // Status text
                tvStatusSubtitle.text = if (item.isHidden) {
                    context.getString(R.string.app_frozen_status)
                } else {
                    context.getString(R.string.app_active_status)
                }
                tvStatusSubtitle.setTextColor(
                    if (item.isHidden) {
                        ContextCompat.getColor(context, R.color.shield_amber_calm)
                    } else {
                        MaterialColors.getColor(
                            itemView,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            ContextCompat.getColor(context, R.color.md_theme_light_onSurfaceVariant)
                        )
                    }
                )

                // Switch state
                switchFrozen.setOnCheckedChangeListener(null)
                switchFrozen.isChecked = item.isHidden
                switchFrozen.isEnabled = !item.isSelfApp

                switchFrozen.setOnClickListener {
                    onToggleFreeze(item)
                }

                // Asynchronous icon loading with cache
                loadAppIcon(item.packageName, ivIcon)
            }

            private fun loadAppIcon(packageName: String, targetView: ShapeableImageView) {
                targetView.tag = packageName
                val cached = iconCache[packageName]
                if (cached != null) {
                    targetView.setImageDrawable(cached)
                    return
                }

                targetView.setImageResource(android.R.drawable.sym_def_app_icon)
                (context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val icon = try {
                        context.packageManager.getApplicationIcon(packageName)
                    } catch (_: Throwable) {
                        null
                    } ?: ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)

                    if (icon != null) {
                        iconCache[packageName] = icon
                        withContext(Dispatchers.Main) {
                            if (targetView.tag == packageName) {
                                targetView.setImageDrawable(icon)
                            }
                        }
                    }
                }
            }
        }
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem == newItem
        }
    }
}
