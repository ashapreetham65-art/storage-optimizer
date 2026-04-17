package com.example.storageoptimizer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageDao {

    // ORDER BY id DESC matches the MediaStore DATE_ADDED DESC order used during scan,
    // so the image list is always newest-first regardless of DB insertion order.
    @Query("SELECT * FROM images ORDER BY id DESC")
    suspend fun getAllImages(): List<ImageEntity>

    // REPLACE strategy: if the same id exists (e.g. after Refresh), it overwrites cleanly.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ImageEntity>)

    // Called before every fresh scan to wipe stale data.
    @Query("DELETE FROM images")
    suspend fun clearAll()

    // Targeted delete after user confirms — no need to re-insert everything.
    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}