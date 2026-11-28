package com.drmohammed.antiporn.ui.control.curfew

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.databinding.ItemCurfewAppPickerBinding
import com.drmohammed.antiporn.ui.accountability.AppIconResolver

/**
 * RecyclerView adapter for [CurfewAppPickerBottomSheet] allowing per-app Run and Net selection.
 */
class CurfewAppPickerAdapter(
    private val isLocalMode: Boolean,
    private val onRunToggled: (CurfewAppPickerBottomSheet.CurfewAppItem, Boolean) -> Unit,
    private val onNetToggled: (CurfewAppPickerBottomSheet.CurfewAppItem, Boolean) -> Unit
) : RecyclerView.Adapter<CurfewAppPickerAdapter.ViewHolder>() {

    private val items: MutableList<CurfewAppPickerBottomSheet.CurfewAppItem> = mutableListOf()

    fun submitItems(newItems: List<CurfewAppPickerBottomSheet.CurfewAppItem>) {
        val oldItems = items.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos].packageName == newItems[newPos].packageName

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    class ViewHolder(val binding: ItemCurfewAppPickerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCurfewAppPickerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        AppIconResolver.cancelLoad(holder.binding.ivAppIcon)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.tvAppName.text = item.appName
        binding.tvPackageName.text = item.packageName

        AppIconResolver.loadIcon(
            imageView = binding.ivAppIcon,
            packageName = item.packageName,
            appName = item.appName,
            isLocalMode = isLocalMode
        )

        binding.cbRun.setOnCheckedChangeListener(null)
        binding.cbNet.setOnCheckedChangeListener(null)

        binding.cbRun.isChecked = item.isRunAllowed
        binding.cbNet.isChecked = item.isNetAllowed

        binding.cbNet.isEnabled = item.isRunAllowed
        binding.cbNet.alpha = if (item.isRunAllowed) 1.0f else 0.45f

        binding.cbRun.setOnCheckedChangeListener { _, isChecked ->
            onRunToggled(item, isChecked)
        }

        binding.cbNet.setOnCheckedChangeListener { _, isChecked ->
            onNetToggled(item, isChecked)
        }

        binding.root.setOnClickListener {
            val newChecked = !binding.cbRun.isChecked
            binding.cbRun.isChecked = newChecked
        }
    }
}
