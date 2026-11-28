package com.drmohammed.antiporn.ui.accountability

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * RecyclerView adapter displaying installed applications with per-app protection controls
 * for [RemoteAppManagerActivity].
 */
internal class RemoteAppsAdapter(
    private val isLocalMode: Boolean,
    private val onFreezeToggled: (String, Int, Boolean) -> Unit,
    private val onCurfewWhitelistToggled: (String, Int, Boolean) -> Unit,
    private val onCurfewVpnBypassToggled: (String, Int, Boolean) -> Unit,
    private val onProtectToggled: (String, Int, Boolean) -> Unit,
    private val onAiScanToggled: (String, Int, Boolean) -> Unit,
    private val onQuickUnsuspendClicked: (InstalledAppDto) -> Unit
) : RecyclerView.Adapter<RemoteAppsAdapter.AppViewHolder>() {

    private var apps: List<InstalledAppDto> = emptyList()
    private var customBlacklist: Set<String> = emptySet()
    private var customCurfewWhitelist: Set<String> = emptySet()
    private var customAiScanPackages: Set<String> = emptySet()
    private var excludedAiScanPackages: Set<String> = emptySet()
    private var curfewVpnBypass: Set<String> = emptySet()
    private var protectedApps: Set<String> = emptySet()
    private var initialCurfewWhitelist: Set<String> = emptySet()
    private var initialCurfewVpnBypass: Set<String> = emptySet()
    private var isScreenAiEnabledGlobally: Boolean = false

    fun submitData(
        apps: List<InstalledAppDto>,
        customBlacklist: Set<String>,
        customCurfewWhitelist: Set<String>,
        customAiScanPackages: Set<String>,
        excludedAiScanPackages: Set<String>,
        curfewVpnBypass: Set<String>,
        protectedApps: Set<String>,
        initialCurfewWhitelist: Set<String>,
        initialCurfewVpnBypass: Set<String>,
        isScreenAiEnabledGlobally: Boolean,
        changedPosition: Int? = null
    ) {
        val listChanged = this.apps !== apps && this.apps != apps
        this.apps = apps
        this.customBlacklist = customBlacklist
        this.customCurfewWhitelist = customCurfewWhitelist
        this.customAiScanPackages = customAiScanPackages
        this.excludedAiScanPackages = excludedAiScanPackages
        this.curfewVpnBypass = curfewVpnBypass
        this.protectedApps = protectedApps
        this.initialCurfewWhitelist = initialCurfewWhitelist
        this.initialCurfewVpnBypass = initialCurfewVpnBypass
        this.isScreenAiEnabledGlobally = isScreenAiEnabledGlobally

        if (changedPosition != null && !listChanged && changedPosition in apps.indices) {
            notifyItemChanged(changedPosition)
        } else {
            notifyDataSetChanged()
        }
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
        val tvHighRiskBadge: TextView = view.findViewById(R.id.tvHighRiskBadge)
        val layoutPhysicalSuspensionAlert: View = view.findViewById(R.id.layoutPhysicalSuspensionAlert)
        val btnQuickUnsuspend: MaterialButton = view.findViewById(R.id.btnQuickUnsuspend)
        val switchFreeze247: MaterialSwitch = view.findViewById(R.id.switchFreeze247)
        val switchAllowInCurfew: MaterialSwitch = view.findViewById(R.id.switchAllowInCurfew)
        val tvCurfewDisabledHint: TextView = view.findViewById(R.id.tvCurfewDisabledHint)
        val switchAllowCurfewInternet: MaterialSwitch = view.findViewById(R.id.switchAllowCurfewInternet)
        val tvCurfewInternetDisabledHint: TextView = view.findViewById(R.id.tvCurfewInternetDisabledHint)
        val switchProtect: MaterialSwitch = view.findViewById(R.id.switchProtect)
        val switchAiScanTarget: MaterialSwitch = view.findViewById(R.id.switchAiScanTarget)
        val tvAiScanDisabledHint: TextView = view.findViewById(R.id.tvAiScanDisabledHint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_remote_app_card,
            parent,
            false
        )
        return AppViewHolder(view)
    }

    override fun getItemCount(): Int = apps.size

    override fun onViewRecycled(holder: AppViewHolder) {
        super.onViewRecycled(holder)
        AppIconResolver.cancelLoad(holder.ivAppIcon)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        val pkg = app.packageName
        val displayName = app.appName.ifBlank { pkg }

        holder.tvAppName.text = displayName
        holder.tvPackageName.text = pkg

        val isHighRisk = AppPolicyRegistry.isHighRisk(pkg)
        holder.tvHighRiskBadge.visibility = if (isHighRisk) View.VISIBLE else View.GONE

        AppIconResolver.loadIcon(
            imageView = holder.ivAppIcon,
            packageName = pkg,
            appName = displayName,
            isLocalMode = isLocalMode
        )

        holder.switchFreeze247.setOnCheckedChangeListener(null)
        holder.switchAllowInCurfew.setOnCheckedChangeListener(null)
        holder.switchAllowCurfewInternet.setOnCheckedChangeListener(null)
        holder.switchProtect.setOnCheckedChangeListener(null)
        holder.switchAiScanTarget.setOnCheckedChangeListener(null)

        val isFrozen = customBlacklist.contains(pkg)
        holder.switchFreeze247.isChecked = isFrozen

        val canQuickUnsuspend = app.isSuspended && !isFrozen
        if (canQuickUnsuspend) {
            holder.layoutPhysicalSuspensionAlert.visibility = View.VISIBLE
            holder.btnQuickUnsuspend.visibility = View.VISIBLE
            holder.btnQuickUnsuspend.setOnClickListener { onQuickUnsuspendClicked(app) }
        } else {
            holder.layoutPhysicalSuspensionAlert.visibility = View.GONE
            holder.btnQuickUnsuspend.setOnClickListener(null)
        }

        val isCurfewAllowed = !isFrozen && customCurfewWhitelist.contains(pkg)
        if (isFrozen) {
            holder.switchAllowInCurfew.isChecked = false
            holder.switchAllowInCurfew.isEnabled = false
            holder.switchAllowInCurfew.alpha = 0.4f
            holder.tvCurfewDisabledHint.visibility = View.VISIBLE
        } else {
            holder.switchAllowInCurfew.isEnabled = true
            holder.switchAllowInCurfew.alpha = 1.0f
            holder.tvCurfewDisabledHint.visibility = View.GONE
            holder.switchAllowInCurfew.isChecked = customCurfewWhitelist.contains(pkg)
        }

        if (!isCurfewAllowed) {
            holder.switchAllowCurfewInternet.isChecked = false
            holder.switchAllowCurfewInternet.isEnabled = false
            holder.switchAllowCurfewInternet.alpha = 0.4f
            holder.tvCurfewInternetDisabledHint.visibility = View.VISIBLE
        } else {
            holder.switchAllowCurfewInternet.isEnabled = true
            holder.switchAllowCurfewInternet.alpha = 1.0f
            holder.tvCurfewInternetDisabledHint.visibility = View.GONE
            holder.switchAllowCurfewInternet.isChecked = curfewVpnBypass.contains(pkg)
        }

        holder.switchProtect.isChecked = protectedApps.contains(pkg)

        val isAiScanTarget = (customAiScanPackages.contains(pkg) || AiExemptionRegistry.isTarget(pkg)) &&
            !excludedAiScanPackages.contains(pkg)
        if (!isScreenAiEnabledGlobally) {
            holder.switchAiScanTarget.isChecked = false
            holder.switchAiScanTarget.isEnabled = false
            holder.switchAiScanTarget.alpha = 0.4f
            holder.tvAiScanDisabledHint.visibility = View.VISIBLE
        } else {
            holder.switchAiScanTarget.isEnabled = true
            holder.switchAiScanTarget.alpha = 1.0f
            holder.tvAiScanDisabledHint.visibility = View.GONE
            holder.switchAiScanTarget.isChecked = isAiScanTarget
        }

        holder.switchFreeze247.setOnCheckedChangeListener { _, isChecked ->
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onFreezeToggled(pkg, currentPos, isChecked)
            }
        }
        holder.switchAllowInCurfew.setOnCheckedChangeListener { _, isChecked ->
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onCurfewWhitelistToggled(pkg, currentPos, isChecked)
            }
        }
        holder.switchAllowCurfewInternet.setOnCheckedChangeListener { _, isChecked ->
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onCurfewVpnBypassToggled(pkg, currentPos, isChecked)
            }
        }
        holder.switchProtect.setOnCheckedChangeListener { _, isChecked ->
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onProtectToggled(pkg, currentPos, isChecked)
            }
        }
        holder.switchAiScanTarget.setOnCheckedChangeListener { _, isChecked ->
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onAiScanToggled(pkg, currentPos, isChecked)
            }
        }
    }
}
