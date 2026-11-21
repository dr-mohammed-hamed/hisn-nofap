package com.drmohammed.antiporn.ai.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.ai.core.inference.HalalifyGenderDetector
import com.drmohammed.antiporn.ai.core.pipeline.ContextualGatingWatcher
import com.drmohammed.antiporn.ai.core.tracking.KltMathHelper
import com.drmohammed.antiporn.ai.core.tracking.KltOpticalFlowTracker
import com.drmohammed.antiporn.ai.core.tracking.TrackedModestyBox
import com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates AI inference models (Halalify), optical flow tracking (KLT),
 * contextual app gating, and blur overlay rendering.
 */
class AiInferenceCoordinator(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onDetectionUpdated: (label: String, confidence: Float) -> Unit = { _, _ -> }
) {
    private var inferenceThread: HandlerThread? = null
    private var inferenceHandler: Handler? = null

    lateinit var kltTracker: KltOpticalFlowTracker
        private set
    lateinit var halalifyDetector: HalalifyGenderDetector
        private set
    lateinit var blurOverlayManager: ScreenBlurOverlayManager
        private set
    lateinit var contextualWatcher: ContextualGatingWatcher
        private set

    private val isHalalifyInferring = AtomicBoolean(false)
    private val isCaptureInFlight = AtomicBoolean(false)
    private var adaptiveIntervalMs: Long = BASE_ADAPTIVE_INTERVAL_MS
    private var lastAnchorCheckTimeMs: Long = 0L
    private var lastA11yScreenshotAttemptMs: Long = 0L
    private var consecutiveA11yFailures: Int = 0
    private var lastA11yFailureTimeMs: Long = 0L
    private var a11yStageIndex: Int = 0

    private val a11yCaptureRunnable = Runnable { triggerAdaptiveA11yCapture() }
    private val postScrollSettleRunnable = Runnable { executePostScrollSettleSweep() }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                if (contextualWatcher.isTargetAppForeground) {
                    triggerHeartbeatCheck()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat check error", e)
            } finally {
                if (isRunning) {
                    inferenceHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
    }

    private var inferenceBitmap: Bitmap? = null
    private var scratchPixels: IntArray? = null
    private var scratchRowBytes: ByteArray? = null

    @Volatile
    private var sceneGenerationId: Long = 0L

    @Volatile
    private var accumulatedScrollDx: Float = 0f

    @Volatile
    private var accumulatedScrollDy: Float = 0f

    private var tileStepIndex: Int = 0
    private var tileBitmap: Bitmap? = null
    private var scratchTilePixels: IntArray? = null

    private var lastDiagnosticLogMs: Long = 0L
    private var lastHalalifyIdleLogMs: Long = 0L

    private val drawnBoxesLock = Any()
    private val lastDrawnPixelBoxes = ArrayList<RectF>(32)

    @Volatile
    var initError: String = ""
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    fun setGeneralChangeRatio(ratio: Float) {
        if (::kltTracker.isInitialized) {
            kltTracker.lastGeneralChangeRatio = ratio
        }
    }

    fun initialize(onInitialized: (Boolean) -> Unit = {}) {
        val infThread = HandlerThread("AiInferenceWorker", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE)
        infThread.start()
        inferenceThread = infThread
        val handler = Handler(infThread.looper)
        inferenceHandler = handler

        kltTracker = KltOpticalFlowTracker()
        blurOverlayManager = ScreenBlurOverlayManager(context).apply {
            isKltTrackingActive = true
            onRegionsCleared = {
                synchronized(drawnBoxesLock) {
                    lastDrawnPixelBoxes.clear()
                }
            }
        }

        contextualWatcher = ContextualGatingWatcher(
            context,
            onStateChanged = { isTarget, _ ->
                consecutiveA11yFailures = 0
                if (!isTarget) {
                    inferenceHandler?.removeCallbacks(a11yCaptureRunnable)
                    inferenceHandler?.removeCallbacks(postScrollSettleRunnable)
                    isCaptureInFlight.set(false)
                    if (::kltTracker.isInitialized) kltTracker.flush()
                    if (::blurOverlayManager.isInitialized) blurOverlayManager.dismissFast()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        scheduleNextA11yCapture(0L)
                    }
                }
            }
        )
        contextualWatcher.start()

        handler.post {
            try {
                halalifyDetector = HalalifyGenderDetector(context)
                val halalifyOk = halalifyDetector.isInitialized
                if (!halalifyOk) {
                    initError = "Halalify initialization failed"
                    Log.e(TAG, "AI initialization failed: $initError")
                    mainHandler.post { onInitialized(false) }
                } else {
                    initError = ""
                    Log.i(TAG, "Halalify ready")
                    mainHandler.post { onInitialized(true) }
                }
            } catch (e: Exception) {
                initError = "AI initialization exception: ${e.message}"
                Log.e(TAG, "Failed to initialize detector", e)
                mainHandler.post { onInitialized(false) }
            }
        }
    }

    fun start() {
        isRunning = true
        activeInstance = this
        isHalalifyInferring.set(false)
        isCaptureInFlight.set(false)
        adaptiveIntervalMs = BASE_ADAPTIVE_INTERVAL_MS
        lastAnchorCheckTimeMs = 0L
        consecutiveA11yFailures = 0
        if (::kltTracker.isInitialized) kltTracker.flush()

        // بدء حلقة النبضات المستقلة Heartbeat كل 900ms
        inferenceHandler?.removeCallbacks(heartbeatRunnable)
        inferenceHandler?.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)

        // على أندرويد 14+، بدء حلقة الالتقاط التكيفية المستقلة فوراً عند وجود تطبيق مستهدف
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && contextualWatcher.isTargetAppForeground) {
            scheduleNextA11yCapture(0L)
        }
    }

    fun processFrame(image: Image, width: Int, height: Int) {
        if (!isRunning) return

        val now = SystemClock.elapsedRealtime()

        if (now - lastDiagnosticLogMs >= 2000L) {
            lastDiagnosticLogMs = now
            val fgPkg = contextualWatcher.currentForegroundPackage
            val isTarget = contextualWatcher.isTargetAppForeground
            Log.i(TAG, "[ScreenAi Scan Active] Pkg: $fgPkg (Target: $isTarget)")
        }

        if (!contextualWatcher.isTargetAppForeground) {
            if (::kltTracker.isInitialized) kltTracker.flush()
            if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions) {
                blurOverlayManager.dismissFast()
            }
            return
        }

        val plane = image.planes.firstOrNull() ?: return
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        // 1. Fast Path: KLT Optical Flow Tracking (60 FPS @ ~1.2ms)
        if (::kltTracker.isInitialized && kltTracker.hasActiveTracks()) {
            val trackedBoxes = kltTracker.update(
                rawBuffer = plane.buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                timestampMs = now
            )
            accumulatedScrollDx += kltTracker.lastGlobalDeltaX
            accumulatedScrollDy += kltTracker.lastGlobalDeltaY

            if (trackedBoxes.isNotEmpty()) {
                val (screenWidth, screenHeight) = getPhysicalScreenSize()
                val paddedBoxes = kltTracker.getPaddedBoxes()
                val overlayNeedsRedraw = !blurOverlayManager.hasActiveRegions || hasBoxesChangedSignificantly(paddedBoxes, screenWidth, screenHeight)
                if (overlayNeedsRedraw) {
                    blurOverlayManager.updateRegionsMapped(
                        rawRegions = paddedBoxes,
                        targetWidth = screenWidth,
                        targetHeight = screenHeight,
                        sourceWidth = 1,
                        sourceHeight = 1
                    )
                }
            } else {
                synchronized(drawnBoxesLock) {
                    lastDrawnPixelBoxes.clear()
                }
                blurOverlayManager.updateRegions(emptyList())
            }
        }

        // 2. Async Anchor Path: Clean A11y Screenshot (API 34+) OR 4-Stage Rotation (API < 34)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // فصل أندرويد 14+ بالكامل عن MediaProjection عبر حلقة الالتقاط التكيفية المستقلة
            val shouldTrigger = !isCaptureInFlight.get() &&
                    ((now - lastAnchorCheckTimeMs >= adaptiveIntervalMs) || (::kltTracker.isInitialized && !kltTracker.hasActiveTracks() && (now - lastAnchorCheckTimeMs >= 50L)))
            if (shouldTrigger) {
                scheduleNextA11yCapture(0L)
            }
        } else {
            val anchorInterval = ANCHOR_INTERVAL_MS
            val shouldTriggerAnchor = !isHalalifyInferring.get() &&
                    ((now - lastAnchorCheckTimeMs >= anchorInterval) || (::kltTracker.isInitialized && !kltTracker.hasActiveTracks() && (now - lastAnchorCheckTimeMs >= 50L)))

            if (shouldTriggerAnchor && isHalalifyInferring.compareAndSet(false, true)) {
                dispatchMediaProjectionAnchor(image, width, height, now)
            }
        }
    }

    private fun notifyDetectionUpdated(detections: List<com.drmohammed.antiporn.ai.core.model.HalalifyDetection>) {
        val topFemale = detections.filter { it.isFemale }.maxByOrNull { it.confidence }
            ?: detections.maxByOrNull { it.confidence }
        val label = if (topFemale != null) "female" else "clean"
        val maxConfidence = topFemale?.confidence ?: 0f
        mainHandler.post {
            try {
                onDetectionUpdated(label, maxConfidence)
            } catch (e: Exception) {
                Log.d(TAG, "Non-critical error in onDetectionUpdated: ${e.message}")
            }
        }
    }

    private fun runHalalifyOnCleanBitmap(
        cleanBitmap: Bitmap,
        windowBounds: Rect,
        captureGen: Long,
        captureTimestamp: Long,
        startScrollX: Float,
        startScrollY: Float,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (!isRunning || captureGen != sceneGenerationId) return
        if (!::halalifyDetector.isInitialized || !halalifyDetector.isInitialized) {
            Log.w(TAG, "Halalify detector not ready, skipping clean bitmap inference")
            return
        }
        val startHalalify = SystemClock.elapsedRealtime()

        val cleanAspect = cleanBitmap.height.toFloat() / cleanBitmap.width.toFloat().coerceAtLeast(1f)
        val shouldTileClean = cleanAspect >= 1.4f
        val a11yStage = if (shouldTileClean) (a11yStageIndex++ % 3) else 0

        val (inputToDetect, cropOffsetY, cropH) = when (a11yStage) {
            1 -> {
                val h = (cleanBitmap.height * 0.60f).toInt().coerceAtLeast(1)
                Triple(Bitmap.createBitmap(cleanBitmap, 0, 0, cleanBitmap.width, h), 0, h)
            }
            2 -> {
                val h = (cleanBitmap.height * 0.60f).toInt().coerceAtLeast(1)
                val topY = (cleanBitmap.height - h).coerceAtLeast(0)
                Triple(Bitmap.createBitmap(cleanBitmap, 0, topY, cleanBitmap.width, h), topY, h)
            }
            else -> {
                Triple(cleanBitmap, 0, cleanBitmap.height)
            }
        }

        val detections = try {
            halalifyDetector.detect(inputToDetect)
        } finally {
            if (inputToDetect !== cleanBitmap) {
                try { inputToDetect.recycle() } catch (_: Exception) {}
            }
        }
        notifyDetectionUpdated(detections)
        val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
        val femaleDetections = detections.filter { it.isFemale }

        if (captureGen != sceneGenerationId) return

        val dtX = accumulatedScrollDx - startScrollX
        val dtY = accumulatedScrollDy - startScrollY

        val (physicalW, physicalH) = getPhysicalScreenSize()
        val scaleX = frameWidth.toFloat() / physicalW.toFloat().coerceAtLeast(1f)
        val scaleY = frameHeight.toFloat() / physicalH.toFloat().coerceAtLeast(1f)

        val scaledWinLeft = windowBounds.left * scaleX
        val scaledWinTop = windowBounds.top * scaleY
        val scaledWinWidth = (windowBounds.right - windowBounds.left) * scaleX
        val scaledWinHeight = (windowBounds.bottom - windowBounds.top) * scaleY

        val mappedFullBoxes = femaleDetections.map { det ->
            val boxLeftNorm = det.boundingBox.left
            val boxRightNorm = det.boundingBox.right
            val boxTopNorm = (cropOffsetY + det.boundingBox.top * cropH) / cleanBitmap.height.toFloat()
            val boxBottomNorm = (cropOffsetY + det.boundingBox.bottom * cropH) / cleanBitmap.height.toFloat()

            val pxL = scaledWinLeft + (boxLeftNorm * scaledWinWidth) + dtX
            val pxT = scaledWinTop + (boxTopNorm * scaledWinHeight) + dtY
            val pxR = scaledWinLeft + (boxRightNorm * scaledWinWidth) + dtX
            val pxB = scaledWinTop + (boxBottomNorm * scaledWinHeight) + dtY

            val normL = (pxL / frameWidth).coerceIn(0f, 1f)
            val normT = (pxT / frameHeight).coerceIn(0f, 1f)
            val normR = (pxR / frameWidth).coerceIn(normL + 0.001f, 1f)
            val normB = (pxB / frameHeight).coerceIn(normT + 0.001f, 1f)
            TrackedModestyBox.createRectF(normL, normT, normR, normB)
        }

        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(mappedFullBoxes, 40f / frameHeight)

        val winNormL = (scaledWinLeft / frameWidth).coerceIn(0f, 1f)
        val winNormR = ((scaledWinLeft + scaledWinWidth) / frameWidth).coerceIn(winNormL + 0.001f, 1f)
        val tileWinTop = scaledWinTop + (cropOffsetY.toFloat() / cleanBitmap.height.toFloat()) * scaledWinHeight
        val tileWinBottom = scaledWinTop + ((cropOffsetY + cropH).toFloat() / cleanBitmap.height.toFloat()) * scaledWinHeight
        val winNormT = (tileWinTop / frameHeight).coerceIn(0f, 1f)
        val winNormB = (tileWinBottom / frameHeight).coerceIn(winNormT + 0.001f, 1f)
        val tileNormRect = TrackedModestyBox.createRectF(winNormL, winNormT, winNormR, winNormB)

        if (::kltTracker.isInitialized) {
            kltTracker.updateAnchorsCrossTile(
                detections = mergedBoxes,
                tileNormRect = tileNormRect,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMs = captureTimestamp,
                isCleanSource = true
            )
        }

        renderOverlayAfterAnchor(halalifyLatency)
    }

    private fun dispatchMediaProjectionAnchor(
        image: Image,
        width: Int,
        height: Int,
        now: Long
    ) {
        val isPortrait = height >= width
        val stageIdx = (tileStepIndex++) % 4

        if (stageIdx == 0) {
            // Stage 0: FullFrame Letterboxed
            val fullBitmapInstance = copyFrameToInferenceBitmap(image)
            if (fullBitmapInstance != null) {
                val captureTimestamp = now
                val captureGen = sceneGenerationId
                val startScrollX = accumulatedScrollDx
                val startScrollY = accumulatedScrollDy

                inferenceHandler?.post {
                    try {
                        if (!isRunning || captureGen != sceneGenerationId) return@post
                        if (!::halalifyDetector.isInitialized || !halalifyDetector.isInitialized) {
                            Log.w(TAG, "Halalify detector not ready, skipping full frame inference")
                            return@post
                        }
                        val startHalalify = SystemClock.elapsedRealtime()
                        val detections = halalifyDetector.detect(fullBitmapInstance)
                        notifyDetectionUpdated(detections)
                        val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
                        val femaleDetections = detections.filter { it.isFemale }

                        if (captureGen != sceneGenerationId) return@post

                        val dtX = accumulatedScrollDx - startScrollX
                        val dtY = accumulatedScrollDy - startScrollY

                        val mappedFullBoxes = femaleDetections.map { det ->
                            val pxL = det.boundingBox.left * width + dtX
                            val pxT = det.boundingBox.top * height + dtY
                            val pxR = det.boundingBox.right * width + dtX
                            val pxB = det.boundingBox.bottom * height + dtY

                            val normL = (pxL / width).coerceIn(0f, 1f)
                            val normT = (pxT / height).coerceIn(0f, 1f)
                            val normR = (pxR / width).coerceIn(normL + 0.001f, 1f)
                            val normB = (pxB / height).coerceIn(normT + 0.001f, 1f)
                            TrackedModestyBox.createRectF(normL, normT, normR, normB)
                        }

                        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(mappedFullBoxes, 40f / height)
                        val tileNormRect = TrackedModestyBox.createRectF(0f, 0f, 1f, 1f)

                        if (::kltTracker.isInitialized) {
                            kltTracker.updateAnchorsCrossTile(
                                detections = mergedBoxes,
                                tileNormRect = tileNormRect,
                                frameWidth = width,
                                frameHeight = height,
                                timestampMs = captureTimestamp,
                                isCleanSource = false
                            )
                        }

                        renderOverlayAfterAnchor(halalifyLatency)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in FullFrame AI inference", e)
                    } finally {
                        lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                        isHalalifyInferring.set(false)
                    }
                }
            } else {
                isHalalifyInferring.set(false)
            }
        } else {
            // Stages 1, 2, 3: Slices (Top, Mid, Bottom / Left, Center, Right)
            val sliceIdx = stageIdx - 1
            val tileW = 416.coerceAtMost(width)
            val tileH = 416.coerceAtMost(height)

            val tileOffsetX: Int
            val tileOffsetY: Int
            if (isPortrait) {
                tileOffsetX = 0
                val maxOffsetY = (height - tileH).coerceAtLeast(0)
                tileOffsetY = when (sliceIdx) {
                    0 -> 0 // Top
                    1 -> maxOffsetY / 2 // Middle
                    else -> maxOffsetY // Bottom
                }
            } else {
                tileOffsetY = 0
                val maxOffsetX = (width - tileW).coerceAtLeast(0)
                tileOffsetX = when (sliceIdx) {
                    0 -> 0 // Left
                    1 -> maxOffsetX / 2 // Center
                    else -> maxOffsetX // Right
                }
            }

            val tileBitmapInstance = copyTileToBitmap(image, tileOffsetX, tileOffsetY, tileW, tileH)
            if (tileBitmapInstance != null) {
                val captureTimestamp = now
                val captureGen = sceneGenerationId
                val startScrollX = accumulatedScrollDx
                val startScrollY = accumulatedScrollDy

                inferenceHandler?.post {
                    try {
                        if (!isRunning || captureGen != sceneGenerationId) return@post
                        if (!::halalifyDetector.isInitialized || !halalifyDetector.isInitialized) {
                            Log.w(TAG, "Halalify detector not ready, skipping tile inference")
                            return@post
                        }
                        val startHalalify = SystemClock.elapsedRealtime()
                        val detections = halalifyDetector.detect(tileBitmapInstance)
                        notifyDetectionUpdated(detections)
                        val halalifyLatency = SystemClock.elapsedRealtime() - startHalalify
                        val femaleDetections = detections.filter { it.isFemale }

                        if (captureGen != sceneGenerationId) return@post

                        val dtX = accumulatedScrollDx - startScrollX
                        val dtY = accumulatedScrollDy - startScrollY

                        val mappedFullBoxes = femaleDetections.map { det ->
                            val pxL = det.boundingBox.left * tileW + tileOffsetX + dtX
                            val pxT = det.boundingBox.top * tileH + tileOffsetY + dtY
                            val pxR = det.boundingBox.right * tileW + tileOffsetX + dtX
                            val pxB = det.boundingBox.bottom * tileH + tileOffsetY + dtY

                            val normL = (pxL / width).coerceIn(0f, 1f)
                            val normT = (pxT / height).coerceIn(0f, 1f)
                            val normR = (pxR / width).coerceIn(normL + 0.001f, 1f)
                            val normB = (pxB / height).coerceIn(normT + 0.001f, 1f)
                            TrackedModestyBox.createRectF(normL, normT, normR, normB)
                        }

                        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(mappedFullBoxes, 40f / height)

                        val tileNormRect = TrackedModestyBox.createRectF(
                            tileOffsetX.toFloat() / width,
                            tileOffsetY.toFloat() / height,
                            (tileOffsetX + tileW).toFloat() / width,
                            (tileOffsetY + tileH).toFloat() / height
                        )

                        if (::kltTracker.isInitialized) {
                            kltTracker.updateAnchorsCrossTile(
                                detections = mergedBoxes,
                                tileNormRect = tileNormRect,
                                frameWidth = width,
                                frameHeight = height,
                                timestampMs = captureTimestamp,
                                isCleanSource = false
                            )
                        }

                        renderOverlayAfterAnchor(halalifyLatency)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in Tile AI inference", e)
                    } finally {
                        lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                        isHalalifyInferring.set(false)
                    }
                }
            } else {
                isHalalifyInferring.set(false)
            }
        }
    }

    private fun renderOverlayAfterAnchor(halalifyLatency: Long) {
        if (::kltTracker.isInitialized && kltTracker.hasActiveTracks()) {
            val paddedBoxes = kltTracker.getPaddedBoxes()
            val (screenWidth, screenHeight) = getPhysicalScreenSize()
            val overlayNeedsRedraw = !blurOverlayManager.hasActiveRegions || hasBoxesChangedSignificantly(paddedBoxes, screenWidth, screenHeight)
            if (overlayNeedsRedraw) {
                blurOverlayManager.updateRegionsMapped(
                    rawRegions = paddedBoxes,
                    targetWidth = screenWidth,
                    targetHeight = screenHeight,
                    sourceWidth = 1,
                    sourceHeight = 1
                )
            }
        } else if (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()) {
            synchronized(drawnBoxesLock) {
                lastDrawnPixelBoxes.clear()
            }
            blurOverlayManager.updateRegions(emptyList())
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastHalalifyIdleLogMs >= 2000L) {
                lastHalalifyIdleLogMs = nowMs
                val rawConfPercent = (halalifyDetector.lastMaxRawFemaleConfidence * 100).toInt()
                Log.i(TAG, "[Halalify Shield] Idle | Max Raw Conf: $rawConfPercent% | Latency: ${halalifyLatency}ms")
            }
        }
    }

    private fun hasBoxesChangedSignificantly(
        newBoxes: List<RectF>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        synchronized(drawnBoxesLock) {
            if (newBoxes.size != lastDrawnPixelBoxes.size) {
                updateLastDrawnPixelBoxes(newBoxes, screenWidth, screenHeight)
                return true
            }
            val sw = screenWidth.toFloat()
            val sh = screenHeight.toFloat()
            for (i in newBoxes.indices) {
                val nb = newBoxes[i]
                val ob = lastDrawnPixelBoxes[i]
                val nl = nb.left * sw
                val nt = nb.top * sh
                val nr = nb.right * sw
                val nb_ = nb.bottom * sh

                if (kotlin.math.abs(nl - ob.left) >= 1.0f ||
                    kotlin.math.abs(nt - ob.top) >= 1.0f ||
                    kotlin.math.abs(nr - ob.right) >= 1.0f ||
                    kotlin.math.abs(nb_ - ob.bottom) >= 1.0f) {
                    updateLastDrawnPixelBoxes(newBoxes, screenWidth, screenHeight)
                    return true
                }
            }
            return false
        }
    }

    private fun updateLastDrawnPixelBoxes(
        boxes: List<RectF>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        synchronized(drawnBoxesLock) {
            val sw = screenWidth.toFloat()
            val sh = screenHeight.toFloat()
            while (lastDrawnPixelBoxes.size < boxes.size) {
                lastDrawnPixelBoxes.add(RectF())
            }
            while (lastDrawnPixelBoxes.size > boxes.size) {
                lastDrawnPixelBoxes.removeAt(lastDrawnPixelBoxes.size - 1)
            }
            for (i in boxes.indices) {
                val src = boxes[i]
                val dst = lastDrawnPixelBoxes[i]
                dst.left = src.left * sw
                dst.top = src.top * sh
                dst.right = src.right * sw
                dst.bottom = src.bottom * sh
            }
        }
    }

    fun onStaticScreen() {
        if (::kltTracker.isInitialized && !kltTracker.hasActiveTracks()) {
            accumulatedScrollDx = 0f
            accumulatedScrollDy = 0f
        }
        if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions) {
            blurOverlayManager.retainActiveRegions()
        }
    }

    fun onHardSceneCut() {
        sceneGenerationId++
        accumulatedScrollDx = 0f
        accumulatedScrollDy = 0f
        consecutiveA11yFailures = 0
        lastAnchorCheckTimeMs = 0L
        synchronized(drawnBoxesLock) {
            lastDrawnPixelBoxes.clear()
        }
        if (::kltTracker.isInitialized) {
            kltTracker.flush()
        }
        if (::blurOverlayManager.isInitialized) {
            blurOverlayManager.dismissFast()
        }
    }

    /**
     * يُستدعى عند حدوث تمرير في التطبيق النشط (TYPE_VIEW_SCROLLED).
     * يعيد ضبط مؤقت الاستقرار Post-Scroll Settle Sweep ليعمل بعد 150ms من لحظة توقف التمرير.
     */
    fun onViewScrolled() {
        if (!isRunning || !contextualWatcher.isTargetAppForeground) return
        inferenceHandler?.removeCallbacks(postScrollSettleRunnable)
        inferenceHandler?.postDelayed(postScrollSettleRunnable, POST_SCROLL_SETTLE_DELAY_MS)
    }

    /**
     * فحص استقرار ما بعد التمرير (Post-Scroll Settle Sweep):
     * يعمل بعد 150ms من توقف التمرير ويفحص جميع الشرائح المتبقية للشاشة المستقرة بغض النظر عن وصول إطارات جديدة.
     */
    private fun executePostScrollSettleSweep() {
        if (!isRunning || !contextualWatcher.isTargetAppForeground) return

        val a11y = HisnAccessibilityService.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && a11y != null && a11y.isCleanWindowCaptureSupported()) {
            if (isCaptureInFlight.compareAndSet(false, true)) {
                val captureTimestamp = SystemClock.elapsedRealtime()
                val captureGen = sceneGenerationId
                val startScrollX = accumulatedScrollDx
                val startScrollY = accumulatedScrollDy

                a11y.captureCleanWindowBitmap { cleanBitmap, windowBounds, _ ->
                    try {
                        if (cleanBitmap != null && isRunning && captureGen == sceneGenerationId) {
                            val (physW, physH) = getPhysicalScreenSize()
                            runHalalifySweepOnCleanBitmap(
                                cleanBitmap = cleanBitmap,
                                windowBounds = windowBounds,
                                captureGen = captureGen,
                                captureTimestamp = captureTimestamp,
                                startScrollX = startScrollX,
                                startScrollY = startScrollY,
                                frameWidth = physW,
                                frameHeight = physH
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in post-scroll settle sweep", e)
                    } finally {
                        try { cleanBitmap?.recycle() } catch (_: Exception) {}
                        isCaptureInFlight.set(false)
                        lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        } else {
            onStaticScreen()
        }
    }

    private fun runHalalifySweepOnCleanBitmap(
        cleanBitmap: Bitmap,
        windowBounds: Rect,
        captureGen: Long,
        captureTimestamp: Long,
        startScrollX: Float,
        startScrollY: Float,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (!isRunning || captureGen != sceneGenerationId) return
        if (!::halalifyDetector.isInitialized || !halalifyDetector.isInitialized) return

        val cleanAspect = cleanBitmap.height.toFloat() / cleanBitmap.width.toFloat().coerceAtLeast(1f)
        val shouldTileClean = cleanAspect >= 1.4f

        val stagesToSweep = if (shouldTileClean) listOf(0, 1, 2) else listOf(0)
        val allFemaleDetections = mutableListOf<com.drmohammed.antiporn.ai.core.model.HalalifyDetection>()

        val (physicalW, physicalH) = getPhysicalScreenSize()
        val scaleX = frameWidth.toFloat() / physicalW.toFloat().coerceAtLeast(1f)
        val scaleY = frameHeight.toFloat() / physicalH.toFloat().coerceAtLeast(1f)

        val scaledWinLeft = windowBounds.left * scaleX
        val scaledWinTop = windowBounds.top * scaleY
        val scaledWinWidth = (windowBounds.right - windowBounds.left) * scaleX
        val scaledWinHeight = (windowBounds.bottom - windowBounds.top) * scaleY

        val dtX = accumulatedScrollDx - startScrollX
        val dtY = accumulatedScrollDy - startScrollY

        val allMappedBoxes = mutableListOf<RectF>()

        for (stage in stagesToSweep) {
            val (inputToDetect, cropOffsetY, cropH) = when (stage) {
                1 -> {
                    val h = (cleanBitmap.height * 0.60f).toInt().coerceAtLeast(1)
                    Triple(Bitmap.createBitmap(cleanBitmap, 0, 0, cleanBitmap.width, h), 0, h)
                }
                2 -> {
                    val h = (cleanBitmap.height * 0.60f).toInt().coerceAtLeast(1)
                    val topY = (cleanBitmap.height - h).coerceAtLeast(0)
                    Triple(Bitmap.createBitmap(cleanBitmap, 0, topY, cleanBitmap.width, h), topY, h)
                }
                else -> {
                    Triple(cleanBitmap, 0, cleanBitmap.height)
                }
            }

            try {
                val detections = halalifyDetector.detect(inputToDetect)
                allFemaleDetections.addAll(detections.filter { it.isFemale })

                val boxes = detections.filter { it.isFemale }.map { det ->
                    val boxLeftNorm = det.boundingBox.left
                    val boxRightNorm = det.boundingBox.right
                    val boxTopNorm = (cropOffsetY + det.boundingBox.top * cropH) / cleanBitmap.height.toFloat()
                    val boxBottomNorm = (cropOffsetY + det.boundingBox.bottom * cropH) / cleanBitmap.height.toFloat()

                    val pxL = scaledWinLeft + (boxLeftNorm * scaledWinWidth) + dtX
                    val pxT = scaledWinTop + (boxTopNorm * scaledWinHeight) + dtY
                    val pxR = scaledWinLeft + (boxRightNorm * scaledWinWidth) + dtX
                    val pxB = scaledWinTop + (boxBottomNorm * scaledWinHeight) + dtY

                    val normL = (pxL / frameWidth).coerceIn(0f, 1f)
                    val normT = (pxT / frameHeight).coerceIn(0f, 1f)
                    val normR = (pxR / frameWidth).coerceIn(normL + 0.001f, 1f)
                    val normB = (pxB / frameHeight).coerceIn(normT + 0.001f, 1f)
                    TrackedModestyBox.createRectF(normL, normT, normR, normB)
                }
                allMappedBoxes.addAll(boxes)
            } finally {
                if (inputToDetect !== cleanBitmap) {
                    try { inputToDetect.recycle() } catch (_: Exception) {}
                }
            }
        }

        notifyDetectionUpdated(allFemaleDetections)

        val mergedBoxes = KltMathHelper.mergeBoxesSpatially(allMappedBoxes, 40f / frameHeight)
        val tileNormRect = TrackedModestyBox.createRectF(0f, 0f, 1f, 1f)

        if (::kltTracker.isInitialized) {
            kltTracker.updateAnchorsCrossTile(
                detections = mergedBoxes,
                tileNormRect = tileNormRect,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMs = captureTimestamp,
                isCleanSource = true
            )
        }

        renderOverlayAfterAnchor(0L)
        blurOverlayManager.retainActiveRegions()
    }

    /**
     * جدولة الالتقاط التكيفي المستقل لأندرويد 14+.
     */
    fun scheduleNextA11yCapture(delayMs: Long) {
        if (!isRunning || !contextualWatcher.isTargetAppForeground) return
        inferenceHandler?.removeCallbacks(a11yCaptureRunnable)
        if (delayMs <= 0L) {
            inferenceHandler?.post(a11yCaptureRunnable)
        } else {
            inferenceHandler?.postDelayed(a11yCaptureRunnable, delayMs)
        }
    }

    private fun triggerAdaptiveA11yCapture() {
        if (!isRunning || !contextualWatcher.isTargetAppForeground) return

        val a11y = HisnAccessibilityService.instance ?: return
        if (!a11y.isCleanWindowCaptureSupported()) return

        if (!isCaptureInFlight.compareAndSet(false, true)) {
            return
        }

        val captureTimestamp = SystemClock.elapsedRealtime()
        val captureGen = sceneGenerationId
        val startScrollX = accumulatedScrollDx
        val startScrollY = accumulatedScrollDy

        a11y.captureCleanWindowBitmap { cleanBitmap, windowBounds, errorCode ->
            try {
                if (cleanBitmap != null) {
                    // نجاح اللقطة: إعادة الفاصل للقيمة الأساسية 380ms
                    adaptiveIntervalMs = BASE_ADAPTIVE_INTERVAL_MS
                    consecutiveA11yFailures = 0

                    if (isRunning && captureGen == sceneGenerationId) {
                        val (physW, physH) = getPhysicalScreenSize()
                        runHalalifyOnCleanBitmap(
                            cleanBitmap = cleanBitmap,
                            windowBounds = windowBounds,
                            captureGen = captureGen,
                            captureTimestamp = captureTimestamp,
                            startScrollX = startScrollX,
                            startScrollY = startScrollY,
                            frameWidth = physW,
                            frameHeight = physH
                        )
                    }
                } else {
                    if (errorCode == 3) {
                        // تمديد الفاصل لـ 550ms عند الخطأ رقم 3
                        adaptiveIntervalMs = CONSTRAINED_ADAPTIVE_INTERVAL_MS
                        Log.d(TAG, "A11y capture constrained (error 3), adaptive interval increased to ${adaptiveIntervalMs}ms")
                    } else {
                        // تجاهل أخطاء FLAG_SECURE بصمت تام دون السقوط إلى MediaProjection
                        Log.d(TAG, "A11y capture null (code $errorCode), ignoring silently")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in adaptive A11y capture", e)
            } finally {
                try { cleanBitmap?.recycle() } catch (_: Exception) {}
                isCaptureInFlight.set(false)
                lastAnchorCheckTimeMs = SystemClock.elapsedRealtime()
                if (isRunning && contextualWatcher.isTargetAppForeground) {
                    scheduleNextA11yCapture(adaptiveIntervalMs)
                }
            }
        }
    }

    private fun triggerHeartbeatCheck() {
        val now = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val a11y = HisnAccessibilityService.instance
            if (a11y != null && a11y.isCleanWindowCaptureSupported()) {
                if (!isCaptureInFlight.get() && !isHalalifyInferring.get()) {
                    if (now - lastAnchorCheckTimeMs >= (HEARTBEAT_INTERVAL_MS - 100L)) {
                        scheduleNextA11yCapture(0L)
                    }
                }
            }
        } else {
            if (::blurOverlayManager.isInitialized && blurOverlayManager.hasActiveRegions) {
                blurOverlayManager.retainActiveRegions()
            }
        }
    }

    private fun copyTileToBitmap(
        image: Image,
        offsetX: Int,
        offsetY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        var bitmap = tileBitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width != tileWidth || bitmap.height != tileHeight) {
            try { bitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            bitmap = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
            tileBitmap = bitmap
        }

        val requiredPixelCount = tileWidth * tileHeight
        var pixels = scratchTilePixels
        if (pixels == null || pixels.size != requiredPixelCount) {
            pixels = IntArray(requiredPixelCount)
            scratchTilePixels = pixels
        }

        val requiredRowBytes = tileWidth * pixelStride
        var rowBytes = scratchRowBytes
        if (rowBytes == null || rowBytes.size < requiredRowBytes) {
            rowBytes = ByteArray(requiredRowBytes.coerceAtLeast(rowStride))
            scratchRowBytes = rowBytes
        }

        val savedPos = buffer.position()
        try {
            for (ty in 0 until tileHeight) {
                val fullY = offsetY + ty
                val startPos = fullY * rowStride + offsetX * pixelStride
                buffer.position(startPos)
                buffer.get(rowBytes, 0, requiredRowBytes)
                val outRowOffset = ty * tileWidth
                for (tx in 0 until tileWidth) {
                    val px = tx * pixelStride
                    val r = rowBytes[px].toInt() and 0xFF
                    val g = rowBytes[px + 1].toInt() and 0xFF
                    val b = rowBytes[px + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) (rowBytes[px + 3].toInt() and 0xFF) else 0xFF
                    pixels[outRowOffset + tx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, tileWidth, 0, 0, tileWidth, tileHeight)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy tile to inference bitmap", e)
            return null
        } finally {
            try { buffer.position(savedPos) } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
        }
    }

    private fun copyFrameToInferenceBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val requiredPixelCount = width * height
        val requiredRowBytes = width * pixelStride

        var pixels = scratchPixels
        if (pixels == null || pixels.size != requiredPixelCount) {
            pixels = IntArray(requiredPixelCount)
            scratchPixels = pixels
        }

        var rowBytes = scratchRowBytes
        if (rowBytes == null || rowBytes.size < requiredRowBytes) {
            rowBytes = ByteArray(requiredRowBytes)
            scratchRowBytes = rowBytes
        }

        var bitmap = inferenceBitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width != width || bitmap.height != height) {
            try { bitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            inferenceBitmap = bitmap
        }

        val savedPos = buffer.position()
        try {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(rowBytes, 0, requiredRowBytes)
                val rowOffset = y * width
                for (x in 0 until width) {
                    val px = x * pixelStride
                    val r = rowBytes[px].toInt() and 0xFF
                    val g = rowBytes[px + 1].toInt() and 0xFF
                    val b = rowBytes[px + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) (rowBytes[px + 3].toInt() and 0xFF) else 0xFF
                    pixels[rowOffset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy frame to inference bitmap", e)
            return null
        } finally {
            try { buffer.position(savedPos) } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
        }
    }

    fun stop() {
        isRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }
        isHalalifyInferring.set(false)
        isCaptureInFlight.set(false)

        val handler = inferenceHandler
        handler?.removeCallbacks(a11yCaptureRunnable)
        handler?.removeCallbacks(postScrollSettleRunnable)
        handler?.removeCallbacks(heartbeatRunnable)

        if (::contextualWatcher.isInitialized) {
            contextualWatcher.stop()
        }

        if (::kltTracker.isInitialized) {
            kltTracker.flush()
        }

        if (::blurOverlayManager.isInitialized) {
            try {
                blurOverlayManager.detachOverlay()
                blurOverlayManager.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing blurOverlayManager", e)
            }
        }

        val worker = inferenceThread
        inferenceThread = null
        inferenceHandler = null

        if (worker != null && worker.isAlive && handler != null) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                try { if (::halalifyDetector.isInitialized) halalifyDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing halalify", e) }
                try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
                try { tileBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
                inferenceBitmap = null
                tileBitmap = null
                scratchPixels = null
                scratchTilePixels = null
                scratchRowBytes = null
                try { worker.quitSafely() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            }
        } else {
            try { if (::halalifyDetector.isInitialized) halalifyDetector.close() } catch (e: Exception) { Log.e(TAG, "Error closing halalify", e) }
            try { inferenceBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            try { tileBitmap?.recycle() } catch (e: Exception) { Log.d(TAG, "Non-critical: ${e.message}") }
            inferenceBitmap = null
            tileBitmap = null
            scratchPixels = null
            scratchTilePixels = null
            scratchRowBytes = null
        }
    }

    private fun getPhysicalScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else if (wm != null) {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(dm)
            Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        } else {
            val dm = context.resources.displayMetrics
            Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        }
    }

    companion object {
        private const val TAG = "AiInferenceCoord"
        const val ANCHOR_INTERVAL_MS = 200L
        const val BASE_ADAPTIVE_INTERVAL_MS = 380L
        const val CONSTRAINED_ADAPTIVE_INTERVAL_MS = 550L
        const val POST_SCROLL_SETTLE_DELAY_MS = 150L
        const val HEARTBEAT_INTERVAL_MS = 900L

        @Volatile
        var activeInstance: AiInferenceCoordinator? = null
            private set
    }
}
