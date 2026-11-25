package com.drmohammed.antiporn.data.repository

import android.os.Build
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "AntiPorn_AccPartners"

private fun safeLog(priority: Int, message: String, throwable: Throwable? = null) {
    try {
        when (priority) {
            Log.DEBUG -> Log.d(TAG, message, throwable)
            Log.INFO -> Log.i(TAG, message, throwable)
            Log.WARN -> Log.w(TAG, message, throwable)
            else -> Log.e(TAG, message, throwable)
        }
    } catch (_: Throwable) {
        // JVM unit test fallback when android.util.Log is not mocked
    }
}

private fun resolvedPartnerRole(doc: DocumentSnapshot): String {
    return doc.getString("role")?.takeIf { it.isNotBlank() }
        ?: if (doc.id.endsWith("_GUARDIAN")) PartnerEntity.ROLE_GUARDIAN else PartnerEntity.ROLE_WARD
}

internal fun partnerCloudDocId(partnerUid: String, role: String): String {
    return if (role == PartnerEntity.ROLE_GUARDIAN) {
        "${partnerUid}_GUARDIAN"
    } else {
        partnerUid
    }
}

internal suspend fun AccountabilityRepository.deleteCloudPartnerDocSafely(
    ownerUid: String,
    partnerUid: String,
    role: String
) {
    val partnersColl = firestore.collection("users").document(ownerUid).collection("partners")
    if (role == PartnerEntity.ROLE_GUARDIAN) {
        partnersColl.document(partnerCloudDocId(partnerUid, PartnerEntity.ROLE_GUARDIAN))
            .delete()
            .await()
        val legacyDoc = partnersColl.document(partnerUid).get().await()
        if (legacyDoc.exists() && legacyDoc.getString("role") == PartnerEntity.ROLE_GUARDIAN) {
            partnersColl.document(partnerUid).delete().await()
        }
    } else {
        val docRef = partnersColl.document(partnerCloudDocId(partnerUid, role))
        val doc = docRef.get().await()
        if (doc.exists() && doc.getString("role") != PartnerEntity.ROLE_GUARDIAN) {
            docRef.delete().await()
        }
    }
}

/**
 * Synchronizes all partner relationships from Firestore into the local SQLite database.
 * Restores supervised wards and guardians automatically when switching devices or reinstalling.
 * Also migrates any local partners to cloud under current authenticated UID if needed.
 * Guarded with syncPartnersMutex to eliminate duplicate simultaneous cloud restore loops.
 */
internal suspend fun AccountabilityRepository.syncPartnersFromCloudInternal(): Result<List<PartnerEntity>> = withContext(Dispatchers.IO) {
    syncPartnersMutex.withLock {
        try {
            val myUid = getOrCreateCurrentUid()
            val maskedMyUid = AiTelemetryReporter.maskIdentifier(myUid)
            if (myUid.startsWith("dev_") && currentAuthUserOrNull() == null) {
                safeLog(Log.DEBUG, "Unauthenticated device UID ($maskedMyUid), skipping cloud partner restore")
                return@withLock Result.success(partnerDao.getAllPartnersBlocking())
            }

            safeLog(Log.INFO, "Initiating cloud partners restore for UID: $maskedMyUid...")

            var policyFetchSucceeded = false
            val myPolicyDoc = try {
                firestore.collection("policies").document(myUid).get().await().also {
                    policyFetchSucceeded = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                safeLog(Log.WARN, "Failed fetching /policies/$maskedMyUid during partner sync: ${e.message}")
                null
            }
            val isActuallyCloudSupervised = myPolicyDoc != null && myPolicyDoc.exists() &&
                myPolicyDoc.getBoolean("isSupervised") == true &&
                !myPolicyDoc.getString("guardianUid").isNullOrBlank()

            val tombstonedWards = getTombstonedTeardownWards()
            val partnersColl = firestore.collection("users").document(myUid).collection("partners")
            val cloudPartnersSnapshot = partnersColl.get().await()
            val cloudPartnerKeys = mutableSetOf<Pair<String, String>>()
            val writtenCanonicalDocIds = mutableSetOf<String>()

            // Process GUARDIAN documents before WARD documents so migrating a legacy misplaced GUARDIAN
            // doc at {uid} -> {uid}_GUARDIAN never collides with or deletes a canonical WARD doc at {uid}.
            val orderedDocs = cloudPartnersSnapshot.documents.sortedBy { snapshotDoc ->
                if (resolvedPartnerRole(snapshotDoc) == PartnerEntity.ROLE_GUARDIAN) 0 else 1
            }

            for (doc in orderedDocs) {
                val rawPartnerUid = doc.getString("partnerUid")?.takeIf { it.isNotBlank() }
                    ?: doc.id.removeSuffix("_GUARDIAN")
                if (rawPartnerUid.isBlank()) continue
                val maskedRawPartner = AiTelemetryReporter.maskIdentifier(rawPartnerUid)
                val partnerName = doc.getString("partnerName")?.takeIf { it.isNotBlank() } ?: "Partner"
                val role = resolvedPartnerRole(doc)
                val pairingCode = doc.getString("pairingCode") ?: AccountabilityRepository.CODE_LOCKED
                val pairedAt = doc.getLong("pairedAt") ?: TrustedTimeProvider.currentTimeMillis(appContext)

                if (role == PartnerEntity.ROLE_WARD &&
                    (tombstonedWards.contains(doc.id) || tombstonedWards.contains(rawPartnerUid))
                ) {
                    try {
                        if (!writtenCanonicalDocIds.contains(doc.id)) {
                            partnersColl.document(doc.id).delete().await()
                        }
                        removeTombstonedTeardownWards(listOf(doc.id, rawPartnerUid))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        safeLog(Log.WARN, "Deferred pruning tombstoned ward ${AiTelemetryReporter.maskIdentifier(doc.id)}: ${e.message}")
                    }
                    continue
                }

                if (role == PartnerEntity.ROLE_GUARDIAN && policyFetchSucceeded && !isActuallyCloudSupervised) {
                    safeLog(Log.WARN, "[GhostGuardian] Detected orphaned guardian $maskedRawPartner in cloud while policy is unsupervised. Pruning from cloud...")
                    try {
                        if (!writtenCanonicalDocIds.contains(doc.id)) {
                            partnersColl.document(doc.id).delete().await()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        safeLog(Log.WARN, "Failed pruning orphaned cloud guardian ${AiTelemetryReporter.maskIdentifier(doc.id)}: ${e.message}")
                    }
                    continue
                }

                val partnerUid = resolveActivePartnerUid(rawPartnerUid, pairingCode)
                if (role == PartnerEntity.ROLE_WARD && tombstonedWards.contains(partnerUid)) {
                    try {
                        if (!writtenCanonicalDocIds.contains(doc.id)) {
                            partnersColl.document(doc.id).delete().await()
                        }
                        removeTombstonedTeardownWards(listOf(doc.id, rawPartnerUid, partnerUid))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        safeLog(Log.WARN, "Deferred pruning migrated tombstoned ward ${AiTelemetryReporter.maskIdentifier(doc.id)}: ${e.message}")
                    }
                    continue
                }

                val maskedPartner = AiTelemetryReporter.maskIdentifier(partnerUid)
                val expectedDocId = partnerCloudDocId(partnerUid, role)
                if (partnerUid != rawPartnerUid || doc.id != expectedDocId) {
                    val canonicalAlreadyInSnapshot = doc.id != expectedDocId &&
                        cloudPartnersSnapshot.documents.any { existingDoc ->
                            existingDoc.id == expectedDocId && resolvedPartnerRole(existingDoc) == role
                        }
                    var normalizedDocWritten = canonicalAlreadyInSnapshot
                    if (!canonicalAlreadyInSnapshot) {
                        try {
                            val updatedDocData = mapOf(
                                "partnerUid" to partnerUid,
                                "partnerName" to partnerName,
                                "role" to role,
                                "pairingCode" to pairingCode,
                                "pairedAt" to pairedAt
                            )
                            partnersColl.document(expectedDocId)
                                .set(updatedDocData, SetOptions.merge())
                                .await()
                            writtenCanonicalDocIds.add(expectedDocId)
                            normalizedDocWritten = true
                            safeLog(Log.DEBUG, "Uploaded synchronized partner doc $maskedPartner (docId=${AiTelemetryReporter.maskIdentifier(expectedDocId)}) to cloud")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            safeLog(Log.WARN, "Failed writing updated partner doc $maskedPartner to cloud: ${e.message}")
                        }
                    }

                    if (normalizedDocWritten) {
                        try {
                            if (doc.id != expectedDocId && !writtenCanonicalDocIds.contains(doc.id)) {
                                partnersColl.document(doc.id).delete().await()
                            }
                            if (role == PartnerEntity.ROLE_WARD &&
                                rawPartnerUid != doc.id &&
                                rawPartnerUid != expectedDocId &&
                                !writtenCanonicalDocIds.contains(rawPartnerUid)
                            ) {
                                val rawSnapshotDoc = cloudPartnersSnapshot.documents.find { it.id == rawPartnerUid }
                                if (rawSnapshotDoc != null &&
                                    resolvedPartnerRole(rawSnapshotDoc) != PartnerEntity.ROLE_GUARDIAN
                                ) {
                                    partnersColl.document(rawPartnerUid).delete().await()
                                }
                            }
                            safeLog(Log.DEBUG, "Pruned obsolete migrated partner doc ${AiTelemetryReporter.maskIdentifier(doc.id)} / $maskedRawPartner from cloud")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            safeLog(Log.WARN, "Failed to prune stale partner doc ${AiTelemetryReporter.maskIdentifier(doc.id)} / $maskedRawPartner: ${e.message}")
                        }
                    }

                    if (canonicalAlreadyInSnapshot) {
                        continue
                    }
                }

                val partnerEntity = PartnerEntity(
                    id = partnerUid,
                    pairingCode = pairingCode,
                    displayName = partnerName,
                    role = role,
                    status = PartnerEntity.STATUS_ACTIVE,
                    createdAt = pairedAt,
                    lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
                )
                partnerDao.savePartnerBlocking(partnerEntity)
                cloudPartnerKeys.add(partnerUid to role)
                safeLog(Log.DEBUG, "Restored cloud partner to SQLite: $partnerName ($maskedPartner, role=$role)")
            }

            try {
                if (isActuallyCloudSupervised) {
                    val guardianUid = myPolicyDoc?.getString("guardianUid")
                    val guardianName = myPolicyDoc?.getString("guardianName")
                    if (!guardianUid.isNullOrBlank()) {
                        val activeGuardianUid = resolveActivePartnerUid(guardianUid, AccountabilityRepository.CODE_LOCKED)
                        partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
                            .filter { it.id != activeGuardianUid }
                            .forEach { partnerDao.deletePartnerByIdAndRoleBlocking(it.id, PartnerEntity.ROLE_GUARDIAN) }
                        val existingGuardian = partnerDao.getPartnerByIdAndRoleBlocking(activeGuardianUid, PartnerEntity.ROLE_GUARDIAN)
                        val guardian = PartnerEntity(
                            id = activeGuardianUid,
                            pairingCode = AccountabilityRepository.CODE_LOCKED,
                            displayName = existingGuardian?.displayName?.takeIf { it.isNotBlank() }
                                ?: guardianName?.takeIf { it.isNotBlank() }
                                ?: appContext.getString(R.string.guardian_default_name),
                            role = PartnerEntity.ROLE_GUARDIAN,
                            status = PartnerEntity.STATUS_ACTIVE,
                            createdAt = existingGuardian?.createdAt ?: myPolicyDoc.getLong("updatedAt") ?: TrustedTimeProvider.currentTimeMillis(appContext),
                            lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
                        )
                        partnerDao.savePartnerBlocking(guardian)
                        safeLog(Log.INFO, "Restored Guardian from cloud policy: ${guardian.displayName} (${AiTelemetryReporter.maskIdentifier(activeGuardianUid)})")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                safeLog(Log.WARN, "Non-critical: Failed to verify guardian from /policies/$maskedMyUid: ${e.message}")
            }

            // Mutual-Supervision Authoritative Ward Recovery Fallback
            try {
                val supervisedWardsQuery = firestore.collection("policies")
                    .whereEqualTo("guardianUid", myUid)
                    .get()
                    .await()
                for (wardDoc in supervisedWardsQuery.documents) {
                    val rawWardUid = wardDoc.id
                    if (rawWardUid.isBlank() || rawWardUid == myUid) continue
                    if (tombstonedWards.contains(rawWardUid)) continue
                    if (cloudPartnerKeys.contains(rawWardUid to PartnerEntity.ROLE_WARD)) continue
                    if (wardDoc.getBoolean("isSupervised") != true) continue

                    val wardUid = resolveActivePartnerUid(rawWardUid, wardDoc.getString("pairingCode"))
                    if (wardUid.isBlank() || wardUid == myUid) continue
                    if (tombstonedWards.contains(wardUid)) continue
                    if (cloudPartnerKeys.contains(wardUid to PartnerEntity.ROLE_WARD)) continue

                    val now = TrustedTimeProvider.currentTimeMillis(appContext)
                    val existingLocalWard = partnerDao.getPartnerByIdAndRoleBlocking(wardUid, PartnerEntity.ROLE_WARD)
                        ?: if (wardUid != rawWardUid) {
                            partnerDao.getPartnerByIdAndRoleBlocking(rawWardUid, PartnerEntity.ROLE_WARD)
                        } else {
                            null
                        }
                    val recoveredName = existingLocalWard?.displayName?.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.status_ward_badge)
                    val recoveredCode = existingLocalWard?.pairingCode?.takeIf { it.isNotBlank() }
                        ?: wardDoc.getString("pairingCode")?.takeIf { it.isNotBlank() }
                        ?: AccountabilityRepository.CODE_LOCKED
                    val recoveredCreatedAt = existingLocalWard?.createdAt
                        ?: wardDoc.getLong("updatedAt")
                        ?: now

                    val recoveredWard = PartnerEntity(
                        id = wardUid,
                        pairingCode = recoveredCode,
                        displayName = recoveredName,
                        role = PartnerEntity.ROLE_WARD,
                        status = PartnerEntity.STATUS_ACTIVE,
                        createdAt = recoveredCreatedAt,
                        lastSyncAt = now
                    )
                    partnerDao.savePartnerBlocking(recoveredWard)
                    cloudPartnerKeys.add(wardUid to PartnerEntity.ROLE_WARD)

                    val maskedWardUid = AiTelemetryReporter.maskIdentifier(wardUid)
                    try {
                        val backfillData = mapOf(
                            "partnerUid" to wardUid,
                            "partnerName" to recoveredName,
                            "role" to PartnerEntity.ROLE_WARD,
                            "pairingCode" to recoveredCode,
                            "pairedAt" to recoveredCreatedAt
                        )
                        partnersColl.document(partnerCloudDocId(wardUid, PartnerEntity.ROLE_WARD))
                            .set(backfillData, SetOptions.merge())
                            .await()
                        safeLog(Log.INFO, "Recovered missing ward $maskedWardUid from /policies query and backfilled cloud partner doc")
                    } catch (backfillEx: Exception) {
                        if (backfillEx is CancellationException) throw backfillEx
                        safeLog(Log.WARN, "Recovered ward $maskedWardUid locally, but cloud backfill failed: ${backfillEx.message}")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                safeLog(Log.WARN, "Non-critical: Failed authoritative ward recovery query from /policies: ${e.message}")
            }

            val localPartners = partnerDao.getAllPartnersBlocking().filter { it.role != PartnerEntity.ROLE_GUARDIAN }
            for (local in localPartners) {
                if (!cloudPartnerKeys.contains(local.id to local.role) && !tombstonedWards.contains(local.id)) {
                    val maskedLocalId = AiTelemetryReporter.maskIdentifier(local.id)
                    try {
                        val relationData = mapOf(
                            "partnerUid" to local.id,
                            "partnerName" to local.displayName,
                            "role" to local.role,
                            "pairingCode" to local.pairingCode,
                            "pairedAt" to local.createdAt
                        )
                        partnersColl.document(partnerCloudDocId(local.id, local.role))
                            .set(relationData, SetOptions.merge())
                            .await()
                        safeLog(Log.INFO, "Migrated local partner ${local.displayName} ($maskedLocalId) to cloud /users/$maskedMyUid/partners")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        safeLog(Log.WARN, "Failed migrating local partner $maskedLocalId to cloud: ${e.message}")
                    }
                }
            }

            checkAndSelfHealSupervisionStatus()

            val finalPartners = partnerDao.getAllPartnersBlocking()
            safeLog(Log.INFO, "Cloud partners sync completed successfully: ${finalPartners.size} total partners in SQLite")
            Result.success(finalPartners)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            safeLog(Log.ERROR, "Error syncing partners from cloud", e)
            Result.failure(e)
        }
    }
}

internal suspend fun AccountabilityRepository.getGuardiansInternal(): List<PartnerEntity> = withContext(Dispatchers.IO) {
    val list = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
    if (list.isNotEmpty()) return@withContext list

    val policy = policyDao.getDevicePolicyBlocking()?.toBundle()
    if (policy != null && policy.isSupervised && !policy.guardianUid.isNullOrBlank()) {
        val activeGuardianUid = resolveActivePartnerUid(policy.guardianUid, AccountabilityRepository.CODE_LOCKED)
        return@withContext revocationMutex.withLock {
            val existing = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
            if (existing.isNotEmpty()) return@withLock existing
            val latestPolicy = policyDao.getDevicePolicyBlocking()?.toBundle()
            if (latestPolicy == null || !latestPolicy.isSupervised || latestPolicy.guardianUid.isNullOrBlank()) {
                return@withLock emptyList()
            }
            val fallbackName = runCatching { appContext.getString(R.string.guardian_default_name) }.getOrDefault("Guardian")
            val synthesizedGuardian = PartnerEntity(
                id = activeGuardianUid,
                pairingCode = AccountabilityRepository.CODE_LOCKED,
                displayName = latestPolicy.guardianName?.takeIf { it.isNotBlank() } ?: fallbackName,
                role = PartnerEntity.ROLE_GUARDIAN,
                status = PartnerEntity.STATUS_ACTIVE,
                createdAt = latestPolicy.updatedAt,
                lastSyncAt = TrustedTimeProvider.currentTimeMillis(appContext)
            )
            try {
                partnerDao.savePartnerBlocking(synthesizedGuardian)
                safeLog(Log.INFO, "Self-healed guardian record in SQLite: ${synthesizedGuardian.displayName}")
            } catch (e: Exception) {
                safeLog(Log.ERROR, "Failed to self-heal guardian in SQLite", e)
            }
            listOf(synthesizedGuardian)
        }
    }
    emptyList()
}

internal suspend fun AccountabilityRepository.getWardsInternal(): List<PartnerEntity> = withContext(Dispatchers.IO) {
    partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD)
}

internal suspend fun AccountabilityRepository.deletePartnerInternal(
    partnerId: String,
    role: String = PartnerEntity.ROLE_WARD
): Result<Unit> = withContext(Dispatchers.IO) {
    val maskedPartner = AiTelemetryReporter.maskIdentifier(partnerId)
    try {
        val myUid = getOrCreateCurrentUid()
        deleteCloudPartnerDocSafely(myUid, partnerId, role)
        partnerDao.deletePartnerByIdAndRoleBlocking(partnerId, role)
        if (role == PartnerEntity.ROLE_WARD) {
            clearPendingGuardianTeardownMarker(partnerId)
            removeTombstonedTeardownWards(listOf(partnerId))
        }
        safeLog(Log.INFO, "Successfully deleted partner $maskedPartner (role=$role) from cloud and local database")
        com.drmohammed.antiporn.cloud.CloudSyncManager.checkAndAutoRollbackIfUnsupervised(appContext)
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        safeLog(Log.ERROR, "Error deleting partner $maskedPartner (role=$role)", e)
        Result.failure(e)
    }
}

/**
 * Gets the user's configured display name, falling back to hardware device model.
 */
internal fun AccountabilityRepository.getMyDisplayNameInternal(): String {
    val customName = prefs.getString(AccountabilityRepository.KEY_MY_DISPLAY_NAME, null)?.trim()
    if (!customName.isNullOrBlank()) {
        return customName
    }
    return "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }
}

/**
 * Sets and persists the user's custom display name locally and syncs to Firestore.
 */
internal suspend fun AccountabilityRepository.setMyDisplayNameInternal(newName: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val cleanName = newName.trim().take(28)
        if (cleanName.isBlank()) {
            prefs.edit().remove(AccountabilityRepository.KEY_MY_DISPLAY_NAME).apply()
        } else {
            prefs.edit().putString(AccountabilityRepository.KEY_MY_DISPLAY_NAME, cleanName).apply()
        }

        val effectiveName = getMyDisplayName()
        val myUid = getOrCreateCurrentUid()
        val maskedMyUid = AiTelemetryReporter.maskIdentifier(myUid)

        val userData = mapOf(
            "uid" to myUid,
            "displayName" to effectiveName,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
        )
        try {
            firestore.collection("users").document(myUid)
                .set(userData, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            safeLog(Log.WARN, "Sync to /users/$maskedMyUid failed: ${e.message}")
        }

        val code = prefs.getString(AccountabilityRepository.KEY_MY_PAIRING_CODE, null)
        if (!code.isNullOrBlank()) {
            val maskedCode = AiTelemetryReporter.maskIdentifier(code)
            try {
                firestore.collection("pairings").document(code)
                    .update("ownerName", effectiveName)
                    .await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                safeLog(Log.WARN, "Update ownerName in /pairings/$maskedCode failed: ${e.message}")
            }
        }

        val wards = partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_WARD)
        for (ward in wards) {
            try {
                firestore.collection("policies").document(ward.id)
                    .update("guardianName", effectiveName)
                    .await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                safeLog(Log.WARN, "Update guardianName in /policies/${AiTelemetryReporter.maskIdentifier(ward.id)} failed: ${e.message}")
            }
        }

        safeLog(Log.INFO, "Successfully updated my display name to '$effectiveName'")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        safeLog(Log.ERROR, "Error setting my display name", e)
        Result.failure(e)
    }
}

/**
 * Renames a partner (ward or guardian) locally in SQLite and in cloud Firestore.
 */
internal suspend fun AccountabilityRepository.renamePartnerInternal(
    partnerId: String,
    newName: String,
    role: String = PartnerEntity.ROLE_WARD
): Result<Unit> = withContext(Dispatchers.IO) {
    val maskedPartner = AiTelemetryReporter.maskIdentifier(partnerId)
    try {
        val cleanName = newName.trim().take(28)
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Name cannot be blank"))
        }

        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        partnerDao.updatePartnerDisplayNameByRoleBlocking(partnerId, role, cleanName, now)

        try {
            val myUid = getOrCreateCurrentUid()
            firestore.collection("users").document(myUid)
                .collection("partners").document(partnerCloudDocId(partnerId, role))
                .set(
                    mapOf(
                        "partnerUid" to partnerId,
                        "role" to role,
                        "partnerName" to cleanName,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            safeLog(Log.WARN, "Cloud sync for partnerName $maskedPartner (role=$role) failed: ${e.message}")
        }

        safeLog(Log.INFO, "Successfully renamed partner $maskedPartner (role=$role) to '$cleanName'")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        safeLog(Log.ERROR, "Error renaming partner $maskedPartner (role=$role)", e)
        Result.failure(e)
    }
}
