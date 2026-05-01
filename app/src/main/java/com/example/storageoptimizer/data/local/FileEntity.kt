package com.example.storageoptimizer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val id: Long,
    val uri:            String,
    val name:           String,
    val size:           Long,
    val mimeType:       String,
    val dateModified:   Long,
    val dateAdded:      Long,
    val path:           String,
    val hash:           Long?
)