package com.example.storageoptimizer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val id: Long,
    val uri:            String,
    val hash:           Long?,
    val size:           Long,
    val dateModified:   Long    // seconds since epoch — used for change detection in V10
)