package com.example.storageoptimizer.data

import android.net.Uri

data class FileItem(
    val id:           Long,
    val uri:          Uri,
    val name:         String,
    val size:         Long,
    val mimeType:     String,
    val dateModified: Long,
    val dateAdded:    Long,
    val path:         String,
    val hash:         Long? = null   // FNV-1a 64-bit hash — must match FileEntity.hash type
)