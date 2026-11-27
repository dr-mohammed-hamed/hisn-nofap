package com.drmohammed.antiporn.fortress.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ItemFortressCardBinding
import com.drmohammed.antiporn.fortress.model.FortressProfile

/**
 * RecyclerView adapter rendering Emergency Fortress first followed by sorted custom Fortresses.
 */
internal class FortressesListAdapter(
    private val isActiveChecker: (FortressProfile) -> Boolean,
    private val onEditClicked: (FortressProfile) -> Unit,
    private val onDeleteClicked: (FortressProfile) -> Unit
) : RecyclerView.Adapter<FortressesListAdapter.ViewHolder>() {

    private val items = mutableListOf<FortressProfile>()

    fun submitList(newItems: List<FortressProfile>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos].id == newItems[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun refreshActiveStates() {
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFortressCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = items[position]
        val b = holder.binding
        val ctx = b.root.context
        val isActive = isActiveChecker(profile)

        b.tvFortressName.text = profile.resolveDisplayName(ctx)
        b.tvBadgeEmergency.isVisible = profile.isEmergency
        b.tvBadgeActive.isVisible = isActive

        b.tvFortressDuration.text = ctx.getString(
            R.string.fortress_duration_minutes_format,
            profile.durationMinutes
        )
        b.tvFortressInternetStatus.setText(
            if (profile.isInternetCutEnabled) {
                R.string.fortress_internet_cut_on
            } else {
                R.string.fortress_internet_cut_off
            }
        )

        val allowedCount = profile.allowedPackages.size
        val netCount = profile.vpnBypassPackages.count { profile.allowedPackages.contains(it) }
        b.tvFortressAllowedApps.text = when {
            allowedCount == 0 -> ctx.getString(R.string.fortress_allowed_apps_summary_none)
            !profile.isEmergency && profile.isInternetCutEnabled && netCount > 0 ->
                ctx.getString(R.string.fortress_allowed_apps_summary_with_net, allowedCount, netCount)
            else -> ctx.getString(R.string.fortress_allowed_apps_summary_run_only, allowedCount)
        }

        b.btnDeleteFortress.visibility = if (profile.isEmergency) View.GONE else View.VISIBLE

        val actionAlpha = if (isActive) 0.45f else 1.0f
        b.btnEditFortress.alpha = actionAlpha
        b.btnDeleteFortress.alpha = actionAlpha

        b.cardFortressItem.setOnClickListener { onEditClicked(profile) }
        b.btnEditFortress.setOnClickListener { onEditClicked(profile) }
        b.btnDeleteFortress.setOnClickListener { onDeleteClicked(profile) }
    }

    class ViewHolder(val binding: ItemFortressCardBinding) : RecyclerView.ViewHolder(binding.root)
}
