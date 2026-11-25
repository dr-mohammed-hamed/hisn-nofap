package com.drmohammed.antiporn.ui.control.web

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer
import com.drmohammed.antiporn.databinding.BottomSheetWebsiteRulesBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
    private var activeAddDialog: AlertDialog? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener {
            (dialog as? BottomSheetDialog)?.behavior?.apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

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
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val horizontalMargin = (20 * density).toInt()
        val topMargin = (8 * density).toInt()

        val textInputLayout = TextInputLayout(ctx).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.dialog_add_url_hint)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
            textDirection = View.TEXT_DIRECTION_LTR
        }
        textInputLayout.addView(editText)

        val container = FrameLayout(ctx).apply {
            setPadding(horizontalMargin, topMargin, horizontalMargin, 0)
            addView(textInputLayout)
        }

        val titleRes = if (isAllowedTab) R.string.dialog_add_allowed_title else R.string.dialog_add_url_title
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.browser_filter_btn_add) { _, _ ->
                val raw = editText.text?.toString().orEmpty()
                handleAddWebsite(raw)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            if (activeAddDialog === dialog) {
                activeAddDialog = null
            }
        }
        activeAddDialog?.dismiss()
        activeAddDialog = dialog
        dialog.show()
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
        activeAddDialog?.dismiss()
        activeAddDialog = null
        _binding = null
    }

    companion object {
        const val TAG = "WebsiteRulesBottomSheet"
        const val REQUEST_KEY = "website_rules_request"
        const val RESULT_BLOCKED_URLS = "result_blocked_urls"
        const val RESULT_ALLOWED_URLS = "result_allowed_urls"

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
