package com.example.storageoptimizer.data

import android.net.Uri

data class ImageItem(
    val id:           Long,
    val uri:          Uri,
    val hash:         Long? = null,
    val size:         Long  = 0L,
    val dateModified: Long  = 0L   // seconds since epoch, from MediaStore DATE_MODIFIED
)

// Which tab is active inside GalleryScreen
enum class ActiveTab { ALL_IMAGES, DUPLICATES, GROUPS }