package com.drmohammed.antiporn.ui.control.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.BottomSheetQrScannerBinding
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.drmohammed.antiporn.ui.common.showSafely
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern Material 3 BottomSheet dialog providing real-time camera scanning of
 * Self-Control barrier QR codes using CameraX, ML Kit Barcode Scanning, and ViewBinding.
 */
class QrScannerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQrScannerBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("QrScannerBottomSheet binding accessed outside view lifecycle")

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraExecutor: java.util.concurrent.Executor? = null
    private var barcodeScanner: BarcodeScanner? = null

    private val isCodeProcessing = AtomicBoolean(false)
    private var isTorchOn = false
    private var countdownJob: Job? = null
    private var defaultStatusColor: Int = Color.GRAY

    private val qrManager by lazy {
        SelfControlQrManager.getInstance(requireContext())
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (_binding == null) return@registerForActivityResult
        if (granted) {
            binding.layoutPermissionFallback.isVisible = false
            startCamera()
        } else {
            binding.layoutPermissionFallback.isVisible = true
            binding.tvScanStatus.text = getString(R.string.qr_scan_camera_permission_required)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetQrScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)
        isTorchOn = savedInstanceState?.getBoolean(KEY_IS_TORCH_ON, false) ?: false

        defaultStatusColor = binding.tvScanStatus.currentTextColor
        cameraExecutor = Dispatchers.Default.asExecutor()

        try {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            barcodeScanner = BarcodeScanning.getClient(options)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize BarcodeScanner client")
            barcodeScanner = null
            binding.tvScanStatus.text = getString(R.string.qr_scan_error_init)
            binding.tvScanStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
        }

        binding.btnCloseScanner.setOnClickListener {
            dismiss()
        }

        binding.btnToggleTorch.setOnClickListener {
            toggleTorch()
        }

        binding.btnGrantPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        childFragmentManager.setFragmentResultListener(REQ_EMERGENCY_CONFIRM, viewLifecycleOwner) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                val started = qrManager.requestEmergencyDelay()
                if (isAdded && _binding != null) {
                    refreshEmergencyDelayState()
                    if (!started) {
                        binding.tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_trusted_time_required)
                        android.widget.Toast.makeText(
                            requireContext().applicationContext,
                            R.string.qr_emergency_trusted_time_required,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        setupEmergencyBypassUi()

        if (barcodeScanner == null) {
            binding.layoutPermissionFallback.isVisible = false
        } else if (hasCameraPermission()) {
            binding.layoutPermissionFallback.isVisible = false
            startCamera()
        } else {
            binding.layoutPermissionFallback.isVisible = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val context = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize CameraX provider")
                if (isAdded) {
                    _binding?.tvScanStatus?.text = getString(R.string.qr_scan_camera_permission_required)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        if (!isAdded || _binding == null) {
            try {
                provider.unbindAll()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unbind failed during detached bindCameraUseCases")
            }
            return
        }

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = binding.previewViewQrScanner.surfaceProvider
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val executor = cameraExecutor ?: return
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            updateTorchAvailability()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Use case binding failed")
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isCodeProcessing.get()) {
            imageProxy.close()
            return
        }

        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = barcodeScanner ?: run {
                imageProxy.close()
                return
            }

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            handleScannedToken(rawValue)
                            break
                        }
                    }
                }
                .addOnFailureListener { error ->
                    Timber.tag(TAG).d(error, "Barcode analysis failed")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Exception during barcode image processing")
            imageProxy.close()
        }
    }

    private fun handleScannedToken(token: String) {
        if (!isCodeProcessing.compareAndSet(false, true)) {
            return
        }

        val currentActivity = activity
        if (currentActivity == null || !isAdded || _binding == null) {
            isCodeProcessing.set(false)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val isValid = withContext(Dispatchers.Default) {
                qrManager.verifyToken(token)
            }
            if (!isAdded || _binding == null) {
                isCodeProcessing.set(false)
                return@launch
            }

            if (isValid) {
                triggerHapticFeedback()
                try {
                    cameraProvider?.unbindAll()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error unbinding camera on successful scan")
                }
                binding.tvScanStatus.text = getString(R.string.qr_scan_success)
                binding.tvScanStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(400)
                    if (!isAdded || isRemoving) return@launch
                    val reqKey = arguments?.getString(EXTRA_REQUEST_KEY)
                    val payload = arguments?.getString(EXTRA_PAYLOAD)
                    if (reqKey != null) {
                        parentFragmentManager.setFragmentResult(
                            reqKey,
                            Bundle().apply {
                                putBoolean(RESULT_KEY_VERIFIED, true)
                                putString(RESULT_KEY_SCANNED_TOKEN, token)
                                if (payload != null) putString(RESULT_KEY_PAYLOAD, payload)
                            }
                        )
                    }
                    dismissAllowingStateLoss()
                }
            } else {
                binding.tvScanStatus.text = getString(R.string.qr_scan_invalid)
                binding.tvScanStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1500)
                    if (isAdded && _binding != null) {
                        binding.tvScanStatus.text = getString(R.string.qr_scan_aim_camera)
                        binding.tvScanStatus.setTextColor(defaultStatusColor)
                        isCodeProcessing.set(false)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_TORCH_ON, isTorchOn)
    }

    private fun updateTorchAvailability() {
        if (_binding == null) return
        val cam = camera
        val hasFlash = cam?.cameraInfo?.hasFlashUnit() == true
        binding.btnToggleTorch.isVisible = hasFlash
        if (hasFlash && isTorchOn) {
            cam?.cameraControl?.enableTorch(true)
        }
        binding.btnToggleTorch.setIconResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        binding.btnToggleTorch.contentDescription = getString(if (isTorchOn) R.string.qr_torch_off else R.string.qr_torch_on)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit() && _binding != null) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            binding.btnToggleTorch.setIconResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            binding.btnToggleTorch.contentDescription = getString(if (isTorchOn) R.string.qr_torch_off else R.string.qr_torch_on)
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerHapticFeedback() {
        try {
            val context = context ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator?.vibrate(80)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Haptic vibration failed")
        }
    }

    // --- Emergency Delay Handling ---

    private fun setupEmergencyBypassUi() {
        if (_binding == null) return
        if (!qrManager.isEmergencyDelayEnabled()) {
            binding.layoutEmergencyBypass.isVisible = false
            return
        }

        binding.layoutEmergencyBypass.isVisible = true
        refreshEmergencyDelayState()

        countdownJob?.cancel()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                if (isAdded && _binding != null) {
                    refreshEmergencyDelayState()
                }
            }
        }
    }

    private fun refreshEmergencyDelayState() {
        if (_binding == null) return
        val isRequested = qrManager.isEmergencyDelayRequested()
        val delayHours = qrManager.getEmergencyDelayHours()

        if (!isRequested) {
            binding.tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_confirm_message, delayHours)
            binding.btnEmergencyBypass.text = getString(R.string.qr_emergency_bypass_action, delayHours)
            binding.btnEmergencyBypass.setOnClickListener {
                showEmergencyConfirmationDialog(delayHours)
            }
        } else {
            val isElapsed = qrManager.isEmergencyDelayActiveAndElapsed()
            if (isElapsed) {
                binding.tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_bypass_ready)
                binding.btnEmergencyBypass.text = getString(R.string.qr_emergency_bypass_ready)
                binding.btnEmergencyBypass.setOnClickListener {
                    if (!isAdded || isRemoving) return@setOnClickListener
                    qrManager.completeEmergencyUnlock()
                    android.widget.Toast.makeText(
                        requireContext().applicationContext,
                        R.string.qr_emergency_unlock_completed_toast,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    val reqKey = arguments?.getString(EXTRA_REQUEST_KEY)
                    val payload = arguments?.getString(EXTRA_PAYLOAD)
                    if (reqKey != null) {
                        parentFragmentManager.setFragmentResult(
                            reqKey,
                            Bundle().apply {
                                putBoolean(RESULT_KEY_EMERGENCY_BYPASS, true)
                                if (payload != null) putString(RESULT_KEY_PAYLOAD, payload)
                            }
                        )
                    }
                    dismissAllowingStateLoss()
                }
            } else {
                val remainingMs = qrManager.getEmergencyDelayRemainingMs()
                val totalMinutes = (remainingMs / 1000L / 60L).coerceAtLeast(1)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                val formattedTime = if (hours > 0) {
                    getString(R.string.qr_emergency_time_hours_minutes, hours.toInt(), minutes.toInt())
                } else {
                    getString(R.string.qr_emergency_time_minutes, minutes.toInt())
                }

                binding.tvEmergencyBypassInfo.text = getString(R.string.qr_emergency_bypass_active_format, formattedTime)
                binding.btnEmergencyBypass.text = getString(R.string.qr_emergency_cancel)
                binding.btnEmergencyBypass.setOnClickListener {
                    qrManager.cancelEmergencyDelay()
                    refreshEmergencyDelayState()
                }
            }
        }
    }

    private fun showEmergencyConfirmationDialog(hours: Int) {
        childFragmentManager.showSafely(TAG_EMERGENCY_CONFIRM) {
            ConfirmActionDialogFragment.newInstance(
                requestKey = REQ_EMERGENCY_CONFIRM,
                titleRes = R.string.qr_emergency_confirm_title,
                messageText = getString(R.string.qr_emergency_confirm_message, hours),
                positiveRes = android.R.string.ok,
                negativeRes = android.R.string.cancel
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownJob?.cancel()
        countdownJob = null
        isCodeProcessing.set(false)

        try {
            cameraProvider?.unbindAll()
            camera = null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Camera unbind failed")
        }

        try {
            barcodeScanner?.close()
            barcodeScanner = null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Barcode scanner close failed")
        }

        cameraExecutor = null
        _binding = null
    }

    companion object {
        const val TAG = "QrScannerBottomSheet"
        const val EXTRA_REQUEST_KEY = "extra_request_key"
        const val EXTRA_PAYLOAD = "extra_payload"
        const val RESULT_KEY_VERIFIED = "result_key_verified"
        const val RESULT_KEY_EMERGENCY_BYPASS = "result_key_emergency_bypass"
        const val RESULT_KEY_PAYLOAD = "result_key_payload"
        private const val KEY_IS_TORCH_ON = "key_is_torch_on"
        private const val REQ_EMERGENCY_CONFIRM = "req_qr_emergency_confirm"
        private const val TAG_EMERGENCY_CONFIRM = "tag_qr_emergency_confirm"
        // main: remote Fortress unlock reads the scanned token from this result key.
        const val RESULT_KEY_SCANNED_TOKEN = "result_key_scanned_token"

        fun newInstance(requestKey: String? = null, payload: String? = null): QrScannerBottomSheet {
            return QrScannerBottomSheet().apply {
                if (requestKey != null || payload != null) {
                    arguments = Bundle().apply {
                        if (requestKey != null) putString(EXTRA_REQUEST_KEY, requestKey)
                        if (payload != null) putString(EXTRA_PAYLOAD, payload)
                    }
                }
            }
        }
    }
}
