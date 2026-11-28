package com.drmohammed.antiporn.ui.control.web

import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer
import com.drmohammed.antiporn.databinding.BottomSheetWebsiteRulesBinding
import com.drmohammed.antiporn.ui.common.TextInputDialogFragment
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.drmohammed.antiporn.ui.common.showSafely
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet for managing blocked and allowed website lists with search and mutual exclusivity.
 */
class WebsiteRulesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWebsiteRulesBinding? = null
    private val binding: BottomSheetWebsiteRulesBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private val blockedUrls = linkedSetOf<String>()
    private val allowedUrls = linkedSetOf<String>()
    private var isAllowedTab: Boolean = false
    private var searchQuery: String = ""

    private lateinit var adapter: WebsiteRulesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWebsiteRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureLandscapeFriendlySheet(enableImeResize = true)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyBottomSheetInsets(view, includeIme = true) { imeVisible ->
            if (_binding != null && isLandscape) {
                binding.btnDoneWebsites.isVisible = !imeVisible
            }
        }

        restoreState(savedInstanceState)

        adapter = WebsiteRulesAdapter { item ->
            val targetSet = if (item.isAllowed) allowedUrls else blockedUrls
            targetSet.remove(item.domain)
            refreshUi()
        }

        binding.rvWebsites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWebsites.adapter = adapter

        binding.toggleGroupWebsiteTab.check(
            if (isAllowedTab) R.id.btnTabAllowed else R.id.btnTabBlocked
        )
        if (searchQuery.isNotEmpty()) {
            binding.etSearchWebsite.setText(searchQuery)
        }

        setupListeners()
        childFragmentManager.setFragmentResultListener(REQ_ADD_WEBSITE, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(TextInputDialogFragment.RESULT_CONFIRMED, false)) {
                val raw = bundle.getString(TextInputDialogFragment.RESULT_TEXT).orEmpty()
                handleAddWebsite(raw)
            }
        }
        refreshUi()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        blockedUrls.clear()
        allowedUrls.clear()
        if (savedInstanceState != null) {
            blockedUrls.addAll(savedInstanceState.getStringArrayList(STATE_BLOCKED_URLS).orEmpty())
            allowedUrls.addAll(savedInstanceState.getStringArrayList(STATE_ALLOWED_URLS).orEmpty())
            isAllowedTab = savedInstanceState.getBoolean(STATE_IS_ALLOWED_TAB, false)
            searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY).orEmpty()
        } else {
            blockedUrls.addAll(arguments?.getStringArrayList(ARG_BLOCKED_URLS).orEmpty())
            allowedUrls.addAll(arguments?.getStringArrayList(ARG_ALLOWED_URLS).orEmpty())
            isAllowedTab = arguments?.getBoolean(ARG_INITIAL_TAB_ALLOWED, false) ?: false
            searchQuery = ""
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_BLOCKED_URLS, ArrayList(blockedUrls))
        outState.putStringArrayList(STATE_ALLOWED_URLS, ArrayList(allowedUrls))
        outState.putBoolean(STATE_IS_ALLOWED_TAB, isAllowedTab)
        outState.putString(STATE_SEARCH_QUERY, searchQuery)
    }

    private fun setupListeners() {
        binding.toggleGroupWebsiteTab.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isAllowedTab = checkedId == R.id.btnTabAllowed
            refreshUi()
        }

        binding.etSearchWebsite.doAfterTextChanged { editable ->
            searchQuery = editable?.toString().orEmpty()
            refreshList()
        }

        binding.btnAddWebsite.setOnClickListener {
            showAddWebsiteDialog()
        }

        binding.btnDoneWebsites.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_BLOCKED_URLS to ArrayList(blockedUrls),
                    RESULT_ALLOWED_URLS to ArrayList(allowedUrls)
                )
            )
            dismiss()
        }
    }

    private fun refreshUi() {
        binding.btnTabBlocked.text = getString(R.string.tab_blocked_websites, blockedUrls.size)
        binding.btnTabAllowed.text = getString(R.string.tab_allowed_websites, allowedUrls.size)
        refreshList()
    }

    private fun refreshList() {
        val activeSet = if (isAllowedTab) allowedUrls else blockedUrls
        val q = searchQuery.trim().lowercase()
        val items = if (q.isEmpty()) {
            activeSet.sorted()
        } else {
            activeSet.filter { it.lowercase().contains(q) }.sorted()
        }

        adapter.submitList(items.map { WebsiteRuleItem(it, isAllowedTab) })

        if (items.isEmpty()) {
            binding.tvEmptyWebsites.text = if (isAllowedTab) {
                getString(R.string.empty_allowed_urls)
            } else {
                getString(R.string.empty_blocked_urls)
            }
            binding.tvEmptyWebsites.visibility = View.VISIBLE
            binding.rvWebsites.visibility = View.GONE
        } else {
            binding.tvEmptyWebsites.visibility = View.GONE
            binding.rvWebsites.visibility = View.VISIBLE
        }
    }

    private fun showAddWebsiteDialog() {
        val titleRes = if (isAllowedTab) R.string.dialog_add_allowed_title else R.string.dialog_add_url_title
        val validationMode = if (isAllowedTab) {
            TextInputDialogFragment.VALIDATION_WEBSITE_ALLOW
        } else {
            TextInputDialogFragment.VALIDATION_WEBSITE_BLOCK
        }
        childFragmentManager.showSafely(TAG_ADD_WEBSITE) {
            TextInputDialogFragment.newInstance(
                requestKey = REQ_ADD_WEBSITE,
                titleRes = titleRes,
                hintRes = R.string.dialog_add_url_hint,
                positiveRes = R.string.browser_filter_btn_add,
                negativeRes = android.R.string.cancel,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                singleLine = true,
                ltrTextDirection = true,
                validationMode = validationMode
            )
        }
    }

    private fun handleAddWebsite(raw: String) {
        val ctx = context ?: return
        val sanitized = if (isAllowedTab) {
            BrowserUrlSanitizer.sanitizeForAllowlist(raw)
        } else {
            BrowserUrlSanitizer.sanitize(raw)
        }

        if (sanitized == null) {
            Toast.makeText(ctx, R.string.browser_filter_error_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        val targetSet = if (isAllowedTab) allowedUrls else blockedUrls
        if (targetSet.contains(sanitized)) {
            Toast.makeText(ctx, R.string.browser_filter_error_duplicate_url, Toast.LENGTH_SHORT).show()
            return
        }

        targetSet.add(sanitized)
        // Mutual exclusivity between blocked and allowed sets prevents DNS vs Browser rule conflicts
        if (isAllowedTab) {
            blockedUrls.remove(sanitized)
        } else {
            allowedUrls.remove(sanitized)
        }
        refreshUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "WebsiteRulesBottomSheet"
        const val REQUEST_KEY = "website_rules_request"
        const val RESULT_BLOCKED_URLS = "result_blocked_urls"
        const val RESULT_ALLOWED_URLS = "result_allowed_urls"
        private const val REQ_ADD_WEBSITE = "req_add_website"
        private const val TAG_ADD_WEBSITE = "tag_add_website"

        private const val ARG_BLOCKED_URLS = "arg_blocked_urls"
        private const val ARG_ALLOWED_URLS = "arg_allowed_urls"
        private const val ARG_INITIAL_TAB_ALLOWED = "arg_initial_tab_allowed"

        private const val STATE_BLOCKED_URLS = "state_blocked_urls"
        private const val STATE_ALLOWED_URLS = "state_allowed_urls"
        private const val STATE_IS_ALLOWED_TAB = "state_is_allowed_tab"
        private const val STATE_SEARCH_QUERY = "state_search_query"

        fun newInstance(
            blockedUrls: Set<String>,
            allowedUrls: Set<String>,
            initialTabIsAllowed: Boolean = false
        ): WebsiteRulesBottomSheet {
            return WebsiteRulesBottomSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_BLOCKED_URLS, ArrayList(blockedUrls))
                    putStringArrayList(ARG_ALLOWED_URLS, ArrayList(allowedUrls))
                    putBoolean(ARG_INITIAL_TAB_ALLOWED, initialTabIsAllowed)
                }
            }
        }
    }
}
