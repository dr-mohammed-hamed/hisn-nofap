package com.drmohammed.antiporn.provisioning.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.provisioning.engine.AdbMdnsDiscovery
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.engine.ProvisioningFreezeRecoveryStore
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * Foreground Service managing mDNS discovery, live settings guidance via ContentObserver,
 * and the high-priority Heads-Up pairing prompt notification.
 */
class AdbPairingNotificationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val companionSyncLock = Any()
    private val promptState = AdbPairingPromptState()

    @Volatile
    private var isServiceDestroyed = false
    private var mdnsDiscovery: AdbMdnsDiscovery? = null
    private var adbWifiObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        isServiceDestroyed = false
        activeInstance = WeakReference(this)
        AdbPairingNotificationFactory.createNotificationChannels(this)
        registerAdbWifiObserver()
        try {
            val initialNotification = AdbPairingNotificationFactory.buildGuideNotification(this, isWirelessAdbEnabled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to promote service to foreground in onCreate")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_ACK_STATE -> {
                promptState.refreshPromptPorts(DeviceOwnerPromoter.isPromotionActive)?.let { (pairPort, connPort) ->
                    AdbPairingOutcomeDispatcher.notifyPairingPrompt(this, pairPort, connPort)
                }
                return START_STICKY
            }
            ACTION_CLEAR_STICKY_OUTCOME -> {
                handleClearStickyOutcome(refreshPrompt = !intent.getBooleanExtra(EXTRA_SUPPRESS_REPROMPT, false))
                return START_STICKY
            }
            ACTION_SUBMIT_PAIRING_CODE -> {
                handleSubmitPairingCodeIntent(intent)
                return START_STICKY
            }
        }
        startMdnsRadar()
        return START_STICKY
    }

    private fun handleSubmitPairingCodeIntent(intent: Intent) {
        val rawCode = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingCodeReceiver.EXTRA_PAIRING_CODE)?.toString()
        val code = PairingCodeReceiver.sanitizePairingCode(rawCode)
        val ports = promptState.currentPorts()
        val port = intent.getIntExtra(EXTRA_PAIRING_PORT, 0).takeIf { it > 0 } ?: ports.first
        val connectPort = ports.second.takeIf { it > 0 } ?: intent.getIntExtra(EXTRA_CONNECT_PORT, 0)

        if (code.length == 6 && port > 0) {
            handlePairingCodeSubmission(code, port, connectPort)
        } else {
            AdbPairingOutcomeDispatcher.dispatchInvalidPairingInput(this, rawCode == null, code.length, port, ports)
        }
    }

    private fun handlePairingCodeSubmission(code: String, port: Int, connectPort: Int) {
        if (OemProvisioningHelper.requiresPreActivationDangerAck(this) && !ProvisioningAckStore.hasValidAck(this)) {
            AdbPairingOutcomeDispatcher.dispatchAckRequiredBlocked(this, port, promptState.currentPorts())
            return
        }
        if (!promptState.tryBeginPromotion(DeviceOwnerPromoter.isPromotionActive)) {
            Timber.tag(TAG).w("Promotion already in progress, ignoring duplicate submit")
            return
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(
            PAIRING_PROMPT_NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildInProgressNotification(this)
        )

        val dispatchCtx = OutcomeDispatchContext(this, promptState, port, connectPort, ::stopForegroundAndSelf)
        serviceScope.launch {
            try {
                val result = DeviceOwnerPromoter.pairAndPromote(this@AdbPairingNotificationService, port, connectPort, code)
                AdbPairingOutcomeDispatcher.dispatchResult(dispatchCtx, result)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    val lastKnownConnectPort = connectPort.takeIf { it > 0 } ?: capturedConnectPort
                    promptState.cancelPromotionWithoutStickyOutcome()
                    withContext(NonCancellable + Dispatchers.IO) {
                        notifyRecoveryOnCancellationIfNeeded(lastKnownConnectPort)
                    }
                    throw t
                }
                AdbPairingOutcomeDispatcher.dispatchUnexpectedError(dispatchCtx, t)
            }
        }
    }

    private fun notifyRecoveryOnCancellationIfNeeded(connectPortSnapshot: Int) {
        try {
            val stranded = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(this)
            if (stranded.isEmpty()) return
            val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (!canNotify) return

            val recoveryIntent = Intent(this, ProvisioningWizardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ProvisioningWizardActivity.EXTRA_SHOW_RECOVERY_DIALOG, true)
                if (connectPortSnapshot > 0) putExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, connectPortSnapshot)
            }
            val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
            val notif = AdbPairingNotificationFactory.buildFreezeRecoveryNotification(
                context = this,
                affectedCount = stranded.size,
                wizardIntent = recoveryIntent,
                optionsBundle = optionsBundle
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NotificationRegistry.EVENT_FREEZE_RECOVERY_ID, notif)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed posting freeze recovery notification on cancellation")
        }
    }

    private fun handleClearStickyOutcome(refreshPrompt: Boolean = true) {
        val snapshot = promptState.clearStickyOutcome(DeviceOwnerPromoter.isPromotionActive, refreshPrompt)
        AdbPairingOutcomeDispatcher.dispatchClearSticky(this, snapshot)
    }

    private fun registerAdbWifiObserver() {
        try {
            val uri = Settings.Global.getUriFor("adb_wifi_enabled")
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val isEnabled = isWirelessAdbEnabled()
                    Timber.tag(TAG).i("Observed adb_wifi_enabled change: $isEnabled")
                    AdbPairingOutcomeDispatcher.notifyGuideStatus(this@AdbPairingNotificationService, isEnabled)
                }
            }
            adbWifiObserver = observer
            contentResolver.registerContentObserver(uri, false, observer)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed registering ContentObserver for adb_wifi_enabled: ${e.message}")
        }
    }

    private fun isWirelessAdbEnabled(): Boolean = try {
        Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
    } catch (_: Exception) {
        false
    }

    private fun startMdnsRadar() {
        val initialSnapshot = promptState.currentPortSnapshot()
        if (initialSnapshot.pairingPort > 0 || initialSnapshot.connectPort > 0) {
            AdbPairingOutcomeDispatcher.dispatchPortSnapshot(this, initialSnapshot, syncCompanionPortsFromState())
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = AdbMdnsDiscovery(
            context = this,
            onPairingPortDiscovered = { port ->
                postMdnsEventOnMain {
                    val snapshot = promptState.onPairingPortDiscovered(port)
                    AdbPairingOutcomeDispatcher.dispatchPortSnapshot(this, snapshot, syncCompanionPortsFromState())
                }
            },
            onConnectPortDiscovered = { port ->
                postMdnsEventOnMain {
                    val snapshot = promptState.onConnectPortDiscovered(port)
                    AdbPairingOutcomeDispatcher.dispatchPortSnapshot(this, snapshot, syncCompanionPortsFromState())
                }
            },
            onPairingServiceLost = {
                Timber.tag(TAG).i("Pairing service lost via mDNS. Resetting pairing port.")
                postMdnsEventOnMain {
                    val snapshot = promptState.onPairingServiceLost()
                    AdbPairingOutcomeDispatcher.dispatchServiceLost(this, snapshot, syncCompanionPortsFromState())
                }
            },
            onConnectServiceLost = {
                Timber.tag(TAG).i("Connect service lost via mDNS. Resetting connect port.")
                postMdnsEventOnMain {
                    val snapshot = promptState.onConnectServiceLost()
                    AdbPairingOutcomeDispatcher.dispatchServiceLost(this, snapshot, syncCompanionPortsFromState())
                }
            }
        ).also { it.startDiscovery() }
    }

    private inline fun postMdnsEventOnMain(crossinline block: () -> Unit) {
        if (isServiceDestroyed) return
        mainHandler.post {
            if (isServiceDestroyed || activeInstance?.get() !== this) return@post
            block()
        }
    }

    private fun syncCompanionPortsFromState(): Pair<Int, Int> = synchronized(companionSyncLock) {
        val ports = promptState.currentPorts()
        capturedPairingPort = ports.first
        capturedConnectPort = ports.second
        ports
    }

    private fun stopForegroundAndSelf() {
        try {
            AdbPairingOutcomeDispatcher.cancelPairingPromptNotification(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping foreground on ACTION_STOP_SERVICE")
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = null
        stopSelf()
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (activeInstance?.get() === this) activeInstance = null
        serviceJob.cancel()
        adbWifiObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error unregistering adbWifiObserver on destroy")
            }
            adbWifiObserver = null
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = null
        promptState.reset()
        syncCompanionPortsFromState()
        AdbPairingOutcomeDispatcher.cancelPairingPromptNotification(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    sealed class StickyOutcomePayload {
        data class BlockingUsers(val usersJson: String, val connectPort: Int) : StickyOutcomePayload()
        data class OemPermission(val oemTypeName: String, val connectPort: Int) : StickyOutcomePayload()
        data class AccountsBlocking(val accountsJson: String, val connectPort: Int) : StickyOutcomePayload()
    }

    companion object {
        private const val TAG = "AntiPorn_PairingSvc"
        const val CHANNEL_ID_GUIDE = "provisioning_guide_silent_v1"
        const val CHANNEL_ID_HEADS_UP = "provisioning_pairing_heads_up_v2"
        const val NOTIFICATION_ID = NotificationRegistry.ADB_PAIRING_ID
        const val PAIRING_PROMPT_NOTIFICATION_ID = 9902
        const val SUCCESS_NOTIFICATION_ID = 9903

        @Volatile var capturedPairingPort: Int = 0
        @Volatile var capturedConnectPort: Int = 0
        @Volatile var pendingStickyOutcome: StickyOutcomePayload? = null
        @Volatile private var activeInstance: WeakReference<AdbPairingNotificationService>? = null

        const val ACTION_START_SERVICE = "com.drmohammed.antiporn.START_PAIRING_SERVICE"
        const val ACTION_STOP_SERVICE = "com.drmohammed.antiporn.STOP_PAIRING_SERVICE"
        const val ACTION_REFRESH_ACK_STATE = "com.drmohammed.antiporn.ACTION_REFRESH_ACK_STATE"
        const val ACTION_CLEAR_STICKY_OUTCOME = "com.drmohammed.antiporn.ACTION_CLEAR_STICKY_OUTCOME"
        const val ACTION_SUBMIT_PAIRING_CODE = "com.drmohammed.antiporn.ACTION_SUBMIT_PAIRING_CODE"
        const val ACTION_PORTS_DISCOVERED = "com.drmohammed.antiporn.PORTS_DISCOVERED"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"
        const val EXTRA_SUPPRESS_REPROMPT = "extra_suppress_reprompt"

        fun refreshNotificationState(context: Context) {
            if (capturedPairingPort <= 0) return
            try {
                context.startService(Intent(context, AdbPairingNotificationService::class.java).apply { action = ACTION_REFRESH_ACK_STATE })
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed starting service for ACTION_REFRESH_ACK_STATE")
            }
        }

        fun clearStickyOutcome(context: Context, suppressReprompt: Boolean = false) {
            pendingStickyOutcome = null
            activeInstance?.get()?.let { it.handleClearStickyOutcome(refreshPrompt = !suppressReprompt); return }
            if (!DeviceOwnerPromoter.isPromotionActive) {
                AdbPairingOutcomeDispatcher.cancelPairingPromptNotification(context)
            }
            if (capturedPairingPort > 0) {
                try {
                    context.startService(Intent(context, AdbPairingNotificationService::class.java).apply {
                        action = ACTION_CLEAR_STICKY_OUTCOME
                        putExtra(EXTRA_SUPPRESS_REPROMPT, suppressReprompt)
                    })
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed starting service for ACTION_CLEAR_STICKY_OUTCOME")
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingNotificationService::class.java).apply { action = ACTION_START_SERVICE }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed starting AdbPairingNotificationService")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AdbPairingNotificationService::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed stopping AdbPairingNotificationService")
            }
        }
    }
}
