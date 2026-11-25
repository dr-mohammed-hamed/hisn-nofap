package com.drmohammed.antiporn.ai.service

import android.app.Notification
import android.app.NotificationChannel
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clean Foreground Service coordinating screen capture and AI modesty/nudity detection.
 * Delegates capture mechanics to [ScreenFrameStreamManager] and AI inference to [AiInferenceCoordinator].
 */
class ScreenAiProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private lateinit var streamManager: ScreenFrameStreamManager
    private lateinit var inferenceCoordinator: AiInferenceCoordinator
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ScreenAiProjectionSvc"
        private const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_AI
        private const val NOTIFICATION_ID = NotificationRegistry.FGS_AI_PROJECTION_ID

        const val ACTION_START = "com.drmohammed.antiporn.ai.action.START"
        const val ACTION_STOP = "com.drmohammed.antiporn.ai.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        @Volatile
        var isRunning: Boolean = false
            private set

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private fun updateRunningState(running: Boolean) {
            isRunning = running
            _isRunningFlow.value = running
        }

        @Volatile
        var isStopping: Boolean = false
            private set

        data class LastDetection(val label: String = "", val confidence: Float = 0f)

        @Volatile
        var lastDetection: LastDetection = LastDetection()
            private set

        @Volatile
        var lastDetectedLabel: String = ""
            private set

        @Volatile
        var lastConfidence: Float = 0f
            private set

        @Volatile
        var currentFps: Float = 0f
            internal set

        @Volatile
        var modelName: String = "Halalify v2"
            private set

        @Volatile
        var initError: String = ""
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        initError = ""
        Log.i(TAG, "ScreenAiProjectionService onCreate")

        streamManager = ScreenFrameStreamManager(
            context = applicationContext,
            onFrameAcquired = { image, width, height ->
                currentFps = streamManager.currentFps
                inferenceCoordinator.processFrame(image, width, height)
            },
            onStaticFrameDetected = {
                currentFps = streamManager.currentFps
                inferenceCoordinator.onStaticScreen()
            },
            onHardSceneCutDetected = {
                inferenceCoordinator.onHardSceneCut()
            },
            onSceneChangeRatio = { ratio ->
                inferenceCoordinator.setGeneralChangeRatio(ratio)
            }
        )

        initializeInferenceCoordinator()
        createNotificationChannel()
    }

    private fun initializeInferenceCoordinator() {
        val coordinator = AiInferenceCoordinator(
            context = applicationContext,
            mainHandler = mainHandler,
            onDetectionUpdated = { label, confidence ->
                lastDetection = LastDetection(label, confidence)
                lastDetectedLabel = label
                lastConfidence = confidence
            }
        )
        inferenceCoordinator = coordinator
        coordinator.initialize { success ->
            if (inferenceCoordinator !== coordinator) return@initialize
            initError = coordinator.initError
            if (!success && isRunning) {
                val errorMsg = initError.ifBlank { "AI detectors failed to initialize" }
                Log.e(TAG, "Async detector initialization failed while service is running: $errorMsg")
                mainHandler.post {
                    Toast.makeText(applicationContext, errorMsg, Toast.LENGTH_LONG).show()
                    if (isRunning && inferenceCoordinator === coordinator) {
                        handleStopAction()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            Log.w(TAG, "onStartCommand received null intent/action; satisfying FGS contract and stopping cleanly")
            if (!isRunning) {
                stopSafelyWithFgsContract()
            }
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP) {
            handleStopAction()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            handleStartAction(intent)
        } else {
            Log.w(TAG, "onStartCommand received unknown action=$action; stopping cleanly")
            if (!isRunning) {
                stopSafelyWithFgsContract()
            }
        }

        return START_NOT_STICKY
    }

    private fun handleStartAction(intent: Intent) {
        if (isRunning) {
            Log.w(TAG, "Duplicate/reentrant ACTION_START ignored; already running")
            return
        }

        if (isStopping) {
            Log.i(TAG, "ACTION_START received while stopping; resetting state and re-initializing inference coordinator")
            isStopping = false
            initError = ""
            initializeInferenceCoordinator()
        }

        if (initError.isNotEmpty()) {
            Log.e(TAG, "Refusing START: detectors not ready: $initError")
            mainHandler.post {
                Toast.makeText(applicationContext, initError, Toast.LENGTH_LONG).show()
            }
            stopSafelyWithFgsContract()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            // Unconditionally stop any active or in-flight standalone A11y coordinator before starting projection
            HisnAccessibilityService.instance?.syncStandaloneAiState(false)
            startForegroundWithNotification()
            // Re-assert after isRunning=true so no concurrent init callback can start a second coordinator
            HisnAccessibilityService.instance?.syncStandaloneAiState(false)
            initMediaProjection(resultCode, resultData)
        } else {
            Log.e(TAG, "Invalid projection token parameters")
            stopSafelyWithFgsContract()
        }
    }

    private fun stopSafelyWithFgsContract() {
        try {
            startForegroundWithNotification(markRunning = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed startForeground fallback before stopping", e)
        }
        updateRunningState(false)
        isStopping = false
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed stopForeground fallback", e)
        }
        stopSelf()
    }

    private fun handleStopAction() {
        updateRunningState(false)
        isStopping = true

        streamManager.stop()
        inferenceCoordinator.stop()

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(markRunning: Boolean = true) {
        if (markRunning) {
            lastDetection = LastDetection()
            lastDetectedLabel = ""
            lastConfidence = 0f
        }

        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (markRunning) {
            updateRunningState(true)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            updateRunningState(false)
            isStopping = false
            stopSelf()
            return
        }

        try {
            val mp = mpManager.getMediaProjection(resultCode, resultData) ?: run {
                Log.e(TAG, "getMediaProjection returned null")
                updateRunningState(false)
                isStopping = false
                stopSelf()
                return
            }
            mediaProjection = mp

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system (e.g. screen lock on Android 15+)")
                    com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordShield(
                        context = applicationContext,
                        shieldId = "SCREEN_AI_PROJECTION",
                        isSuccess = false,
                        details = "MediaProjection stopped by system"
                    )
                    showResumeNotificationOnUnlock()
                    stopSelf()
                }
            }
            projectionCallback = callback
            mp.registerCallback(callback, mainHandler)

            // Guarantee mutual exclusion even if standalone coordinator was mid-initialization
            HisnAccessibilityService.instance?.syncStandaloneAiState(false)
            inferenceCoordinator.start()
            streamManager.start(mp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            updateRunningState(false)
            isStopping = false
            stopSelf()
        }
    }

    private fun showResumeNotificationOnUnlock() {
        try {
            val notifyIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_REQUEST_AI_TOGGLE", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                1002,
                notifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_shield)
                .setContentTitle(getString(R.string.ai_service_notification_title))
                .setContentText(getString(R.string.ai_service_resume_prompt))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(1002, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show resume notification: ${e.message}")
        }
    }

    private fun buildServiceNotification(): Notification {
        val stopIntent = Intent(this, ScreenAiProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ai_service_notification_title))
            .setContentText(getString(R.string.ai_service_notification_desc))
            .setSmallIcon(R.drawable.ic_ai_shield)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_ai_shield, getString(R.string.ai_service_action_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ai_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.ai_service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ScreenAiProjectionService onDestroy")
        updateRunningState(false)
        isStopping = false

        streamManager.stop()
        inferenceCoordinator.stop()

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            Log.d(TAG, "unregisterCallback non-critical", e)
        }
        projectionCallback = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
        mediaProjection = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
