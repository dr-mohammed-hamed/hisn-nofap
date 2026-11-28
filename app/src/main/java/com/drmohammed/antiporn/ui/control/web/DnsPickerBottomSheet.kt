package com.drmohammed.antiporn.ui.control.web

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.databinding.BottomSheetDnsPickerBinding
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * BottomSheet for selecting a verified encrypted DNS preset or validating a custom DoT host.
 */
class DnsPickerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDnsPickerBinding? = null
    private val binding: BottomSheetDnsPickerBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private enum class DnsOption {
        CLOUDFLARE,
        ADGUARD,
        CONTROLD,
        CUSTOM
    }

    private val validationViewModel: DnsPickerValidationViewModel by viewModel()
    private var selectedOption: DnsOption = DnsOption.CLOUDFLARE
    private var isValidating: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDnsPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureLandscapeFriendlySheet(enableImeResize = true)
        applyBottomSheetInsets(view, includeIme = true)

        if (savedInstanceState != null) {
            val savedOrdinal = savedInstanceState.getInt(STATE_SELECTED_OPTION, DnsOption.CLOUDFLARE.ordinal)
            selectedOption = DnsOption.entries.getOrElse(savedOrdinal) { DnsOption.CLOUDFLARE }
            binding.etCustomDnsHost.setText(savedInstanceState.getString(STATE_CUSTOM_HOST).orEmpty())
        } else {
            val initialHost = arguments?.getString(ARG_CURRENT_DNS_HOST)?.trim().orEmpty()
            selectedOption = resolveOptionForHost(initialHost)
            if (selectedOption == DnsOption.CUSTOM) {
                binding.etCustomDnsHost.setText(initialHost)
            }
        }

        setupListeners()
        observeValidationViewModel()
        setValidatingState(validationViewModel.isValidating.value)
        renderSelectionState()
    }

    private fun observeValidationViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    validationViewModel.isValidating.collect { validating ->
                        if (_binding != null) {
                            setValidatingState(validating)
                        }
                    }
                }
                launch {
                    validationViewModel.events.collect { event ->
                        if (_binding == null || !isAdded) return@collect
                        when (event) {
                            is DnsValidationEvent.Completed -> handleValidationResult(event.host, event.result)
                            is DnsValidationEvent.ProbeException -> {
                                binding.tilCustomDnsHost.error = getString(R.string.dns_probe_error_unreachable)
                                binding.etCustomDnsHost.requestFocus()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleValidationResult(
        customHost: String,
        result: DnsFilteringValidator.ValidationResult
    ) {
        when (result) {
            is DnsFilteringValidator.ValidationResult.Valid -> {
                deliverResultAndDismiss(customHost)
            }
            is DnsFilteringValidator.ValidationResult.UnfilteredPorn -> {
                binding.tilCustomDnsHost.error = getString(
                    R.string.dns_probe_error_not_blocking_detail,
                    result.domain,
                    result.resolvedIp
                )
                binding.etCustomDnsHost.requestFocus()
            }
            is DnsFilteringValidator.ValidationResult.Unreachable -> {
                binding.tilCustomDnsHost.error = getString(
                    R.string.dns_probe_error_unreachable_detail,
                    result.reason
                )
                binding.etCustomDnsHost.requestFocus()
            }
            is DnsFilteringValidator.ValidationResult.ResolverBroken -> {
                binding.tilCustomDnsHost.error = getString(
                    R.string.dns_probe_error_broken_detail,
                    result.reason
                )
                binding.etCustomDnsHost.requestFocus()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_OPTION, selectedOption.ordinal)
        if (_binding != null) {
            outState.putString(STATE_CUSTOM_HOST, binding.etCustomDnsHost.text?.toString().orEmpty())
        }
    }

    private fun resolveOptionForHost(host: String): DnsOption {
        val clean = host.trim().lowercase()
        return when {
            clean.isEmpty() || clean == DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase() -> DnsOption.CLOUDFLARE
            clean == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase() -> DnsOption.ADGUARD
            clean == DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST.lowercase() ||
                clean == DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST.lowercase() -> DnsOption.CONTROLD
            else -> DnsOption.CUSTOM
        }
    }

    private fun setupListeners() {
        binding.cardDnsCloudflare.setOnClickListener { selectOption(DnsOption.CLOUDFLARE) }
        binding.cardDnsAdguard.setOnClickListener { selectOption(DnsOption.ADGUARD) }
        binding.cardDnsControlD.setOnClickListener { selectOption(DnsOption.CONTROLD) }
        binding.cardDnsCustom.setOnClickListener { selectOption(DnsOption.CUSTOM) }

        binding.etCustomDnsHost.doAfterTextChanged {
            binding.tilCustomDnsHost.error = null
        }

        binding.btnConfirmDns.setOnClickListener {
            onConfirmClicked()
        }
    }

    private fun selectOption(option: DnsOption) {
        if (isValidating) return
        selectedOption = option
        if (option != DnsOption.CUSTOM) {
            binding.tilCustomDnsHost.error = null
        }
        renderSelectionState()
    }

    private fun renderSelectionState() {
        binding.rbDnsCloudflare.isChecked = selectedOption == DnsOption.CLOUDFLARE
        binding.rbDnsAdguard.isChecked = selectedOption == DnsOption.ADGUARD
        binding.rbDnsControlD.isChecked = selectedOption == DnsOption.CONTROLD
        binding.rbDnsCustom.isChecked = selectedOption == DnsOption.CUSTOM

        val primaryColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
        val outlineColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        val density = resources.displayMetrics.density
        val selectedStroke = (2 * density).toInt()
        val defaultStroke = (1 * density).toInt()

        val cards = listOf(
            binding.cardDnsCloudflare to (selectedOption == DnsOption.CLOUDFLARE),
            binding.cardDnsAdguard to (selectedOption == DnsOption.ADGUARD),
            binding.cardDnsControlD to (selectedOption == DnsOption.CONTROLD),
            binding.cardDnsCustom to (selectedOption == DnsOption.CUSTOM)
        )
        for ((card, isSelected) in cards) {
            card.strokeColor = if (isSelected) primaryColor else outlineColor
            card.strokeWidth = if (isSelected) selectedStroke else defaultStroke
        }

        binding.tilCustomDnsHost.visibility = if (selectedOption == DnsOption.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun onConfirmClicked() {
        if (isValidating) return

        val presetHost = when (selectedOption) {
            DnsOption.CLOUDFLARE -> DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
            DnsOption.ADGUARD -> DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST
            DnsOption.CONTROLD -> DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST
            DnsOption.CUSTOM -> null
        }

        if (presetHost != null) {
            deliverResultAndDismiss(presetHost)
            return
        }

        val customHost = binding.etCustomDnsHost.text?.toString()?.trim().orEmpty()
        if (!customHost.matches(WebFilterViewModel.DNS_HOST_REGEX)) {
            binding.tilCustomDnsHost.error = getString(R.string.error_invalid_dns_host)
            binding.etCustomDnsHost.requestFocus()
            return
        }

        if (DnsFilteringValidator.isKnownFamilyPreset(customHost)) {
            deliverResultAndDismiss(customHost)
            return
        }

        validationViewModel.validateCustomHost(customHost)
    }

    private fun setValidatingState(validating: Boolean) {
        isValidating = validating
        binding.layoutValidationProgress.visibility = if (validating) View.VISIBLE else View.GONE
        binding.btnConfirmDns.isEnabled = !validating
        binding.etCustomDnsHost.isEnabled = !validating
        binding.cardDnsCloudflare.isEnabled = !validating
        binding.cardDnsAdguard.isEnabled = !validating
        binding.cardDnsControlD.isEnabled = !validating
        binding.cardDnsCustom.isEnabled = !validating
        isCancelable = !validating
    }

    private fun deliverResultAndDismiss(host: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(
                RESULT_DNS_HOST to host,
                RESULT_VALIDATED to true
            )
        )
        if (isStateSaved) dismissAllowingStateLoss() else dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DnsPickerBottomSheet"
        const val REQUEST_KEY = "dns_picker_request"
        const val RESULT_DNS_HOST = "result_dns_host"
        const val RESULT_VALIDATED = "result_validated"

        private const val ARG_CURRENT_DNS_HOST = "arg_current_dns_host"
        private const val STATE_SELECTED_OPTION = "state_selected_option"
        private const val STATE_CUSTOM_HOST = "state_custom_host"

        fun newInstance(currentDnsHost: String): DnsPickerBottomSheet {
            return DnsPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_DNS_HOST, currentDnsHost)
                }
            }
        }
    }
}
