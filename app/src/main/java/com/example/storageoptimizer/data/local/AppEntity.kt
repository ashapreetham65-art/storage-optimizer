package com.example.storageoptimizer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey val packageName: String,
    val name:        String,
    val apkSize:     Long,
    val dataSize:    Long,
    val versionName: String,
    val installedAt: Long,
    val lastUsed:    Long
    // icon and isSystemApp are NOT stored — derived at runtime
)