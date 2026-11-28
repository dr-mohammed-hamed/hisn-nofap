package com.drmohammed.antiporn.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for accountability partners (Guardians and Wards).
 */
@Dao
interface PartnerDao {

    @Query("SELECT * FROM partners ORDER BY created_at DESC")
    fun getAllPartnersFlow(): Flow<List<PartnerEntity>>

    @Query("SELECT * FROM partners ORDER BY created_at DESC")
    suspend fun getAllPartners(): List<PartnerEntity>

    @Query("SELECT * FROM partners ORDER BY created_at DESC")
    fun getAllPartnersBlocking(): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE role = :role ORDER BY created_at DESC")
    fun getPartnersByRoleFlow(role: String): Flow<List<PartnerEntity>>

    @Query("SELECT * FROM partners WHERE role = :role ORDER BY created_at DESC")
    suspend fun getPartnersByRole(role: String): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE role = :role ORDER BY created_at DESC")
    fun getPartnersByRoleBlocking(role: String): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE id = :id ORDER BY created_at DESC")
    suspend fun getPartnersById(id: String): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE id = :id ORDER BY created_at DESC")
    fun getPartnersByIdBlocking(id: String): List<PartnerEntity>

    @Query("SELECT * FROM partners WHERE id = :id AND role = :role LIMIT 1")
    suspend fun getPartnerByIdAndRole(id: String, role: String): PartnerEntity?

    @Query("SELECT * FROM partners WHERE id = :id AND role = :role LIMIT 1")
    fun getPartnerByIdAndRoleBlocking(id: String, role: String): PartnerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePartner(partner: PartnerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePartnerBlocking(partner: PartnerEntity)

    @Query("DELETE FROM partners WHERE id = :id AND role = :role")
    suspend fun deletePartnerByIdAndRole(id: String, role: String): Int

    @Query("DELETE FROM partners WHERE id = :id AND role = :role")
    fun deletePartnerByIdAndRoleBlocking(id: String, role: String): Int

    @Query("UPDATE partners SET display_name = :newName, last_sync_at = :syncTimestamp WHERE id = :id AND role = :role")
    suspend fun updatePartnerDisplayNameByRole(id: String, role: String, newName: String, syncTimestamp: Long): Int

    @Query("UPDATE partners SET display_name = :newName, last_sync_at = :syncTimestamp WHERE id = :id AND role = :role")
    fun updatePartnerDisplayNameByRoleBlocking(id: String, role: String, newName: String, syncTimestamp: Long): Int
}
