package com.example.storageoptimizer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

// Only lightweight scalar fields are persisted.
// No bitmaps, no thumbnails, no full image data.
@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val id:   Long,
    val uri:              String,
    val hash:             Long?,
    val size:             Long
)