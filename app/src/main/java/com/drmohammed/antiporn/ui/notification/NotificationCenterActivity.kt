package com.drmohammed.antiporn.ui.notification

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityNotificationCenterBinding
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.router.NotificationActionRouter
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Activity presenting the In-App Notification Center and Event History.
 * Supports category filtering, mark as read, batch clear, and deep-link routing.
 */
class NotificationCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationCenterBinding
    private val viewModel: NotificationCenterViewModel by viewModel()
    private lateinit var adapter: NotificationAdapter
    private var lastItemClickTime = 0L
    private var lastActionClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupFragmentResultListeners()
        setupToolbar()
        setupFilters()
        setupRecyclerView()
        observeViewModel()
        handleIntentAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    private fun applyWindowInsets() {
        val appBarLeft = binding.appBarLayout.paddingLeft
        val appBarTop = binding.appBarLayout.paddingTop
        val appBarRight = binding.appBarLayout.paddingRight
        val appBarBottom = binding.appBarLayout.paddingBottom

        val rvLeft = binding.rvNotifications.paddingLeft
        val rvTop = binding.rvNotifications.paddingTop
        val rvRight = binding.rvNotifications.paddingRight
        val rvBottom = binding.rvNotifications.paddingBottom

        val emptyLeft = binding.layoutEmptyState.paddingLeft
        val emptyTop = binding.layoutEmptyState.paddingTop
        val emptyRight = binding.layoutEmptyState.paddingRight
        val emptyBottom = binding.layoutEmptyState.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutRootNotificationCenter) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBarLayout.setPadding(
                appBarLeft + insets.left,
                appBarTop + insets.top,
                appBarRight + insets.right,
                appBarBottom
            )
            binding.rvNotifications.setPadding(
                rvLeft + insets.left,
                rvTop,
                rvRight + insets.right,
                rvBottom + insets.bottom
            )
            binding.layoutEmptyState.setPadding(
                emptyLeft + insets.left,
                emptyTop,
                emptyRight + insets.right,
                emptyBottom + insets.bottom
            )
            windowInsets
        }
    }

    private fun handleIntentAction(intent: Intent?) {
        val actionKey = intent?.getStringExtra(AppNotificationManager.EXTRA_ACTION_TYPE) ?: return
        val payload = intent.getStringExtra(AppNotificationManager.EXTRA_ACTION_PAYLOAD)
        val actionType = NotificationActionType.fromKey(actionKey)
        if (actionType != NotificationActionType.NONE) {
            intent.removeExtra(AppNotificationManager.EXTRA_ACTION_TYPE)
            intent.removeExtra(AppNotificationManager.EXTRA_ACTION_PAYLOAD)
            NotificationActionRouter.route(this, actionType, payload)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnMarkAllRead.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastActionClickTime < 500L) return@setOnClickListener
            lastActionClickTime = now
            viewModel.markAllAsRead()
        }

        binding.btnClearAll.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastActionClickTime < 500L) return@setOnClickListener
            lastActionClickTime = now
            showClearConfirmationDialog()
        }
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val selectedCategory = when (checkedIds.first()) {
                R.id.chipFilterApprovals -> NotificationCategory.APPROVALS
                R.id.chipFilterGuardian -> NotificationCategory.GUARDIAN
                R.id.chipFilterCurfew -> NotificationCategory.CURFEW
                R.id.chipFilterShields -> NotificationCategory.SHIELDS
                R.id.chipFilterAi -> NotificationCategory.AI
                R.id.chipFilterOta -> NotificationCategory.OTA
                R.id.chipFilterSystem -> NotificationCategory.SYSTEM
                else -> null
            }
            viewModel.setCategoryFilter(selectedCategory)
        }
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter { notification ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastItemClickTime < 500L) return@NotificationAdapter
            lastItemClickTime = now

            if (!notification.isRead) {
                viewModel.markAsRead(notification.id)
            }
            NotificationActionRouter.route(this, notification)
        }

        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.notifications.collectLatest { list ->
                        adapter.submitList(list)
                        val isEmpty = list.isEmpty()
                        binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        binding.rvNotifications.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        binding.btnClearAll.isEnabled = !isEmpty
                    }
                }

                launch {
                    viewModel.unreadCount.collectLatest { count ->
                        binding.btnMarkAllRead.isEnabled = count > 0
                    }
                }
            }
        }
    }

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(REQ_CLEAR_NOTIFICATIONS, this) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                viewModel.clearAllNotifications()
            }
        }
    }

    private fun showClearConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        supportFragmentManager.showSafely(TAG_CLEAR_NOTIFICATIONS) {
            ConfirmActionDialogFragment.newInstance(
                requestKey = REQ_CLEAR_NOTIFICATIONS,
                titleRes = R.string.confirm_clear_notifications_title,
                messageRes = R.string.confirm_clear_notifications_msg,
                positiveRes = R.string.btn_confirm_clear,
                negativeRes = android.R.string.cancel
            )
        }
    }

    companion object {
        private const val REQ_CLEAR_NOTIFICATIONS = "req_notif_clear_all"
        private const val TAG_CLEAR_NOTIFICATIONS = "tag_notif_clear_all"
    }
}
