package com.drmohammed.antiporn.ui.waqf

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ItemWaqfChannelBinding
import com.drmohammed.antiporn.waqf.model.WaqfAccentStyle
import com.drmohammed.antiporn.waqf.model.WaqfActionType
import com.drmohammed.antiporn.waqf.model.WaqfChannel
import com.drmohammed.antiporn.waqf.model.WaqfChannelAction
import com.drmohammed.antiporn.waqf.model.WaqfChannelType
import com.google.android.material.color.MaterialColors

/**
 * RecyclerView [ListAdapter] rendering active [WaqfChannel] entries with DayNight-safe
 * semantic accent styling, RTL-safe LTR identifiers, and validated action buttons.
 */
class WaqfChannelsAdapter(
    private val onActionClick: (WaqfChannel, WaqfChannelAction) -> Unit,
    private val onCopyValueClick: (WaqfChannel, String) -> Unit
) : ListAdapter<WaqfChannel, WaqfChannelsAdapter.ChannelViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemWaqfChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding, onActionClick, onCopyValueClick)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChannelViewHolder(
        private val binding: ItemWaqfChannelBinding,
        private val onActionClick: (WaqfChannel, WaqfChannelAction) -> Unit,
        private val onCopyValueClick: (WaqfChannel, String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: WaqfChannel) {
            val ctx = binding.root.context
            val accentColor = resolveAccentColor(channel.accentStyle)
            val softBg = ColorUtils.setAlphaComponent(accentColor, 26)
            val strokeColor = ColorUtils.setAlphaComponent(accentColor, 76)
            val displayBoxBg = ColorUtils.setAlphaComponent(accentColor, 16)
            val density = ctx.resources.displayMetrics.density
            val oneDp = (1f * density).toInt().coerceAtLeast(1)

            binding.cardChannel.strokeColor = strokeColor

            // Icon circle & vector
            binding.frameChannelIcon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(softBg)
                setStroke(oneDp, strokeColor)
            }
            binding.ivChannelIcon.setImageResource(iconForType(channel.type))
            ImageViewCompat.setImageTintList(binding.ivChannelIcon, ColorStateList.valueOf(accentColor))

            // Badge pill
            val badgeText = channel.badge?.takeIf { it.isNotBlank() }
            binding.tvChannelBadge.isVisible = badgeText != null
            if (badgeText != null) {
                binding.tvChannelBadge.text = badgeText
                binding.tvChannelBadge.setTextColor(accentColor)
                binding.tvChannelBadge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 100f * density
                    setColor(softBg)
                    setStroke(oneDp, strokeColor)
                }
            }

            // Title & Subtitle
            binding.tvChannelTitle.text = channel.title
            val subtitleText = channel.subtitle?.takeIf { it.isNotBlank() }
            binding.tvChannelSubtitle.isVisible = subtitleText != null
            if (subtitleText != null) {
                binding.tvChannelSubtitle.text = subtitleText
            }

            // Copyable LTR display value (phone number, handle, IBAN)
            val displayValue = channel.displayValue?.takeIf { it.isNotBlank() }
            binding.layoutDisplayValue.isVisible = displayValue != null
            if (displayValue != null) {
                binding.tvDisplayValue.text = displayValue
                binding.tvDisplayValue.textDirection = View.TEXT_DIRECTION_LTR
                binding.layoutDisplayValue.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(displayBoxBg)
                    setStroke(oneDp, strokeColor)
                }
                binding.layoutDisplayValue.setOnClickListener {
                    onCopyValueClick(channel, displayValue)
                }
            } else {
                binding.layoutDisplayValue.setOnClickListener(null)
            }

            // Instructions
            val instructionsText = channel.instructions?.takeIf { it.isNotBlank() }
            binding.tvChannelInstructions.isVisible = instructionsText != null
            if (instructionsText != null) {
                binding.tvChannelInstructions.text = instructionsText
            }

            // Action Buttons (validated via effectivePrimaryAction / effectiveSecondaryAction)
            val primary = channel.effectivePrimaryAction()
            val secondary = channel.effectiveSecondaryAction()
            binding.layoutChannelActions.isVisible = primary != null || secondary != null

            if (primary != null) {
                binding.btnPrimaryAction.isVisible = true
                binding.btnPrimaryAction.text = primary.label
                binding.btnPrimaryAction.setIconResource(iconForAction(primary.type))
                binding.btnPrimaryAction.backgroundTintList = ColorStateList.valueOf(accentColor)
                val onAccentColor = if (ColorUtils.calculateLuminance(accentColor) > 0.45) {
                    Color.parseColor("#0F172A")
                } else {
                    Color.WHITE
                }
                binding.btnPrimaryAction.setTextColor(onAccentColor)
                binding.btnPrimaryAction.iconTint = ColorStateList.valueOf(onAccentColor)
                binding.btnPrimaryAction.setOnClickListener {
                    onActionClick(channel, primary)
                }
            } else {
                binding.btnPrimaryAction.isVisible = false
                binding.btnPrimaryAction.setOnClickListener(null)
            }

            if (secondary != null) {
                binding.btnSecondaryAction.isVisible = true
                binding.btnSecondaryAction.text = secondary.label
                binding.btnSecondaryAction.setIconResource(iconForAction(secondary.type))
                binding.btnSecondaryAction.setTextColor(accentColor)
                binding.btnSecondaryAction.iconTint = ColorStateList.valueOf(accentColor)
                binding.btnSecondaryAction.strokeColor = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(accentColor, 110)
                )
                val lp = binding.btnSecondaryAction.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    lp.marginStart = if (primary != null) (10f * density).toInt() else 0
                    binding.btnSecondaryAction.layoutParams = lp
                }
                binding.btnSecondaryAction.setOnClickListener {
                    onActionClick(channel, secondary)
                }
            } else {
                binding.btnSecondaryAction.isVisible = false
                binding.btnSecondaryAction.setOnClickListener(null)
            }
        }

        private fun resolveAccentColor(style: WaqfAccentStyle): Int {
            val ctx = binding.root.context
            return when (style) {
                WaqfAccentStyle.EMERALD -> ContextCompat.getColor(ctx, R.color.status_green)
                WaqfAccentStyle.TEAL -> MaterialColors.getColor(
                    binding.root,
                    androidx.appcompat.R.attr.colorPrimary,
                    ContextCompat.getColor(ctx, R.color.md_theme_light_primary)
                )
                WaqfAccentStyle.CRIMSON -> ContextCompat.getColor(ctx, R.color.status_red)
                WaqfAccentStyle.BLUE -> MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSecondary,
                    ContextCompat.getColor(ctx, R.color.md_theme_light_secondary)
                )
                WaqfAccentStyle.AMBER -> ContextCompat.getColor(ctx, R.color.status_orange)
            }
        }

        private fun iconForType(type: WaqfChannelType): Int = when (type) {
            WaqfChannelType.INSTAPAY -> R.drawable.ic_bank
            WaqfChannelType.MOBILE_WALLET -> R.drawable.ic_phone_dial
            WaqfChannelType.WEB_LINK -> R.drawable.ic_globe
            WaqfChannelType.BANK_ACCOUNT -> R.drawable.ic_bank
            WaqfChannelType.COPY_ONLY, WaqfChannelType.OTHER -> R.drawable.ic_heart_waqf
        }

        private fun iconForAction(type: WaqfActionType): Int = when (type) {
            WaqfActionType.OPEN_URL -> R.drawable.ic_open_in_new
            WaqfActionType.COPY_VALUE -> R.drawable.ic_copy
            WaqfActionType.DIAL_USSD -> R.drawable.ic_phone_dial
            WaqfActionType.UNKNOWN -> 0
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WaqfChannel>() {
        override fun areItemsTheSame(oldItem: WaqfChannel, newItem: WaqfChannel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WaqfChannel, newItem: WaqfChannel): Boolean {
            return oldItem == newItem
        }
    }
}
