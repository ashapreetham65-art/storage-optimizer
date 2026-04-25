package com.example.storageoptimizer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val id: Long,
    val uri:            String,
    val hash:           Long?,
    val size:           Long,
    val dateModified:   Long,   // seconds since epoch — V10 change detection
    val dateAdded:      Long    // seconds since epoch — V11 Groups date-bucketing
)