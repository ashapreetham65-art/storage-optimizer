package com.example.storageoptimizer.data

import android.net.Uri

data class ImageItem(
    val id: Long,
    val uri: Uri,
    val hash: Long? = null,
    val size: Long = 0L
)

// Which tab is active inside GalleryScreen
enum class ActiveTab { ALL_IMAGES, DUPLICATES, GROUPS }