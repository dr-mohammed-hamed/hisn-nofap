package com.drmohammed.antiporn.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Single Source of Truth (SSOT) managing Cloud Synchronization gating and lifecycle.
 *
 * Implements "Zero-Cloud by Default" architecture:
 * Keeps Firebase and Firestore completely dormant unless an active supervision relationship
 * exists (as a ward or guardian) or during an intentional 15-minute pairing session.
 */
object CloudSyncManager {

    private const val TAG = "AntiPorn_CloudSyncMgr"
    private const val PREFS_NAME = "antiporn_cloud_sync_prefs"

    const val KEY_IS_CLOUD_SYNC_ENABLED = "is_cloud_sync_enabled"
    const val KEY_CLOUD_MIGRATION_CHECKED = "cloud_migration_checked"
    const val KEY_PAIRING_SESSION_CODE = "pairing_session_code"
    const val KEY_PAIRING_SESSION_EXPIRES_AT = "pairing_session_expires_at"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isCloudSyncEnabledFlow = MutableStateFlow(false)
    val isCloudSyncEnabledFlow: StateFlow<Boolean> = _isCloudSyncEnabledFlow.asStateFlow()

    @Volatile
    private var isInitialized = false

    data class PairingSession(
        val code: String,
        val expiresAt: Long
    )

    @Volatile
    private var currentPairingSession: PairingSession? = null

    private fun safeLog(priority: Int, message: String, throwable: Throwable? = null) {
        try {
            when (priority) {
                Log.DEBUG -> Log.d(TAG, message, throwable)
                Log.INFO -> Log.i(TAG, message, throwable)
                Log.WARN -> Log.w(TAG, message, throwable)
                Log.ERROR -> Log.e(TAG, message, throwable)
            }
        } catch (_: Throwable) {
            // JVM unit test fallback when android.util.Log is not mocked
        }
    }

    private fun getPrefs(context: Context): SharedPreferences? {
        return try {
            val appCtx = context.applicationContext ?: context
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Initializes the in-memory state from SharedPreferences on app startup.
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val prefs = getPrefs(context)
                val enabled = prefs?.getBoolean(KEY_IS_CLOUD_SYNC_ENABLED, false) ?: false
                _isCloudSyncEnabledFlow.value = enabled
                isInitialized = true
                safeLog(Log.INFO, "CloudSyncManager initialized: isCloudSyncEnabled=$enabled, migrationChecked=${isMigrationChecked(context)}")
            } catch (_: Throwable) {
                isInitialized = true
            }
        }
    }

    /**
     * Returns true if cloud synchronization is currently enabled for this device.
     */
    fun isCloudSyncEnabled(context: Context): Boolean {
        if (!isInitialized) init(context)
        return _isCloudSyncEnabledFlow.value
    }

    /**
     * Sets the persistent cloud synchronization state.
     */
    fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        try {
            val prefs = getPrefs(context)
            prefs?.edit()?.putBoolean(KEY_IS_CLOUD_SYNC_ENABLED, enabled)?.apply()
            _isCloudSyncEnabledFlow.value = enabled
            safeLog(Log.INFO, "CloudSyncManager state changed: isCloudSyncEnabled=$enabled")

            // Reactively start or stop sync services to prevent cold-boot and pairing race conditions
            val appCtx = context.applicationContext ?: context
            if (enabled) {
                try {
                    com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appCtx).startFirestoreSync()
                } catch (_: Throwable) {
                    // JVM test fallback
                }
                try {
                    com.drmohammed.antiporn.accountability.coordinator.GuardianSupervisionCoordinator.getInstance(appCtx).startSupervising()
                } catch (_: Throwable) {
                    // JVM test fallback
                }
            } else {
                try {
                    com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appCtx).stopFirestoreSync()
                } catch (_: Throwable) {
                    // JVM test fallback
                }
            }
        } catch (t: Throwable) {
            safeLog(Log.WARN, "Error updating isCloudSyncEnabled in SharedPreferences: ${t.message}")
        }
    }

    /**
     * Returns true if the one-time migration check has been completed.
     */
    fun isMigrationChecked(context: Context): Boolean {
        return try {
            getPrefs(context)?.getBoolean(KEY_CLOUD_MIGRATION_CHECKED, false) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Marks the migration check as completed.
     */
    fun setMigrationChecked(context: Context, checked: Boolean) {
        try {
            getPrefs(context)?.edit()?.putBoolean(KEY_CLOUD_MIGRATION_CHECKED, checked)?.apply()
            safeLog(Log.INFO, "CloudSyncManager migration status updated: checked=$checked")
        } catch (t: Throwable) {
            safeLog(Log.WARN, "Error updating cloud_migration_checked in SharedPreferences: ${t.message}")
        }
    }

    /**
     * Executes safe, non-disruptive migration for existing users and fresh installs.
     * Guaranteed zero false offline disconnections.
     */
    fun performSafeMigrationCheck(context: Context, externalScope: CoroutineScope = scope) {
        init(context)
        if (isMigrationChecked(context)) {
            safeLog(Log.DEBUG, "Migration check already completed; skipping.")
            return
        }

        externalScope.launch(Dispatchers.IO) {
            try {
                val db = HisnRoomDatabase.getInstance(context)
                val localPolicy = db.devicePolicyDao().getDevicePolicyBlocking()?.toBundle()
                val isLocallySupervised = localPolicy?.isSupervised == true || !localPolicy?.guardianUid.isNullOrBlank()
                val localPartners = db.partnerDao().getAllPartnersBlocking()
                val hasLocalPartners = localPartners.isNotEmpty()

                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser

                safeLog(Log.INFO, "Evaluating migration check: currentUser=${currentUser?.uid != null}, isLocallySupervised=$isLocallySupervised, hasLocalPartners=$hasLocalPartners")

                // Case 1: Fresh Install (Zero local supervision records & no authenticated Firebase user)
                if (currentUser == null && !isLocallySupervised && !hasLocalPartners) {
                    safeLog(Log.INFO, "Migration: Fresh install detected. Setting isCloudSyncEnabled=false, cloud_migration_checked=true (0ms local resolution)")
                    setCloudSyncEnabled(context, false)
                    setMigrationChecked(context, true)
                    return@launch
                }

                // Case 2: Definitively supervised locally (Ward or Guardian)
                if (isLocallySupervised || hasLocalPartners) {
                    safeLog(Log.INFO, "Migration: Active local supervision relationship found. Confirming isCloudSyncEnabled=true, cloud_migration_checked=true")
                    setCloudSyncEnabled(context, true)
                    setMigrationChecked(context, true)
                    return@launch
                }

                // Case 3: Ambiguous state (currentUser != null exists from previous install, but local DB has no active partner records)
                // Keep cloud sync enabled temporarily to prevent disruption while checking Firestore
                setCloudSyncEnabled(context, true)
                val uid = currentUser?.uid
                if (uid.isNullOrBlank()) {
                    setCloudSyncEnabled(context, false)
                    setMigrationChecked(context, true)
                    return@launch
                }

                try {
                    val firestore = FirebaseFirestore.getInstance()
                    
                    // (a) Check if device is a ward in cloud
                    val policyDoc = firestore.collection("policies").document(uid).get().await()
                    val cloudSupervised = policyDoc.exists() && (policyDoc.getBoolean("isSupervised") == true || !policyDoc.getString("guardianUid").isNullOrBlank())
                    
                    if (cloudSupervised) {
                        safeLog(Log.INFO, "Migration: Verified ward supervision in cloud for UID $uid. Confirming isCloudSyncEnabled=true.")
                        setCloudSyncEnabled(context, true)
                        setMigrationChecked(context, true)
                        return@launch
                    }

                    // (b) Check if user is a guardian for any ward
                    val partnersSnapshot = firestore.collection("users").document(uid).collection("partners").get().await()
                    val hasCloudPartners = !partnersSnapshot.isEmpty
                    if (hasCloudPartners) {
                        safeLog(Log.INFO, "Migration: Verified guardian/partner relationships in cloud for UID $uid. Confirming isCloudSyncEnabled=true.")
                        setCloudSyncEnabled(context, true)
                        setMigrationChecked(context, true)
                        return@launch
                    }

                    // (c) Query policies where guardianUid == uid
                    val supervisingSnapshot = firestore.collection("policies")
                        .whereEqualTo("guardianUid", uid)
                        .limit(1)
                        .get()
                        .await()

                    if (!supervisingSnapshot.isEmpty) {
                        safeLog(Log.INFO, "Migration: Verified active wards supervised by UID $uid. Confirming isCloudSyncEnabled=true.")
                        setCloudSyncEnabled(context, true)
                        setMigrationChecked(context, true)
                        return@launch
                    }

                    // Confirmed: 100% clean check, no supervision relationships found
                    safeLog(Log.INFO, "Migration: Confirmed no supervision relationships exist in cloud for UID $uid. Disabling cloud sync cleanly.")
                    setCloudSyncEnabled(context, false)
                    setMigrationChecked(context, true)
                    com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).stopFirestoreSync()

                } catch (networkEx: Exception) {
                    // GOLDEN RULE: On network errors, never disconnect cloud by accident! Keep enabled and leave migration unchecked to retry later.
                    safeLog(Log.WARN, "Migration: Network error checking cloud supervision for UID $uid (${networkEx.message}). Retaining isCloudSyncEnabled=true and deferring migration check.")
                    setCloudSyncEnabled(context, true)
                    setMigrationChecked(context, false)
                }

            } catch (e: Exception) {
                safeLog(Log.ERROR, "Unexpected error in performSafeMigrationCheck", e)
                // Fail-safe: leave cloud enabled if anything unexpected fails
                setCloudSyncEnabled(context, true)
            }
        }
    }

    /**
     * Starts an active 15-minute temporary pairing session.
     * Persists session expiry and code, enables cloud sync and triggers Firestore sync.
     */
    fun startTemporaryPairingSession(context: Context, code: String, expiresAt: Long) {
        currentPairingSession = PairingSession(code, expiresAt)
        try {
            getPrefs(context)?.edit()
                ?.putString(KEY_PAIRING_SESSION_CODE, code)
                ?.putLong(KEY_PAIRING_SESSION_EXPIRES_AT, expiresAt)
                ?.apply()
        } catch (_: Throwable) {
            // SharedPreferences fallback
        }
        setCloudSyncEnabled(context, true)
        safeLog(Log.INFO, "Temporary pairing session started for code $code (expiresAt=$expiresAt)")
    }

    /**
     * Returns the currently active pairing session if not expired.
     * Uses TrustedTimeProvider to prevent device clock tampering.
     */
    fun getActivePairingSession(context: Context? = null): PairingSession? {
        var session = currentPairingSession
        if (session == null && context != null) {
            val prefs = getPrefs(context)
            val savedCode = prefs?.getString(KEY_PAIRING_SESSION_CODE, null)
            val savedExpiresAt = prefs?.getLong(KEY_PAIRING_SESSION_EXPIRES_AT, 0L) ?: 0L
            if (!savedCode.isNullOrBlank() && savedExpiresAt > 0L) {
                session = PairingSession(savedCode, savedExpiresAt)
                currentPairingSession = session
            }
        }
        val current = session ?: return null
        val now = if (context != null) {
            try {
                com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(context)
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
        } else {
            System.currentTimeMillis()
        }

        return if (now <= current.expiresAt) {
            current
        } else {
            currentPairingSession = null
            if (context != null) {
                try {
                    getPrefs(context)?.edit()
                        ?.remove(KEY_PAIRING_SESSION_CODE)
                        ?.remove(KEY_PAIRING_SESSION_EXPIRES_AT)
                        ?.apply()
                } catch (_: Throwable) {}
                checkAndAutoRollbackIfUnsupervised(context)
            }
            null
        }
    }

    /**
     * Cancels the temporary pairing session and rolls back to offline mode if unsupervised.
     */
    suspend fun cancelTemporaryPairingSession(context: Context, code: String) = withContext(Dispatchers.IO) {
        val session = currentPairingSession
        if (session != null && session.code == code) {
            currentPairingSession = null
        }
        try {
            getPrefs(context)?.edit()
                ?.remove(KEY_PAIRING_SESSION_CODE)
                ?.remove(KEY_PAIRING_SESSION_EXPIRES_AT)
                ?.apply()
        } catch (_: Throwable) {}
        checkAndAutoRollbackIfUnsupervisedSuspend(context)
    }

    /**
     * Confirms that pairing succeeded; locks cloud sync permanently as enabled.
     */
    fun confirmPairingSuccess(context: Context) {
        currentPairingSession = null
        try {
            getPrefs(context)?.edit()
                ?.remove(KEY_PAIRING_SESSION_CODE)
                ?.remove(KEY_PAIRING_SESSION_EXPIRES_AT)
                ?.apply()
        } catch (_: Throwable) {}
        setCloudSyncEnabled(context, true)
        setMigrationChecked(context, true)
        safeLog(Log.INFO, "Pairing successfully confirmed; cloud sync locked as enabled.")
    }

    /**
     * Synchronous suspend evaluation of supervision relationships.
     */
    suspend fun checkAndAutoRollbackIfUnsupervisedSuspend(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext ?: context
        try {
            val db = HisnRoomDatabase.getInstance(appCtx)
            val policy = db.devicePolicyDao().getDevicePolicyBlocking()?.toBundle()
            val isSupervised = policy?.isSupervised == true || !policy?.guardianUid.isNullOrBlank()
            val activePartners = db.partnerDao().getAllPartnersBlocking().filter { it.status == PartnerEntity.STATUS_ACTIVE }

            if (!isSupervised && activePartners.isEmpty()) {
                safeLog(Log.INFO, "No active supervision relationships found. Rolling back cloud sync to false.")
                currentPairingSession = null
                setCloudSyncEnabled(appCtx, false)
            } else {
                safeLog(Log.DEBUG, "Active relationships exist (supervised=$isSupervised, partners=${activePartners.size}); retaining cloud sync.")
            }
        } catch (e: Exception) {
            safeLog(Log.WARN, "Error evaluating auto-rollback: ${e.message}")
        }
    }

    /**
     * Asynchronously checks if the device has any active supervision relationships.
     * If not supervised and not supervising anyone, rolls back is_cloud_sync_enabled to false
     * and detaches all Firestore listeners.
     */
    fun checkAndAutoRollbackIfUnsupervised(context: Context) {
        val appCtx = context.applicationContext ?: context
        scope.launch(Dispatchers.IO) {
            checkAndAutoRollbackIfUnsupervisedSuspend(appCtx)
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            isInitialized = false
            currentPairingSession = null
            _isCloudSyncEnabledFlow.value = false
        }
    }
}
