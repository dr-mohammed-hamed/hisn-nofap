package com.drmohammed.antiporn.ui.shields

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.BottomSheetActiveShieldsBinding
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.drmohammed.antiporn.ui.common.showSafely
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Material 3 Bottom Sheet modal presenting currently active and verified security shields.
 */
class ActiveShieldsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetActiveShieldsBinding? = null
    private val binding: BottomSheetActiveShieldsBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetActiveShieldsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)

        binding.layoutShieldsContainer.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.GONE
        binding.btnCloseBottomSheet.setOnClickListener { dismiss() }

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val activeShields = withContext(Dispatchers.IO) {
                ActiveShieldsEvaluator.evaluateActiveShields(appContext)
            }
            if (!isAdded || _binding == null) return@launch

            binding.tvBottomSheetCount.text = getString(R.string.bottom_sheet_shields_count, activeShields.size)

            if (activeShields.isEmpty()) {
                binding.layoutShieldsContainer.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
            } else {
                binding.layoutShieldsContainer.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
                binding.layoutShieldsContainer.removeAllViews()

                val inflater = LayoutInflater.from(requireContext())
                val container: LinearLayout = binding.layoutShieldsContainer
                for (shield in activeShields) {
                    val itemView = inflater.inflate(R.layout.item_active_shield, container, false)
                    val ivIcon = itemView.findViewById<ImageView>(R.id.ivShieldIcon)
                    val tvTitle = itemView.findViewById<TextView>(R.id.tvShieldTitle)
                    val tvDesc = itemView.findViewById<TextView>(R.id.tvShieldDescription)
                    val tvBadge = itemView.findViewById<TextView>(R.id.tvShieldBadge)

                    ivIcon.setImageResource(shield.iconRes)
                    tvTitle.text = shield.title
                    tvDesc.text = shield.description
                    tvBadge.text = shield.badgeText

                    container.addView(itemView)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ActiveShieldsSheet"

        fun show(fragmentManager: FragmentManager) {
            fragmentManager.showSafely(TAG) { ActiveShieldsBottomSheet() }
        }

        fun show(context: Context) {
            val activity = findFragmentActivity(context)
            if (activity == null || activity.isFinishing || activity.isDestroyed) return
            show(activity.supportFragmentManager)
        }

        fun dismiss(fragmentManager: FragmentManager? = null) {
            (fragmentManager?.findFragmentByTag(TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        }

        private fun findFragmentActivity(context: Context): FragmentActivity? {
            var ctx: Context? = context
            while (ctx is ContextWrapper) {
                if (ctx is FragmentActivity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }
}
