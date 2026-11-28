package com.drmohammed.antiporn.ui.control.qr

import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.print.PrintHelper
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.DialogQrExportBinding
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.drmohammed.antiporn.ui.common.showSafely
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Modern Material Design 3 BottomSheet dialog displaying the generated Self-Control QR lock code.
 * Provides user actions to share (via FileProvider Intent), print (via PrintHelper),
 * and save as PNG to the device photo gallery (via MediaStore).
 */
class QrExportDialog : BottomSheetDialogFragment() {

    private var _binding: DialogQrExportBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("QrExportDialog binding accessed outside view lifecycle")
    private var resolvedBitmap: Bitmap? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQrExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)

        binding.btnShareQr.isEnabled = false
        binding.btnPrintQr.isEnabled = false
        binding.btnSaveQrImage.isEnabled = false

        // Resolve and generate QR bitmap off the Main Thread
        viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = context?.applicationContext ?: return@launch
            val bitmap = withContext(Dispatchers.Default) {
                val qrManager = SelfControlQrManager.getInstance(appCtx)
                val token = qrManager.getSavedToken() ?: qrManager.generateAndSaveNewToken()
                qrManager.generateQrBitmap(token, 512)
            }
            if (!isAdded || _binding == null) return@launch
            resolvedBitmap = bitmap
            binding.ivExportQrCode.setImageBitmap(bitmap)
            binding.btnShareQr.isEnabled = true
            binding.btnPrintQr.isEnabled = true
            binding.btnSaveQrImage.isEnabled = true
        }

        binding.btnShareQr.setOnClickListener {
            resolvedBitmap?.let { shareQrCode(it) }
        }

        binding.btnPrintQr.setOnClickListener {
            resolvedBitmap?.let { printQrCode(it) }
        }

        binding.btnSaveQrImage.setOnClickListener {
            resolvedBitmap?.let { saveBitmapToGallery(it) }
        }

        binding.cbAcknowledgePossession.setOnCheckedChangeListener { _, isChecked ->
            binding.btnProceedTestScan.isEnabled = isChecked
        }

        binding.btnProceedTestScan.setOnClickListener {
            val ctx = context?.applicationContext ?: return@setOnClickListener
            binding.btnProceedTestScan.isEnabled = false
            val qrManager = SelfControlQrManager.getInstance(ctx)
            qrManager.setQrLockEnabled(true)
            Toast.makeText(ctx, R.string.qr_wizard_test_success_toast, Toast.LENGTH_LONG).show()
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY_SETUP_COMPLETED,
                Bundle().apply { putBoolean(RESULT_KEY_ENABLED, true) }
            )
            dismissAllowingStateLoss()
        }

        binding.btnCloseExportDialog.setOnClickListener {
            dismiss()
        }
    }

    private fun shareQrCode(bitmap: Bitmap) {
        val appCtx = context?.applicationContext ?: return
        binding.btnShareQr.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val chooser = withContext(Dispatchers.IO + NonCancellable) {
                    val cachePath = File(appCtx.cacheDir, "qr_exports").apply { mkdirs() }
                    val imageFile = File(cachePath, "AntiPorn_SelfControl_QR.png")
                    FileOutputStream(imageFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    val authority = "${appCtx.packageName}.fileprovider"
                    val contentUri: Uri = FileProvider.getUriForFile(appCtx, authority, imageFile)

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        putExtra(Intent.EXTRA_TEXT, appCtx.getString(R.string.qr_export_share_text))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    Intent.createChooser(shareIntent, appCtx.getString(R.string.qr_export_share_chooser))
                }
                if (isAdded && _binding != null) {
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed to share QR image")
                Toast.makeText(
                    appCtx,
                    appCtx.getString(R.string.qr_export_share_error, e.localizedMessage ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                if (isAdded && _binding != null) {
                    binding.btnShareQr.isEnabled = true
                }
            }
        }
    }

    private fun printQrCode(bitmap: Bitmap) {
        val ctx = context ?: return
        try {
            if (PrintHelper.systemSupportsPrint()) {
                PrintHelper(ctx).apply {
                    scaleMode = PrintHelper.SCALE_MODE_FIT
                }.printBitmap("AntiPorn_SelfControl_QR", bitmap)
            } else {
                Toast.makeText(ctx.applicationContext, R.string.qr_export_print_not_supported, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Print helper failed")
            Toast.makeText(ctx.applicationContext, R.string.qr_export_print_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val appCtx = context?.applicationContext ?: return
        binding.btnSaveQrImage.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val savedUri = withContext(Dispatchers.IO + NonCancellable) {
                        val filename = "AntiPorn_SelfControl_QR_${System.currentTimeMillis()}.png"
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AntiPorn")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }

                        val uri = appCtx.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            contentValues
                        ) ?: return@withContext null

                        var outputStream: OutputStream? = null
                        try {
                            outputStream = appCtx.contentResolver.openOutputStream(uri)
                                ?: throw IllegalStateException("Unable to open MediaStore output stream")
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            outputStream.flush()
                            outputStream.close()
                            outputStream = null

                            contentValues.clear()
                            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            appCtx.contentResolver.update(uri, contentValues, null, null)
                            uri
                        } catch (t: Throwable) {
                            try {
                                outputStream?.close()
                            } catch (closeEx: Exception) {
                                Timber.tag(TAG).w(closeEx, "Error closing failed gallery stream")
                            }
                            runCatching { appCtx.contentResolver.delete(uri, null, null) }
                                .onFailure { Timber.tag(TAG).w(it, "Failed deleting orphaned pending MediaStore row") }
                            throw t
                        }
                    }

                    if (savedUri != null) {
                        if (isAdded) {
                            parentFragmentManager.showSafely(TAG_GALLERY_WARNING) {
                                ConfirmActionDialogFragment.newInstance(
                                    requestKey = REQ_GALLERY_WARNING,
                                    titleRes = R.string.qr_export_gallery_warning_title,
                                    messageRes = R.string.qr_export_gallery_warning_message,
                                    positiveRes = android.R.string.ok
                                )
                            }
                        }
                    } else {
                        Toast.makeText(appCtx, R.string.qr_export_save_error_gallery, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Pre-Android 10: avoid SecurityException due to missing WRITE_EXTERNAL_STORAGE
                    Toast.makeText(appCtx, R.string.qr_export_save_error_gallery, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed to save QR to gallery")
                Toast.makeText(
                    appCtx,
                    appCtx.getString(R.string.qr_export_save_error, e.localizedMessage ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                if (isAdded && _binding != null) {
                    binding.btnSaveQrImage.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations == true) return
        val ctx = context?.applicationContext
        if (ctx != null) {
            val qrManager = SelfControlQrManager.getInstance(ctx)
            if (!qrManager.isQrLockEnabled()) {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY_SETUP_COMPLETED,
                    Bundle().apply { putBoolean(RESULT_KEY_ENABLED, false) }
                )
            }
        }
    }

    companion object {
        const val TAG = "QrExportDialog"
        const val REQUEST_KEY_SETUP_COMPLETED = "qr_setup_completed"
        const val RESULT_KEY_ENABLED = "result_key_enabled"
        private const val REQ_GALLERY_WARNING = "req_qr_export_gallery_warning"
        private const val TAG_GALLERY_WARNING = "tag_qr_export_gallery_warning"

        fun newInstance(): QrExportDialog {
            return QrExportDialog()
        }
    }
}
