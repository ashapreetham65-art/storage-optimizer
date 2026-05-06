package com.example.storageoptimizer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppDao {

    @Query("SELECT * FROM apps")
    suspend fun getAll(): List<AppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(apps: List<AppEntity>)

    @Query("DELETE FROM apps WHERE packageName IN (:packageNames)")
    suspend fun deleteByPackageNames(packageNames: List<String>)

    @Query("DELETE FROM apps")
    suspend fun clearAll()
}