package com.example.storageoptimizer.data

data class AppItem(
    val packageName:   String,       // unique ID
    val name:          String,       // display name
    val apkSize:       Long,         // size of the APK file
    val dataSize:      Long,         // app's data/cache size (needs PACKAGE_USAGE_STATS)
    val versionName:   String,
    val installedAt:   Long,         // epoch ms
    val lastUsed:      Long,         // epoch ms — 0 if unknown
    val icon:          android.graphics.drawable.Drawable,
    val isSystemApp:   Boolean
)