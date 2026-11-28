package com.drmohammed.antiporn.testing

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class FakeTestApplicationContext : Application() {
    private val prefs = mutableMapOf<String, InMemoryTestSharedPreferences>()
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.drmohammed.antiporn"
    override fun getPackageManager(): PackageManager? = null
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        prefs.getOrPut(name ?: "default") { InMemoryTestSharedPreferences() }
}

internal class InMemoryTestSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false
        override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, v: MutableSet<String>?) = apply { if (key != null) pending[key] = v?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
        override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) {
                values.clear()
                clearAll = false
            }
            for ((k, v) in pending) {
                if (v == null) values.remove(k) else values[k] = v
            }
            pending.clear()
        }
    }
}

internal class FakeTestDevicePolicyDao : DevicePolicyDao {
    private var entity: DevicePolicyEntity? = null
    private val flow = MutableStateFlow<DevicePolicyEntity?>(null)
    override fun getDevicePolicyFlow(): Flow<DevicePolicyEntity?> = flow
    override suspend fun getDevicePolicy(): DevicePolicyEntity? = entity
    override fun getDevicePolicyBlocking(): DevicePolicyEntity? = entity
    override suspend fun saveDevicePolicy(entity: DevicePolicyEntity) { this.entity = entity; flow.value = entity }
    override fun saveDevicePolicyBlocking(entity: DevicePolicyEntity) { this.entity = entity; flow.value = entity }
}

internal class FakeTestPartnerDao : PartnerDao {
    override fun getAllPartnersFlow(): Flow<List<PartnerEntity>> = MutableStateFlow(emptyList())
    override suspend fun getAllPartners(): List<PartnerEntity> = emptyList()
    override fun getAllPartnersBlocking(): List<PartnerEntity> = emptyList()
    override fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>> = MutableStateFlow(emptyList())
    override suspend fun getPartnersByRole(role: String): List<PartnerEntity> = emptyList()
    override fun getPartnersByRoleBlocking(role: String): List<PartnerEntity> = emptyList()
    override suspend fun getPartnersById(id: String): List<PartnerEntity> = emptyList()
    override fun getPartnersByIdBlocking(id: String): List<PartnerEntity> = emptyList()
    override suspend fun getPartnerByIdAndRole(id: String, role: String): PartnerEntity? = null
    override fun getPartnerByIdAndRoleBlocking(id: String, role: String): PartnerEntity? = null
    override suspend fun savePartner(partner: PartnerEntity) {}
    override fun savePartnerBlocking(partner: PartnerEntity) {}
    override suspend fun deletePartnerByIdAndRole(id: String, role: String): Int = 0
    override fun deletePartnerByIdAndRoleBlocking(id: String, role: String): Int = 0
    override suspend fun updatePartnerDisplayNameByRole(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
    override fun updatePartnerDisplayNameByRoleBlocking(id: String, role: String, newName: String, syncTimestamp: Long): Int = 0
}

internal class FakeTestPendingApprovalDao : PendingApprovalDao {
    private val lock = Any()
    private val store = linkedMapOf<String, PendingApprovalEntity>()
    private val flow = MutableStateFlow<List<PendingApprovalEntity>>(emptyList())

    private fun snapshotSortedDesc(): List<PendingApprovalEntity> =
        store.values.sortedByDescending { it.requestedAt }

    private fun emitLocked() {
        flow.value = snapshotSortedDesc()
    }

    override fun getPendingApprovalsFlow(): Flow<List<PendingApprovalEntity>> = flow.asStateFlow()
    override suspend fun getPendingApprovals(): List<PendingApprovalEntity> = getPendingApprovalsBlocking()
    override fun getPendingApprovalsBlocking(): List<PendingApprovalEntity> = synchronized(lock) { snapshotSortedDesc() }
    override suspend fun getActualPendingApprovals(): List<PendingApprovalEntity> = getActualPendingApprovalsBlocking()
    override fun getActualPendingApprovalsBlocking(): List<PendingApprovalEntity> = synchronized(lock) {
        store.values
            .filter { it.status == PendingApprovalEntity.STATUS_PENDING || it.status == PendingApprovalEntity.STATUS_FALLBACK_HIDDEN }
            .sortedByDescending { it.requestedAt }
    }
    override suspend fun getUnsyncedApprovals(): List<PendingApprovalEntity> = getUnsyncedApprovalsBlocking()
    override fun getUnsyncedApprovalsBlocking(): List<PendingApprovalEntity> = synchronized(lock) {
        store.values.filter { !it.isCloudSynced }.sortedBy { it.requestedAt }
    }
    override suspend fun getApprovalByPackageName(packageName: String): PendingApprovalEntity? =
        getApprovalByPackageNameBlocking(packageName)
    override fun getApprovalByPackageNameBlocking(packageName: String): PendingApprovalEntity? = synchronized(lock) {
        store[packageName]
    }
    override suspend fun savePendingApproval(approval: PendingApprovalEntity) = savePendingApprovalBlocking(approval)
    override fun savePendingApprovalBlocking(approval: PendingApprovalEntity) = synchronized(lock) {
        store[approval.packageName] = approval
        emitLocked()
    }
    override suspend fun savePendingApprovals(approvals: List<PendingApprovalEntity>) = savePendingApprovalsBlocking(approvals)
    override fun savePendingApprovalsBlocking(approvals: List<PendingApprovalEntity>) = synchronized(lock) {
        approvals.forEach { store[it.packageName] = it }
        emitLocked()
    }
    override suspend fun updateApprovalStatus(packageName: String, status: String, decidedBy: String?, decidedAt: Long, isSynced: Int): Int =
        updateApprovalStatusBlocking(packageName, status, decidedBy, decidedAt, isSynced)
    override fun updateApprovalStatusBlocking(packageName: String, status: String, decidedBy: String?, decidedAt: Long, isSynced: Int): Int = synchronized(lock) {
        val existing = store[packageName] ?: return@synchronized 0
        store[packageName] = existing.copy(status = status, decidedBy = decidedBy, decidedAt = decidedAt, isCloudSynced = isSynced == 1)
        emitLocked()
        1
    }
    override suspend fun markApprovalCloudSynced(packageName: String, isSynced: Int): Int =
        markApprovalCloudSyncedBlocking(packageName, isSynced)
    override fun markApprovalCloudSyncedBlocking(packageName: String, isSynced: Int): Int = synchronized(lock) {
        val existing = store[packageName] ?: return@synchronized 0
        store[packageName] = existing.copy(isCloudSynced = isSynced == 1)
        emitLocked()
        1
    }
    override fun markApprovalCloudSyncedIfMatchingBlocking(
        packageName: String,
        expectedStatus: String,
        expectedDecidedAt: Long?,
        isSynced: Int
    ): Int = synchronized(lock) {
        val existing = store[packageName] ?: return@synchronized 0
        if (existing.status != expectedStatus || existing.decidedAt != expectedDecidedAt) return@synchronized 0
        store[packageName] = existing.copy(isCloudSynced = isSynced == 1)
        emitLocked()
        1
    }
    override suspend fun deletePendingApproval(packageName: String): Int = deletePendingApprovalBlocking(packageName)
    override fun deletePendingApprovalBlocking(packageName: String): Int = synchronized(lock) {
        val removed = store.remove(packageName)
        if (removed != null) {
            emitLocked()
            1
        } else 0
    }
    override fun clearAllPendingApprovalsBlocking(): Int = synchronized(lock) {
        val n = store.size
        store.clear()
        emitLocked()
        n
    }
}
