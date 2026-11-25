package com.drmohammed.antiporn.provisioning.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.provisioning.engine.AdbMdnsDiscovery
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground Service that manages mDNS auto-discovery, live settings step-by-step
 * guidance via ContentObserver, and surfaces the high-priority Heads-Up pairing prompt.
 */
class AdbPairingNotificationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val stateLock = Any()

    private var mdnsDiscovery: AdbMdnsDiscovery? = null
    @Volatile private var capturedPairingPort = 0
    @Volatile private var capturedConnectPort = 0
    private var adbWifiObserver: ContentObserver? = null
    @Volatile private var lastPromptedPairingPort = 0
    @Volatile private var isPromoting = false
    @Volatile private var isShowingStickyOutcome = false

    override fun onCreate() {
        super.onCreate()
        AdbPairingNotificationFactory.createNotificationChannels(this)
        registerAdbWifiObserver()

        try {
            val initialNotification = AdbPairingNotificationFactory.buildGuideNotification(this, isWirelessAdbEnabled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
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
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (capturedPairingPort > 0 && !isPromoting && !isShowingStickyOutcome && !DeviceOwnerPromoter.isPromotionActive) {
                    nm?.notify(
                        PAIRING_PROMPT_NOTIFICATION_ID,
                        AdbPairingNotificationFactory.buildPairingPromptNotification(this, capturedPairingPort, capturedConnectPort)
                    )
                }
                return START_STICKY
            }
            ACTION_SUBMIT_PAIRING_CODE -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val rawCode = remoteInput?.getCharSequence(PairingCodeReceiver.EXTRA_PAIRING_CODE)?.toString()
                val code = PairingCodeReceiver.sanitizePairingCode(rawCode)
                val port = intent.getIntExtra(EXTRA_PAIRING_PORT, 0).takeIf { it > 0 } ?: capturedPairingPort
                val connectPort = capturedConnectPort.takeIf { it > 0 } ?: intent.getIntExtra(EXTRA_CONNECT_PORT, 0)

                if (code.length == 6 && port > 0) {
                    handlePairingCodeSubmission(code, port, connectPort)
                } else {
                    Timber.tag(TAG).w("ACTION_SUBMIT_PAIRING_CODE received with empty/invalid code. len=${code.length}, port=$port")
                    BlackBoxRecorder.recordPairing(
                        this,
                        "REMOTE_INPUT_EMPTY_OR_INVALID",
                        isSuccess = false,
                        details = "rawCodeNull=${rawCode == null}, port=$port"
                    )
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.notify(
                        PAIRING_PROMPT_NOTIFICATION_ID,
                        AdbPairingNotificationFactory.buildPairingPromptNotification(this, capturedPairingPort, capturedConnectPort)
                    )
                }
                return START_STICKY
            }
        }

        startMdnsRadar()
        return START_STICKY
    }

    private fun handlePairingCodeSubmission(code: String, port: Int, connectPort: Int) {
        if (OemProvisioningHelper.requiresPreActivationDangerAck(this) && !ProvisioningAckStore.hasValidAck(this)) {
            Timber.tag(TAG).w("Submission blocked: Danger acknowledgment required but missing or expired")
            BlackBoxRecorder.recordPairing(this, "SUBMISSION_BLOCKED_NO_ACK", isSuccess = false, details = "port=$port")
            val failIntent = Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                setPackage(packageName)
                putExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                putExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE, getString(R.string.provisioning_error_ack_required))
            }
            sendBroadcast(failIntent)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(
                PAIRING_PROMPT_NOTIFICATION_ID,
                AdbPairingNotificationFactory.buildPairingPromptNotification(this, capturedPairingPort, capturedConnectPort)
            )
            return
        }

        synchronized(stateLock) {
            if (isPromoting || DeviceOwnerPromoter.isPromotionActive) {
                Timber.tag(TAG).w("Promotion already in progress, ignoring duplicate submit")
                return
            }
            isPromoting = true
            isShowingStickyOutcome = false
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(PAIRING_PROMPT_NOTIFICATION_ID, AdbPairingNotificationFactory.buildInProgressNotification(this))

        serviceScope.launch {
            try {
                val result = DeviceOwnerPromoter.pairAndPromote(
                    context = this@AdbPairingNotificationService,
                    pairingPort = port,
                    connectPort = connectPort,
                    pairingCode = code
                )

                when (result) {
                    is PromotionResult.Success -> {
                        isPromoting = false
                        isShowingStickyOutcome = false
                        Timber.tag(TAG).i("Promotion succeeded via service submission")
                        ProvisioningAckStore.clearAck(this@AdbPairingNotificationService)

                        val mainIntent = Intent(this@AdbPairingNotificationService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
                        try {
                            startActivity(mainIntent, optionsBundle)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Direct launch of MainActivity failed")
                        }

                        val successIntent = Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                            setPackage(packageName)
                            putExtra(PairingCodeReceiver.EXTRA_SUCCESS, true)
                        }
                        sendBroadcast(successIntent)

                        nm?.notify(
                            SUCCESS_NOTIFICATION_ID,
                            AdbPairingNotificationFactory.buildSuccessNotification(
                                this@AdbPairingNotificationService,
                                mainIntent,
                                optionsBundle
                            )
                        )
                        stopForegroundAndSelf()
                    }
                    is PromotionResult.SecondaryUsersBlocking -> {
                        isShowingStickyOutcome = true
                        isPromoting = false
                        val usersJson = BlockingSecondaryUser.listToJson(result.users)
                        Timber.tag(TAG).w("Promotion paused: ${result.users.size} blocking secondary user(s) detected")

                        val pausedBroadcast = Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                            setPackage(packageName)
                            putExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                            putExtra(PairingCodeReceiver.EXTRA_BLOCKING_USERS_JSON, usersJson)
                            putExtra(EXTRA_CONNECT_PORT, connectPort.takeIf { it > 0 } ?: capturedConnectPort)
                        }
                        sendBroadcast(pausedBroadcast)

                        val wizardIntent = Intent(this@AdbPairingNotificationService, ProvisioningWizardActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_USERS_JSON, usersJson)
                            putExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, connectPort.takeIf { it > 0 } ?: capturedConnectPort)
                        }
                        val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
                        try {
                            startActivity(wizardIntent, optionsBundle)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Direct launch of ProvisioningWizardActivity for blocking users failed")
                        }

                        nm?.notify(
                            PAIRING_PROMPT_NOTIFICATION_ID,
                            AdbPairingNotificationFactory.buildBlockingUsersNotification(
                                this@AdbPairingNotificationService,
                                wizardIntent,
                                optionsBundle
                            )
                        )
                    }
                    is PromotionResult.Failure -> {
                        isPromoting = false
                        val alreadyInProgressMsg = getString(R.string.provisioning_error_already_in_progress)
                        if (DeviceOwnerPromoter.isPromotionActive && result.message == alreadyInProgressMsg) {
                            Timber.tag(TAG).i("Ignoring concurrent lock rejection because another promotion is active")
                            return@launch
                        }
                        isShowingStickyOutcome = true
                        Timber.tag(TAG).e("Promotion failed: ${result.message}")

                        val failIntent = Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                            setPackage(packageName)
                            putExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                            putExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE, result.message)
                        }
                        sendBroadcast(failIntent)

                        nm?.notify(
                            PAIRING_PROMPT_NOTIFICATION_ID,
                            AdbPairingNotificationFactory.buildFailureNotification(
                                this@AdbPairingNotificationService,
                                result.message
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                isPromoting = false
                Timber.tag(TAG).e(t, "Exception during handlePairingCodeSubmission")
            }
        }
    }

    private fun registerAdbWifiObserver() {
        try {
            val uri: Uri = Settings.Global.getUriFor("adb_wifi_enabled")
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val isEnabled = isWirelessAdbEnabled()
                    Timber.tag(TAG).i("Observed adb_wifi_enabled change: $isEnabled")
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.notify(
                        NOTIFICATION_ID,
                        AdbPairingNotificationFactory.buildGuideNotification(this@AdbPairingNotificationService, isEnabled)
                    )
                }
            }
            adbWifiObserver = observer
            contentResolver.registerContentObserver(uri, false, observer)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed registering ContentObserver for adb_wifi_enabled: ${e.message}")
        }
    }

    private fun isWirelessAdbEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    private fun startMdnsRadar() {
        if (capturedPairingPort > 0 || capturedConnectPort > 0) {
            notifyPortState()
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = AdbMdnsDiscovery(
            context = this,
            onPairingPortDiscovered = { port ->
                synchronized(stateLock) {
                    capturedPairingPort = port
                }
                notifyPortState()
            },
            onConnectPortDiscovered = { port ->
                synchronized(stateLock) {
                    capturedConnectPort = port
                }
                notifyPortState()
            },
            onPairingServiceLost = {
                Timber.tag(TAG).i("Pairing service lost via mDNS. Resetting pairing port.")
                val shouldCancelPrompt = synchronized(stateLock) {
                    if (!isPromoting) {
                        capturedPairingPort = 0
                        Companion.capturedPairingPort = 0
                        lastPromptedPairingPort = 0
                        !isShowingStickyOutcome
                    } else {
                        false
                    }
                }
                val broadcastIntent = Intent(ACTION_PORTS_DISCOVERED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PAIRING_PORT, capturedPairingPort)
                    putExtra(EXTRA_CONNECT_PORT, capturedConnectPort)
                }
                sendBroadcast(broadcastIntent)
                if (shouldCancelPrompt) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.cancel(PAIRING_PROMPT_NOTIFICATION_ID)
                }
            }
        ).also { it.startDiscovery() }
    }

    private fun notifyPortState() {
        val (pairPort, connPort, shouldPrompt) = synchronized(stateLock) {
            Companion.capturedPairingPort = capturedPairingPort
            Companion.capturedConnectPort = capturedConnectPort
            val prompt = capturedPairingPort > 0 &&
                capturedPairingPort != lastPromptedPairingPort &&
                !isPromoting &&
                !isShowingStickyOutcome
            if (prompt) {
                lastPromptedPairingPort = capturedPairingPort
            }
            Triple(capturedPairingPort, capturedConnectPort, prompt)
        }
        Timber.tag(TAG).i("Port state updated: pairing=$pairPort, connect=$connPort")

        val broadcastIntent = Intent(ACTION_PORTS_DISCOVERED).apply {
            setPackage(packageName)
            putExtra(EXTRA_PAIRING_PORT, pairPort)
            putExtra(EXTRA_CONNECT_PORT, connPort)
        }
        sendBroadcast(broadcastIntent)

        if (shouldPrompt) {
            BlackBoxRecorder.recordPairing(this, "PAIRING_PORT_CAPTURED", isSuccess = true, details = "port=$pairPort")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(
                PAIRING_PROMPT_NOTIFICATION_ID,
                AdbPairingNotificationFactory.buildPairingPromptNotification(this, pairPort, connPort)
            )
        }
    }

    private fun stopForegroundAndSelf() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(PAIRING_PROMPT_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping foreground on ACTION_STOP_SERVICE")
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = null
        stopSelf()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        adbWifiObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error unregistering adbWifiObserver on destroy")
            }
            adbWifiObserver = null
        }
        mdnsDiscovery?.stopDiscovery()
        mdnsDiscovery = null
        synchronized(stateLock) {
            capturedPairingPort = 0
            capturedConnectPort = 0
            Companion.capturedPairingPort = 0
            Companion.capturedConnectPort = 0
            lastPromptedPairingPort = 0
            isShowingStickyOutcome = false
            isPromoting = false
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(PAIRING_PROMPT_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AntiPorn_PairingSvc"
        const val CHANNEL_ID_GUIDE = "provisioning_guide_silent_v1"
        const val CHANNEL_ID_HEADS_UP = "provisioning_pairing_heads_up_v2"

        const val NOTIFICATION_ID = NotificationRegistry.ADB_PAIRING_ID
        const val PAIRING_PROMPT_NOTIFICATION_ID = 9902
        const val SUCCESS_NOTIFICATION_ID = 9903

        @Volatile var capturedPairingPort: Int = 0
        @Volatile var capturedConnectPort: Int = 0

        const val ACTION_START_SERVICE = "com.drmohammed.antiporn.START_PAIRING_SERVICE"
        const val ACTION_STOP_SERVICE = "com.drmohammed.antiporn.STOP_PAIRING_SERVICE"
        const val ACTION_REFRESH_ACK_STATE = "com.drmohammed.antiporn.ACTION_REFRESH_ACK_STATE"
        const val ACTION_SUBMIT_PAIRING_CODE = "com.drmohammed.antiporn.ACTION_SUBMIT_PAIRING_CODE"

        const val ACTION_PORTS_DISCOVERED = "com.drmohammed.antiporn.PORTS_DISCOVERED"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"

        fun refreshNotificationState(context: Context) {
            if (capturedPairingPort <= 0) return
            val intent = Intent(context, AdbPairingNotificationService::class.java).apply {
                action = ACTION_REFRESH_ACK_STATE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed starting service for ACTION_REFRESH_ACK_STATE")
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingNotificationService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
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
