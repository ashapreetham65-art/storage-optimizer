package com.example.storageoptimizer.data

import android.net.Uri

data class FileItem(
    val id:           Long,
    val uri:          Uri,
    val name:         String,
    val size:         Long,
    val mimeType:     String,
    val dateModified: Long,   // seconds since epoch
    val dateAdded:    Long,   // seconds since epoch
    val path:         String  // for display purposes
)