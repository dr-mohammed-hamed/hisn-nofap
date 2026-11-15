package com.drmohammed.antiporn.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.banking.InstalledBankingApp
import com.drmohammed.antiporn.databinding.ItemBankingAppBinding

/**
 * محول عرض قائمة التطبيقات البنكية والمصرفية المعتمدة (Banking Apps Adapter).
 */
class BankingAppsAdapter(
    private val onLaunchClick: (InstalledBankingApp) -> Unit,
    private val onPinShortcutClick: (InstalledBankingApp) -> Unit
) : ListAdapter<InstalledBankingApp, BankingAppsAdapter.BankingAppViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BankingAppViewHolder {
        val binding = ItemBankingAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BankingAppViewHolder(binding, onLaunchClick, onPinShortcutClick)
    }

    override fun onBindViewHolder(holder: BankingAppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class BankingAppViewHolder(
        private val binding: ItemBankingAppBinding,
        private val onLaunchClick: (InstalledBankingApp) -> Unit,
        private val onPinShortcutClick: (InstalledBankingApp) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: InstalledBankingApp) {
            binding.ivBankingAppIcon.setImageDrawable(app.icon)
            binding.tvBankingAppName.text = app.appName
            binding.tvBankingPackage.text = app.packageName

            binding.btnSafeLaunch.setOnClickListener {
                onLaunchClick(app)
            }

            binding.btnPinShortcut.setOnClickListener {
                onPinShortcutClick(app)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<InstalledBankingApp>() {
        override fun areItemsTheSame(oldItem: InstalledBankingApp, newItem: InstalledBankingApp): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: InstalledBankingApp, newItem: InstalledBankingApp): Boolean {
            return oldItem.packageName == newItem.packageName && oldItem.appName == newItem.appName
        }
    }
}
