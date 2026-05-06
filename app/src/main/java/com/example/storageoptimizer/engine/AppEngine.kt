package com.example.storageoptimizer.engine

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.storage.StorageManager
import com.example.storageoptimizer.data.AppItem
import java.io.File
import java.util.UUID

object AppEngine {

    fun loadApps(context: Context): List<AppItem> {
        val pm = context.packageManager

        val hasPermission = hasUsageStatsPermission(context)
        val lastUsedMap   = if (hasPermission) queryLastUsed(context) else emptyMap()

        val flags = PackageManager.GET_META_DATA
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        return packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null

            val name = try {
                appInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
            } catch (_: Exception) { null } ?: return@mapNotNull null

            val sourceFile = try {
                appInfo.sourceDir?.let { File(it) }
            } catch (_: Exception) { null }
            if (sourceFile == null || !sourceFile.exists()) return@mapNotNull null

            val isSystem        = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

            if (isSystem && !isUpdatedSystem) {
                val hasLauncher = pm.getLaunchIntentForPackage(pkgInfo.packageName) != null
                if (!hasLauncher) return@mapNotNull null
            }

            // ── Get correct per-app storage breakdown ─────────────────────────
            val sizes = getAppStorageSizes(context, pkgInfo.packageName, appInfo)

            AppItem(
                packageName = pkgInfo.packageName,
                name        = name,
                apkSize     = sizes.apkBytes,
                dataSize    = sizes.dataBytes + sizes.cacheBytes,
                versionName = pkgInfo.versionName ?: "",
                installedAt = pkgInfo.firstInstallTime,
                lastUsed    = lastUsedMap[pkgInfo.packageName] ?: 0L,
                icon        = appInfo.loadIcon(pm),
                isSystemApp = isSystem
            )
        }.sortedByDescending { it.apkSize + it.dataSize }
    }

    // ── Storage sizes container ───────────────────────────────────────────────
    private data class AppSizes(
        val apkBytes:   Long,
        val dataBytes:  Long,
        val cacheBytes: Long
    )

    private fun getAppStorageSizes(
        context:     Context,
        packageName: String,
        appInfo:     ApplicationInfo
    ): AppSizes {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only attempt StorageStatsManager if usage stats permission is granted.
            // Without it the call throws SecurityException and falls back to APK-only.
            if (hasUsageStatsPermission(context)) {
                try {
                    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE)
                            as StorageStatsManager
                    val sm  = context.getSystemService(Context.STORAGE_SERVICE)
                            as StorageManager

                    val uuid: UUID = try {
                        sm.getUuidForPath(File(appInfo.sourceDir))
                    } catch (_: Exception) {
                        StorageManager.UUID_DEFAULT
                    }

                    val userHandle: UserHandle =
                        UserHandle.getUserHandleForUid(appInfo.uid)

                    val stats = ssm.queryStatsForPackage(uuid, packageName, userHandle)

                    return AppSizes(
                        apkBytes   = stats.appBytes,
                        dataBytes  = stats.dataBytes,
                        cacheBytes = stats.cacheBytes
                    )
                } catch (e: Exception) {
                    android.util.Log.e("AppEngine", "StorageStats failed for $packageName: ${e.message}")
                }
            }
        }
        return fallbackSizes(appInfo)
    }

    private fun fallbackSizes(appInfo: ApplicationInfo): AppSizes {
        val apkBytes  = try { File(appInfo.sourceDir).length() } catch (_: Exception) { 0L }
        val dataBytes = try { dirSize(File(appInfo.dataDir)) }   catch (_: Exception) { 0L }
        // No reliable cache size without StorageStatsManager
        return AppSizes(apkBytes = apkBytes, dataBytes = dataBytes, cacheBytes = 0L)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists() || !dir.canRead()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    // ── Usage stats ───────────────────────────────────────────────────────────

    fun queryLastUsed(context: Context): Map<String, Long> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager ?: return emptyMap()

            val end   = System.currentTimeMillis()
            val start = end - 1000L * 60 * 60 * 24 * 365

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            if (stats.isNullOrEmpty()) return emptyMap()

            stats
                .groupBy { it.packageName }
                .mapValues { (_, list) -> list.maxOf { it.lastTimeUsed } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager ?: return false
            val end   = System.currentTimeMillis()
            val start = end - 1000L * 60 * 60 * 24
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            !stats.isNullOrEmpty()
        } catch (_: Exception) { false }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        val kb = bytes / 1024.0
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else      -> "${bytes} B"
        }
    }
}