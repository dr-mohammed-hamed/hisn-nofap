package com.drmohammed.antiporn.pageshield.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ItemPageShieldAppBinding
import com.drmohammed.antiporn.databinding.ItemPageShieldPageBinding
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.model.PageShieldReaction
import com.drmohammed.antiporn.pageshield.registry.ProtectedAppDefinition
import com.drmohammed.antiporn.ui.accountability.AppIconResolver

class PageShieldAppsAdapter(
    private val onToggleExpand: (packageName: String) -> Unit,
    private val onPageToggled: (packageName: String, pageId: String, isChecked: Boolean) -> Unit,
    private val onReactionChanged: (packageName: String, reaction: PageShieldReaction) -> Unit,
    private val onFreezeDurationChanged: (packageName: String, minutes: Int) -> Unit
) : RecyclerView.Adapter<PageShieldAppsAdapter.AppViewHolder>() {

    private var apps: List<ProtectedAppDefinition> = emptyList()
    private var expandedPackages: Set<String> = emptySet()
    private var config: PageShieldConfig = PageShieldConfig()

    fun submitData(
        newApps: List<ProtectedAppDefinition>,
        newExpanded: Set<String>,
        newConfig: PageShieldConfig
    ) {
        val oldApps = this.apps
        val oldExpanded = this.expandedPackages
        val oldConfig = this.config

        this.apps = newApps
        this.expandedPackages = newExpanded
        this.config = newConfig

        if (oldApps != newApps) {
            notifyDataSetChanged()
            return
        }

        // Granular updates per app
        for (i in newApps.indices) {
            val pkg = newApps[i].packageName
            val expandChanged = (pkg in oldExpanded) != (pkg in newExpanded)
            val oldPolicy = oldConfig.appPolicies[pkg]
            val newPolicy = newConfig.appPolicies[pkg]

            val policyChanged = if (oldPolicy != null && newPolicy != null &&
                oldPolicy.enabledPageIds == newPolicy.enabledPageIds &&
                oldPolicy.reaction == newPolicy.reaction &&
                oldPolicy.freezeDurationMinutes != newPolicy.freezeDurationMinutes
            ) {
                // If only freezeDurationMinutes changed, avoid rebinding view holder to prevent typing collisions
                false
            } else {
                oldPolicy != newPolicy
            }

            if (expandChanged || policyChanged) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemPageShieldAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        super.onViewRecycled(holder)
        AppIconResolver.cancelLoad(holder.binding.ivAppIcon)
    }

    override fun getItemCount(): Int = apps.size

    inner class AppViewHolder(
        val binding: ItemPageShieldAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var freezeDurationWatcher: android.text.TextWatcher? = null
        private var isBinding = false

        fun bind(appDef: ProtectedAppDefinition) {
            isBinding = true
            val context = itemView.context
            val packageName = appDef.packageName
            val policy = config.appPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)
            val isExpanded = expandedPackages.contains(packageName)

            // 1. Icon & Names via Smart Hybrid AppIconResolver
            AppIconResolver.loadIcon(
                imageView = binding.ivAppIcon,
                packageName = packageName,
                appName = context.getString(appDef.appNameResId),
                isLocalMode = true
            )
            binding.tvAppName.setText(appDef.appNameResId)

            val enabledCount = policy.enabledPageIds.size
            val totalCount = appDef.pages.size
            binding.tvAppSubtitle.text = if (enabledCount > 0) {
                context.getString(R.string.page_shield_app_pages_count, enabledCount, totalCount)
            } else {
                context.getString(R.string.page_shield_app_pages_none)
            }

            // 2. Expand/Collapse State
            binding.layoutAppDetails.isVisible = isExpanded
            binding.ivExpandChevron.rotation = if (isExpanded) 180f else 0f
            binding.layoutAppHeader.setOnClickListener {
                onToggleExpand(packageName)
            }

            // 3. Dynamic Pages Container
            binding.layoutPagesContainer.removeAllViews()
            val inflater = LayoutInflater.from(context)
            for (pageDef in appDef.pages) {
                val pageBinding = ItemPageShieldPageBinding.inflate(inflater, binding.layoutPagesContainer, false)
                pageBinding.cbPage.setText(pageDef.titleResId)
                pageBinding.tvPageDescription.setText(pageDef.descriptionResId)

                val isChecked = policy.enabledPageIds.contains(pageDef.pageId)
                pageBinding.cbPage.isChecked = isChecked
                pageBinding.cbPage.setOnCheckedChangeListener { _, checked ->
                    if (!isBinding) {
                        onPageToggled(packageName, pageDef.pageId, checked)
                    }
                }
                binding.layoutPagesContainer.addView(pageBinding.root)
            }

            // 4. Reaction RadioGroup
            binding.rgReaction.setOnCheckedChangeListener(null)
            when (policy.reaction) {
                PageShieldReaction.EXIT_ONLY -> {
                    binding.rgReaction.check(R.id.rbExitOnly)
                    binding.layoutFreezeDuration.isVisible = false
                }
                PageShieldReaction.EXIT_AND_FREEZE -> {
                    binding.rgReaction.check(R.id.rbExitAndFreeze)
                    binding.layoutFreezeDuration.isVisible = true
                }
            }
            binding.rgReaction.setOnCheckedChangeListener { _, checkedId ->
                if (isBinding) return@setOnCheckedChangeListener
                when (checkedId) {
                    R.id.rbExitOnly -> {
                        binding.layoutFreezeDuration.isVisible = false
                        onReactionChanged(packageName, PageShieldReaction.EXIT_ONLY)
                    }
                    R.id.rbExitAndFreeze -> {
                        binding.layoutFreezeDuration.isVisible = true
                        onReactionChanged(packageName, PageShieldReaction.EXIT_AND_FREEZE)
                    }
                }
            }

            // 5. Freeze Duration Input with TextWatcher lifecycle safety
            freezeDurationWatcher?.let { binding.etFreezeDuration.removeTextChangedListener(it) }
            freezeDurationWatcher = null

            val durationText = policy.freezeDurationMinutes.toString()
            if (!binding.etFreezeDuration.isFocused && binding.etFreezeDuration.text?.toString() != durationText) {
                binding.etFreezeDuration.setText(durationText)
            }

            freezeDurationWatcher = binding.etFreezeDuration.doAfterTextChanged { text ->
                if (isBinding) return@doAfterTextChanged
                val str = text?.toString()?.trim().orEmpty()
                if (str.isEmpty()) {
                    binding.etFreezeDuration.error = context.getString(R.string.page_shield_freeze_range_error)
                    return@doAfterTextChanged
                }
                val mins = str.toIntOrNull()
                if (mins == null || mins !in AppPageShieldPolicy.MIN_FREEZE_MINUTES..AppPageShieldPolicy.MAX_FREEZE_MINUTES) {
                    binding.etFreezeDuration.error = context.getString(R.string.page_shield_freeze_range_error)
                } else {
                    binding.etFreezeDuration.error = null
                    onFreezeDurationChanged(packageName, mins)
                }
            }

            isBinding = false
        }
    }
}
