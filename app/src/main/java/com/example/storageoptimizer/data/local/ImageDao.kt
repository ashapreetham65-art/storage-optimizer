package com.example.storageoptimizer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageDao {

    @Query("SELECT * FROM images ORDER BY id DESC")
    suspend fun getAllImages(): List<ImageEntity>

    // Used by full first-scan: wipe + reinsert everything cleanly.
    @Query("DELETE FROM images")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ImageEntity>)

    // Used by incremental refresh: insert/update only changed rows.
    // REPLACE handles both INSERT (new) and UPDATE (modified) in one call.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(images: List<ImageEntity>)

    // Used after user confirms deletion and after incremental refresh detects removals.
    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}