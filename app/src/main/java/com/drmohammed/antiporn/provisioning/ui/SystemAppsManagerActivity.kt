package com.drmohammed.antiporn.provisioning.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone, lightweight System Apps Manager & Freezer Activity.
 * Runs seamlessly during OOBE Setup Wizard without relying on network or MainActivity state.
 */
class SystemAppsManagerActivity : AppCompatActivity() {

    private val viewModel: SystemAppsManagerViewModel by viewModel()

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
    private var isProgrammaticUiUpdate = false

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
        setupFragmentResultListeners()
        setupListeners()
        observeViewModel()
        viewModel.loadInstalledApplicationsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHiddenStates()
    }

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(REQ_CRITICAL_FREEZE_CONFIRM, this) { _, bundle ->
            val pkg = bundle.getBundle(ConfirmActionDialogFragment.RESULT_PAYLOAD)
                ?.getString(KEY_CRITICAL_PKG)
                .orEmpty()
            if (pkg.isBlank()) return@setFragmentResultListener
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                val targetItem = viewModel.uiState.value.masterAppList.firstOrNull { it.packageName == pkg }
                    ?: return@setFragmentResultListener
                viewModel.executeFreezeChange(targetItem, targetHidden = true)
            } else {
                adapter.notifyItemChangedByPackage(pkg)
            }
        }
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
                if (!isProgrammaticUiUpdate) {
                    viewModel.setSearchQuery(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isProgrammaticUiUpdate) return@setOnCheckedStateChangeListener
            val mode = when {
                checkedIds.contains(R.id.chipFilterSystem) -> SystemAppsFilterMode.SYSTEM
                checkedIds.contains(R.id.chipFilterFrozen) -> SystemAppsFilterMode.FROZEN
                else -> SystemAppsFilterMode.ALL
            }
            viewModel.setFilterMode(mode)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is SystemAppsManagerEvent.FreezeSuccess -> {
                                val msg = if (event.isFrozen) {
                                    getString(R.string.toast_freeze_success, event.appLabel)
                                } else {
                                    getString(R.string.toast_unfreeze_success, event.appLabel)
                                }
                                Toast.makeText(this@SystemAppsManagerActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                            is SystemAppsManagerEvent.FreezeFailed -> {
                                adapter.notifyItemChangedByPackage(event.packageName)
                                Toast.makeText(
                                    this@SystemAppsManagerActivity,
                                    event.errorResId,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: SystemAppsManagerUiState) {
        progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        rvAppsList.visibility = if (state.isLoading) View.GONE else View.VISIBLE
        tvEmptyState.visibility = if (!state.isLoading && state.filteredApps.isEmpty()) View.VISIBLE else View.GONE

        chipFilterAll.text = getString(R.string.chip_filter_all, state.totalCount)
        chipFilterSystem.text = getString(R.string.chip_filter_system, state.systemCount)
        chipFilterFrozen.text = getString(R.string.chip_filter_frozen, state.frozenCount)

        val expectedChipId = when (state.currentFilterMode) {
            SystemAppsFilterMode.ALL -> R.id.chipFilterAll
            SystemAppsFilterMode.SYSTEM -> R.id.chipFilterSystem
            SystemAppsFilterMode.FROZEN -> R.id.chipFilterFrozen
        }
        isProgrammaticUiUpdate = true
        if (chipGroupFilter.checkedChipId != expectedChipId) {
            chipGroupFilter.check(expectedChipId)
        }
        if (etSearch.text?.toString()?.trim().orEmpty() != state.currentSearchQuery) {
            etSearch.setText(state.currentSearchQuery)
            etSearch.setSelection(state.currentSearchQuery.length)
        }
        isProgrammaticUiUpdate = false

        adapter.setInFlightPackages(state.inFlightPackages)
        adapter.submitList(state.filteredApps)
    }

    private fun handleToggleFreeze(item: SystemAppItem) {
        if (item.isSelfApp) {
            Toast.makeText(this, R.string.self_app_protected_hint, Toast.LENGTH_SHORT).show()
            adapter.notifyItemChangedByPackage(item.packageName)
            return
        }

        if (viewModel.uiState.value.inFlightPackages.contains(item.packageName)) {
            adapter.notifyItemChangedByPackage(item.packageName)
            return
        }

        val targetHidden = !item.isHidden
        if (targetHidden && item.isCritical) {
            supportFragmentManager.showSafely(TAG_CRITICAL_FREEZE_CONFIRM) {
                ConfirmActionDialogFragment.newInstance(
                    requestKey = REQ_CRITICAL_FREEZE_CONFIRM,
                    titleRes = R.string.critical_system_warning_title,
                    messageText = getString(R.string.critical_system_warning_message, item.label),
                    positiveRes = R.string.btn_confirm_freeze,
                    negativeRes = android.R.string.cancel,
                    payload = bundleOf(KEY_CRITICAL_PKG to item.packageName)
                )
            }
        } else {
            viewModel.executeFreezeChange(item, targetHidden)
        }
    }

    companion object {
        private const val REQ_CRITICAL_FREEZE_CONFIRM = "req_sys_apps_critical_freeze"
        private const val TAG_CRITICAL_FREEZE_CONFIRM = "tag_sys_apps_critical_freeze"
        private const val KEY_CRITICAL_PKG = "key_critical_pkg"
    }

    private class SystemAppsAdapter(
        private val context: Context,
        private val onToggleFreeze: (SystemAppItem) -> Unit
    ) : ListAdapter<SystemAppItem, SystemAppsAdapter.AppViewHolder>(AppDiffCallback()) {

        private val iconCache = ConcurrentHashMap<String, Drawable>()
        private var inFlightPackages: Set<String> = emptySet()

        fun setInFlightPackages(packages: Set<String>) {
            if (inFlightPackages == packages) return
            val changed = (inFlightPackages - packages) + (packages - inFlightPackages)
            inFlightPackages = packages
            currentList.forEachIndexed { index, item ->
                if (item.packageName in changed) {
                    notifyItemChanged(index)
                }
            }
        }

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

            fun bind(item: SystemAppItem) {
                tvName.text = item.label
                tvPackage.text = item.packageName

                tvBadge.visibility = View.VISIBLE
                if (item.isSelfApp) {
                    tvBadge.setText(R.string.self_app_protected_hint)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.shield_emerald_radiant))
                } else if (item.isSystem) {
                    tvBadge.setText(R.string.badge_system_app)
                    tvBadge.setTextColor(ContextCompat.getColor(context, R.color.shield_amber_calm))
                } else {
                    tvBadge.setText(R.string.badge_user_app)
                    tvBadge.setTextColor(
                        MaterialColors.getColor(
                            itemView,
                            androidx.appcompat.R.attr.colorPrimary,
                            ContextCompat.getColor(context, R.color.md_theme_light_primary)
                        )
                    )
                }

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

                switchFrozen.setOnCheckedChangeListener(null)
                switchFrozen.isChecked = item.isHidden
                switchFrozen.isEnabled = !item.isSelfApp && !inFlightPackages.contains(item.packageName)
                switchFrozen.setOnClickListener {
                    onToggleFreeze(item)
                }

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

    private class AppDiffCallback : DiffUtil.ItemCallback<SystemAppItem>() {
        override fun areItemsTheSame(oldItem: SystemAppItem, newItem: SystemAppItem): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: SystemAppItem, newItem: SystemAppItem): Boolean =
            oldItem == newItem
    }
}
