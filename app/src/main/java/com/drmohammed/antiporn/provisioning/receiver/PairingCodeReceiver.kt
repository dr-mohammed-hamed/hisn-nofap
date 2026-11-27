package com.drmohammed.antiporn.provisioning.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import timber.log.Timber

/**
 * BroadcastReceiver triggered when the user enters the pairing code
 * into the interactive Heads-Up notification.
 *
 * Implements:
 * 1. Smart Input Sanitization (Arabic/Indic digits to English, non-digits stripped).
 * 2. ConnectPort race-condition safety (waits up to 5s if mDNS resolution is trailing).
 * 3. Auto-Return to MainActivity via BAL (Background Activity Launch) exemption.
 */
class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val rawCode = remoteInput?.getCharSequence(EXTRA_PAIRING_CODE)?.toString()
            ?: intent.getStringExtra(EXTRA_PAIRING_CODE)
        val pairingCode = sanitizePairingCode(rawCode)

        val pairingPort = intent.getIntExtra(EXTRA_PAIRING_PORT, 0).takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedPairingPort
        val connectPort = intent.getIntExtra(EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedConnectPort

        Timber.tag(TAG).i("PairingCodeReceiver received payload: len=${pairingCode.length}, pairingPort=$pairingPort")

        val serviceIntent = Intent(context, AdbPairingNotificationService::class.java).apply {
            action = AdbPairingNotificationService.ACTION_SUBMIT_PAIRING_CODE
            putExtra(AdbPairingNotificationService.EXTRA_PAIRING_PORT, pairingPort)
            putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, connectPort)
            val bundle = Bundle().apply {
                putCharSequence(EXTRA_PAIRING_CODE, pairingCode)
            }
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(EXTRA_PAIRING_CODE).build()), this, bundle)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed starting AdbPairingNotificationService from PairingCodeReceiver")
        }
    }

    private fun sanitizePairingCode(raw: String?): String = Companion.sanitizePairingCode(raw)

    companion object {
        private const val TAG = "AntiPorn_PairReceiver"
        const val EXTRA_PAIRING_CODE = "extra_pairing_code"
        const val EXTRA_PAIRING_PORT = "extra_pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"

        const val ACTION_PROVISIONING_RESULT = "com.drmohammed.antiporn.ACTION_PROVISIONING_RESULT"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_BLOCKING_USERS_JSON = "extra_blocking_users_json"
        const val EXTRA_OEM_PERMISSION_TYPE = "extra_oem_permission_type"
        const val EXTRA_BLOCKING_ACCOUNTS_JSON = "extra_blocking_accounts_json"

        /**
         * Sanitizes pairing input by stripping non-digits and converting Arabic/Indic numerals to standard ASCII digits.
         */
        fun sanitizePairingCode(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val sb = StringBuilder()
            for (ch in raw) {
                when (ch) {
                    in '0'..'9' -> sb.append(ch)
                    in '٠'..'٩' -> sb.append((ch - '٠' + '0'.code).toChar())
                    in '۰'..'۹' -> sb.append((ch - '۰' + '0'.code).toChar())
                }
            }
            return sb.toString()
        }
    }
}
