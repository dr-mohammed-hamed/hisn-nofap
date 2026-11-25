package com.drmohammed.antiporn.ui.control.web

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.databinding.ItemFilteredUrlBinding

data class WebsiteRuleItem(
    val domain: String,
    val isAllowed: Boolean
)

/**
 * RecyclerView adapter for displaying and deleting website rules inside WebsiteRulesBottomSheet.
 */
class WebsiteRulesAdapter(
    private val onDeleteClick: (WebsiteRuleItem) -> Unit
) : ListAdapter<WebsiteRuleItem, WebsiteRulesAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(
        private val binding: ItemFilteredUrlBinding,
        private val onDeleteClick: (WebsiteRuleItem) -> Unit,
        private val itemAt: (Int) -> WebsiteRuleItem?
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnDeleteUrl.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    itemAt(pos)?.let(onDeleteClick)
                }
            }
        }

        fun bind(item: WebsiteRuleItem) {
            binding.tvUrlDomain.text = item.domain
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFilteredUrlBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onDeleteClick) { pos ->
            if (pos in 0 until itemCount) getItem(pos) else null
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private object DiffCallback : DiffUtil.ItemCallback<WebsiteRuleItem>() {
        override fun areItemsTheSame(oldItem: WebsiteRuleItem, newItem: WebsiteRuleItem): Boolean =
            oldItem.domain == newItem.domain && oldItem.isAllowed == newItem.isAllowed

        override fun areContentsTheSame(oldItem: WebsiteRuleItem, newItem: WebsiteRuleItem): Boolean =
            oldItem == newItem
    }
}
