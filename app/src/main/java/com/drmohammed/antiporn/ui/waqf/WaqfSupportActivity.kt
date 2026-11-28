package com.drmohammed.antiporn.ui.waqf

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.databinding.ActivityWaqfSupportBinding
import com.drmohammed.antiporn.waqf.model.WaqfActionType
import com.drmohammed.antiporn.waqf.model.WaqfActionValidator
import com.drmohammed.antiporn.waqf.model.WaqfChannel
import com.drmohammed.antiporn.waqf.model.WaqfChannelAction
import com.drmohammed.antiporn.waqf.model.WaqfLoadSource
import com.drmohammed.antiporn.waqf.model.WaqfSnapshot
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Full-screen Developer Support screen.
 *
 * Displays an inspirational Hadith & purpose Hero Card with an always-visible transparency notice,
 * dynamic Stale-While-Revalidate developer support channels (with automatic Banking Cloak support
 * for InstaPay), an inline offline retry banner when cold-fallback is active, and a share card.
 */
class WaqfSupportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaqfSupportBinding
    private val viewModel: WaqfSupportViewModel by viewModel()
    private lateinit var channelsAdapter: WaqfChannelsAdapter
    private var lastClickTimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityWaqfSupportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutRootWaqfSupport) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeSnapshot()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        channelsAdapter = WaqfChannelsAdapter(
            onActionClick = { channel, action ->
                if (!consumeDebounce()) return@WaqfChannelsAdapter
                handleChannelAction(channel, action)
            },
            onCopyValueClick = { channel, value ->
                if (!consumeDebounce()) return@WaqfChannelsAdapter
                copyToClipboard(label = channel.title, value = value)
            }
        )
        binding.rvWaqfChannels.apply {
            layoutManager = LinearLayoutManager(this@WaqfSupportActivity)
            adapter = channelsAdapter
            itemAnimator = null
        }
    }

    private fun setupListeners() {
        binding.btnRetryWaqf.setOnClickListener {
            if (!consumeDebounce()) return@setOnClickListener
            viewModel.retryRefresh()
        }

        binding.btnShareApp.setOnClickListener {
            if (!consumeDebounce()) return@setOnClickListener
            shareAppForReward()
        }
    }

    private fun observeSnapshot() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.snapshot.collect { snapshot ->
                    renderSnapshot(snapshot)
                }
            }
        }
    }

    private fun renderSnapshot(snapshot: WaqfSnapshot) {
        val hero = snapshot.config.hero
        binding.tvHeroBadge.text = hero?.badge?.takeIf { it.isNotBlank() }
            ?: getString(R.string.waqf_hero_badge)
        binding.tvHeroHadith.text = hero?.hadith?.takeIf { it.isNotBlank() }
            ?: getString(R.string.waqf_hero_hadith)
        binding.tvHeroTitle.text = hero?.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.waqf_hero_title)
        binding.tvHeroBody.text = hero?.body?.takeIf { it.isNotBlank() }
            ?: getString(R.string.waqf_hero_body)

        val notice = hero?.notice?.takeIf { it.isNotBlank() }
            ?: getString(R.string.waqf_hero_notice)
        binding.cardHeroNotice.isVisible = notice.isNotBlank()
        if (notice.isNotBlank()) {
            binding.tvHeroNotice.text = notice
        }

        val isColdFallback = snapshot.source == WaqfLoadSource.ColdFallback
        binding.pbWaqfLoading.isVisible = snapshot.isRefreshing && isColdFallback
        binding.cardOfflineRetry.isVisible = isColdFallback && !snapshot.isRefreshing

        val activeChannels = snapshot.config.activeChannels()
        binding.tvChannelsSectionTitle.isVisible = activeChannels.isNotEmpty()
        channelsAdapter.submitList(activeChannels)
    }

    private fun handleChannelAction(channel: WaqfChannel, action: WaqfChannelAction) {
        when (action.type) {
            WaqfActionType.OPEN_URL -> openUrlSafely(channel, action.target)
            WaqfActionType.COPY_VALUE -> copyToClipboard(label = channel.title, value = action.target)
            WaqfActionType.DIAL_USSD -> dialUssdSafely(action.target)
            WaqfActionType.UNKNOWN -> {
                Toast.makeText(this, R.string.waqf_invalid_action, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUrlSafely(channel: WaqfChannel, rawUrl: String) {
        if (!WaqfActionValidator.isSafeHttpsUrl(rawUrl)) {
            Toast.makeText(this, R.string.waqf_invalid_action, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            Uri.parse(rawUrl.trim())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed parsing Waqf HTTPS URI")
            Toast.makeText(this, R.string.waqf_invalid_action, Toast.LENGTH_SHORT).show()
            return
        }

        if (channel.requiresBankingCloak) {
            val targetPkg = channel.targetPackage?.takeIf { it.isNotBlank() }
                ?: BankingCloakManager.INSTAPAY_PACKAGE
            val launched = BankingCloakManager.launchUriWithCloakIfNeeded(
                context = this,
                uri = uri,
                targetPackage = targetPkg
            )
            if (!launched) {
                Toast.makeText(this, R.string.waqf_open_link_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Timber.tag(TAG).w(e, "No activity found for Waqf link")
            Toast.makeText(this, R.string.waqf_open_link_failed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed launching Waqf link")
            Toast.makeText(this, R.string.waqf_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dialUssdSafely(rawUssd: String) {
        if (!WaqfActionValidator.isValidUssd(rawUssd)) {
            Toast.makeText(this, R.string.waqf_invalid_action, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val telUri = Uri.parse("tel:" + Uri.encode(rawUssd.trim()))
            startActivity(Intent(Intent.ACTION_DIAL, telUri))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed launching phone dialer for USSD")
            Toast.makeText(this, R.string.waqf_dial_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(this, getString(R.string.waqf_copied_toast, value), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed copying Waqf value to clipboard")
        }
    }

    private fun shareAppForReward() {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.waqf_share_message))
            }
            val chooser = Intent.createChooser(sendIntent, getString(R.string.waqf_share_chooser_title))
            startActivity(chooser)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed launching share chooser")
            Toast.makeText(this, R.string.waqf_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun consumeDebounce(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTimeMs < CLICK_DEBOUNCE_MS) {
            return false
        }
        lastClickTimeMs = now
        return true
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfActivity"
        private const val CLICK_DEBOUNCE_MS = 600L
    }
}
