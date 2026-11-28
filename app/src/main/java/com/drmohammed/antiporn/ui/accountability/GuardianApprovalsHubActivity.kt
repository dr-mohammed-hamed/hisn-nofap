package com.drmohammed.antiporn.ui.accountability

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class GuardianApprovalsHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var rvApprovals: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var ivEmptyIcon: ImageView
    private lateinit var tvEmptyTitle: TextView

    private lateinit var adapter: GuardianApprovalsAdapter
    private val viewModel: GuardianApprovalsHubViewModel by viewModel()
    private var isProgrammaticTabUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_approvals_hub)

        applyWindowInsets()
        initViews()
        setupRecyclerView()
        setupListeners()
        observeViewModel()

        viewModel.initialize(
            targetWardId = intent.getStringExtra(EXTRA_WARD_ID),
            targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE),
            forceReset = false
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.initialize(
            targetWardId = intent.getStringExtra(EXTRA_WARD_ID),
            targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE),
            forceReset = true
        )
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootApprovalsHub)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tabLayout = findViewById(R.id.tabLayoutApprovals)
        rvApprovals = findViewById(R.id.rvApprovals)
        progressBar = findViewById(R.id.progressBar)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
    }

    private fun setupRecyclerView() {
        adapter = GuardianApprovalsAdapter(
            onApproveClick = { item, callback ->
                callback(true)
                viewModel.resolveApproval(item, approve = true)
            },
            onRejectClick = { item, callback ->
                callback(true)
                viewModel.resolveApproval(item, approve = false)
            },
            onRevokeClick = { item, callback ->
                callback(true)
                viewModel.resolveApproval(item, approve = false)
            },
            onReApproveClick = { item, callback ->
                callback(true)
                viewModel.resolveApproval(item, approve = true)
            }
        )
        rvApprovals.layoutManager = LinearLayoutManager(this)
        rvApprovals.adapter = adapter
    }

    private fun setupListeners() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (isProgrammaticTabUpdate) return
                tab?.let { viewModel.selectTab(it.position) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ApprovalsHubEvent.ShowToastRes -> {
                                Toast.makeText(
                                    this@GuardianApprovalsHubActivity,
                                    event.resId,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: GuardianApprovalsHubUiState) {
        progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        adapter.setInFlightKeys(state.inFlightApprovalKeys)
        updateTabBadges(state)

        if (tabLayout.selectedTabPosition != state.selectedTabIndex) {
            isProgrammaticTabUpdate = true
            tabLayout.getTabAt(state.selectedTabIndex)?.select()
            isProgrammaticTabUpdate = false
        }

        filterAndDisplay(state)
    }

    private fun updateTabBadges(state: GuardianApprovalsHubUiState) {
        val pendingCount = state.countForStatus("PENDING")
        val approvedCount = state.countForStatus("APPROVED")
        val rejectedCount = state.countForStatus("REJECTED")

        tabLayout.getTabAt(0)?.text = if (pendingCount > 0) {
            "${getString(R.string.tab_approvals_pending)} ($pendingCount)"
        } else {
            getString(R.string.tab_approvals_pending)
        }

        tabLayout.getTabAt(1)?.text = if (approvedCount > 0) {
            "${getString(R.string.tab_approvals_approved)} ($approvedCount)"
        } else {
            getString(R.string.tab_approvals_approved)
        }

        tabLayout.getTabAt(2)?.text = if (rejectedCount > 0) {
            "${getString(R.string.tab_approvals_rejected)} ($rejectedCount)"
        } else {
            getString(R.string.tab_approvals_rejected)
        }
    }

    private fun filterAndDisplay(state: GuardianApprovalsHubUiState) {
        val filtered = state.filteredApprovalsForSelectedTab()
        if (filtered.isEmpty() && !state.isLoading) {
            rvApprovals.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
            tvEmptyTitle.text = when (state.selectedTabIndex) {
                0 -> getString(R.string.empty_approvals_pending)
                1 -> getString(R.string.empty_approvals_approved)
                else -> getString(R.string.empty_approvals_rejected)
            }
            adapter.submitList(emptyList())
        } else if (filtered.isEmpty()) {
            rvApprovals.visibility = View.GONE
            layoutEmptyState.visibility = View.GONE
            adapter.submitList(emptyList())
        } else {
            layoutEmptyState.visibility = View.GONE
            rvApprovals.visibility = View.VISIBLE
            adapter.submitList(filtered) {
                if (!state.hasScrolledToTarget && !state.targetPackage.isNullOrBlank()) {
                    val targetIndex = filtered.indexOfFirst {
                        it.packageName == state.targetPackage &&
                            (state.targetWardId.isNullOrBlank() ||
                                it.wardId == state.targetWardId ||
                                it.wardId == state.canonicalTargetWardId)
                    }
                    if (targetIndex >= 0) {
                        viewModel.markScrolledToTarget()
                        rvApprovals.smoothScrollToPosition(targetIndex)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
    }
}
