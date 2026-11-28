package com.drmohammed.antiporn.data.repository

import android.content.Context
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Syncs the supervised ward device's active Fortress and Night Curfew telemetry
 * to Firestore `/policies/{uid}` so the Guardian sees live lock status.
 */
object WardActiveModesTelemetrySyncer {

    private const val TAG = "AntiPorn_WardModesSync"
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    fun syncAsync(context: Context) {
        val appContext = context.applicationContext ?: context
        syncScope.launch {
            sync(appContext)
        }
    }

    suspend fun sync(context: Context): Result<Unit> = syncMutex.withLock {
        val appContext = context.applicationContext ?: context
        try {
            val accRepo = AccountabilityRepository.getInstance(appContext)
            if (!accRepo.isSupervised() || !CloudSyncManager.isCloudSyncEnabled(appContext)) {
                return@withLock Result.success(Unit)
            }
            val uid = accRepo.getOrCreateCurrentUid()
            if (uid.isBlank()) {
                Timber.tag(TAG).d("Skipping ward active modes sync: UID is blank")
                return@withLock Result.success(Unit)
            }
            val hasAuth = try {
                FirebaseAuth.getInstance().currentUser != null
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "FirebaseAuth unavailable while checking active modes sync auth")
                false
            }
            if (!hasAuth) {
                Timber.tag(TAG).d("Skipping ward active modes sync: device not authenticated")
                return@withLock Result.success(Unit)
            }

            val snapshot = EffectiveRestrictionResolver.getActiveModesSnapshot(appContext)
            val activeFortress = snapshot.activeFortress
            val now = TrustedTimeProvider.currentTimeMillis(appContext)

            val payload = mapOf<String, Any?>(
                "wardActiveFortressId" to activeFortress?.fortressId,
                "wardActiveFortressName" to activeFortress?.resolveDisplayName(appContext),
                "wardActiveFortressIsEmergency" to (activeFortress?.isEmergency ?: false),
                "wardActiveFortressEndsAtWall" to (activeFortress?.endWallMs ?: 0L),
                "wardIsCurfewActiveNow" to snapshot.isNightCurfewActive,
                "wardActiveModesUpdatedAt" to now
            )

            try {
                FirebaseFirestore.getInstance()
                    .collection("policies")
                    .document(uid)
                    .update(payload)
                    .await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                    Timber.tag(TAG).d("Skipping ward active modes telemetry sync: policy document does not exist yet")
                    return@withLock Result.success(Unit)
                }
                throw e
            }

            Timber.tag(TAG).d(
                "Ward active modes telemetry synced (fortress=%s, curfew=%b)",
                activeFortress?.fortressId,
                snapshot.isNightCurfewActive
            )
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Non-critical ward active modes telemetry sync failed")
            Result.failure(e)
        }
    }
}
