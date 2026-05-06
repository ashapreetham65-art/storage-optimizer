package com.example.storageoptimizer.ui.theme.apps

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.storageoptimizer.data.AppItem
import com.example.storageoptimizer.data.MainViewModel
import com.example.storageoptimizer.engine.AppEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ── Palette ───────────────────────────────────────────────────────────────────
private val BgTop        = Color(0xFF0F1422)
private val BgBottom     = Color(0xFF1A1F35)
private val TabPillBg    = Color(0xFF1C2340)
private val AccentBlue   = Color(0xFF5B8DEF)
private val AccentCyan   = Color(0xFF26C6DA)
private val AccentIndigo = Color(0xFF5C6BC0)
private val CardBg       = Color(0xFF1E2540)
private val CardBorder1  = Color(0xFF3A4468)
private val CardBorder2  = Color(0xFF4D5580)
private val SortPillBg   = Color(0xFF1C2340)
private val SubText      = Color(0xFF8A90A8)
private val HeaderText   = Color(0xFFEEF0F8)
private val CountPillBg  = Color(0xFF1E2A4A)
private val IconBg       = Color(0xFF252D4A)

// ── Sort options ──────────────────────────────────────────────────────────────
// Each category has two directions; the arrow button toggles between them.
enum class AppSortCategory { SIZE, NAME, INSTALLED, LAST_USED }
enum class AppSortDir       { ASC, DESC }

// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    viewModel:      MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context        = LocalContext.current
    val apps           by viewModel.apps.collectAsState()
    val isScanning     by viewModel.isScanningApps.collectAsState()

    var sortCategory by remember { mutableStateOf(AppSortCategory.SIZE) }
    var sortDir      by remember { mutableStateOf(AppSortDir.DESC) }
    var dropdownOpen by remember { mutableStateOf(false) }
    val listState    = rememberLazyListState()

    val lastScannedAt by viewModel.lastScannedAt.collectAsState()

    LaunchedEffect(sortCategory, sortDir) { listState.scrollToItem(0) }

    // Only refresh when entering this screen if user has already done a scan before.
    LaunchedEffect(Unit) {
        if (lastScannedAt != null && !isScanning) viewModel.scanApps(context)
    }

    val sortedApps = remember(apps, sortCategory, sortDir) {
        val asc = sortDir == AppSortDir.ASC
        when (sortCategory) {
            AppSortCategory.SIZE      ->
                if (asc) apps.sortedBy { it.apkSize + it.dataSize } else apps.sortedByDescending { it.apkSize + it.dataSize }
            AppSortCategory.NAME      ->
                if (asc) apps.sortedBy { it.name.lowercase() } else apps.sortedByDescending { it.name.lowercase() }
            AppSortCategory.INSTALLED ->
                if (asc) apps.sortedBy { it.installedAt } else apps.sortedByDescending { it.installedAt }
            AppSortCategory.LAST_USED ->
                if (asc) apps.sortedBy { if (it.lastUsed > 0L) it.lastUsed else Long.MAX_VALUE }
                else     apps.sortedByDescending { it.lastUsed }
        }
    }

    val sortLabel = when (sortCategory) {
        AppSortCategory.SIZE      -> "Size"
        AppSortCategory.NAME      -> "Name"
        AppSortCategory.INSTALLED -> "Installed"
        AppSortCategory.LAST_USED -> "Last Used"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(8.dp))

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier         = Modifier
                        .size(44.dp).clip(CircleShape)
                        .background(TabPillBg)
                        .clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color.White,
                        modifier           = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                // Title + count pill
                Row(
                    modifier          = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text       = "Apps",
                        color      = HeaderText,
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (apps.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CountPillBg)
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        listOf(AccentIndigo.copy(alpha = 0.4f), AccentCyan.copy(alpha = 0.4f))
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text       = "${apps.size}",
                                    color      = AccentCyan,
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text       = "apps",
                                    color      = HeaderText,
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text     = formatBytes(apps.sumOf { it.apkSize + it.dataSize }),
                            color    = SubText,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Scanning indicator ─────────────────────────────────────────────
            if (isScanning) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color       = AccentCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Scanning apps...", color = SubText, fontSize = 13.sp)
                }
            }

            // ── Usage Access permission banner ────────────────────────────────────
            val hasUsageAccess = remember { AppEngine.hasUsageStatsPermission(context) }

            if (!hasUsageAccess) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E2540))
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(listOf(AccentCyan.copy(alpha = 0.4f), AccentIndigo.copy(alpha = 0.4f))),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⚠️", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text       = "Usage Access required for accurate sizes",
                                color      = HeaderText,
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text     = "Tap to grant → find your app → enable",
                                color    = SubText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // ── Sort bar: 4-category dropdown + arrow direction toggle ────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // Category pill
                Box(
                    modifier = Modifier
                        .weight(1f).height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SortPillBg)
                        .clickable { dropdownOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text       = "Sort: $sortLabel",
                            color      = Color.White,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector        = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Sort options",
                            tint               = Color.White,
                            modifier           = Modifier.size(18.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded         = dropdownOpen,
                    onDismissRequest = { dropdownOpen = false },
                    modifier         = Modifier.background(Color(0xFF1C2340))
                ) {
                    listOf(
                        AppSortCategory.SIZE      to "Size",
                        AppSortCategory.NAME      to "Name",
                        AppSortCategory.INSTALLED to "Installed",
                        AppSortCategory.LAST_USED to "Last Used"
                    ).forEach { (cat, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text       = label,
                                    color      = if (sortCategory == cat) AccentCyan else Color.White,
                                    fontSize   = 14.sp,
                                    fontWeight = if (sortCategory == cat) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            onClick = { sortCategory = cat; dropdownOpen = false },
                            trailingIcon = {
                                if (sortCategory == cat) {
                                    Icon(
                                        imageVector        = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint               = AccentCyan,
                                        modifier           = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                // Direction arrow toggle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SortPillBg)
                        .clickable {
                            sortDir = if (sortDir == AppSortDir.DESC) AppSortDir.ASC else AppSortDir.DESC
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (sortDir == AppSortDir.DESC) "↓" else "↑",
                        color      = AccentCyan,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }


            // ── Empty state ────────────────────────────────────────────────────
            if (!isScanning && apps.isEmpty()) {
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "No apps found", color = SubText, fontSize = 15.sp)
                }
                return@Column
            }

            // ── App list ───────────────────────────────────────────────────────
            LazyColumn(
                state          = listState,
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = sortedApps, key = { it.packageName }) { app ->
                    AppCard(
                        app         = app,
                        sortCategory = sortCategory,
                        sortDir      = sortDir,
                        onClick     = {
                            // Open system App Info page
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", app.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                        onLongClick = {
                            // Launch system uninstall dialog
                            context.startActivity(
                                Intent(Intent.ACTION_DELETE).apply {
                                    data = Uri.fromParts("package", app.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single app card
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    app:        AppItem,
    sortCategory: AppSortCategory,
    sortDir:     AppSortDir,
    onClick:    () -> Unit,
    onLongClick: () -> Unit
) {
    // Convert Drawable to ImageBitmap once
    val iconBitmap = remember(app.packageName) {
        runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(
                app.icon.intrinsicWidth.coerceAtLeast(1),
                app.icon.intrinsicHeight.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            android.graphics.Canvas(bmp).also { c ->
                app.icon.setBounds(0, 0, c.width, c.height)
                app.icon.draw(c)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    // Secondary info shown on the right — changes based on sort category
    val secondaryLabel = when (sortCategory) {
        AppSortCategory.INSTALLED ->
            "Installed ${formatDate(app.installedAt)}"
        AppSortCategory.LAST_USED ->
            if (app.lastUsed > 0L) "Used ${timeAgo(app.lastUsed)}" else "Never used"
        else -> formatBytes(app.apkSize + app.dataSize)
    }

    val accentColor = accentForSize(app.apkSize + app.dataSize)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(CardBorder1, CardBorder2)),
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF12151F))
                    .drawBehind {
                        drawRoundRect(
                            color        = accentColor.copy(alpha = 0.2f),
                            topLeft      = Offset.Zero,
                            size         = size,
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style        = Stroke(width = 1.dp.toPx())
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap             = iconBitmap,
                        contentDescription = app.name,
                        modifier           = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )
                }
            }

            // Name + meta
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = app.name,
                    color      = HeaderText,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // Version pill
                    if (app.versionName.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text       = "v${app.versionName}",
                                color      = accentColor,
                                fontSize   = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    // APK size always shown in subtext
                    Text(
                        text     = formatBytes(app.apkSize + app.dataSize),
                        color    = SubText,
                        fontSize = 11.sp
                    )
                }
            }

            // Right-side contextual label
            Text(
                text       = secondaryLabel,
                color      = accentColor,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.widthIn(max = 90.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Accent colour grades by APK size — red=huge, orange=large, blue=medium, muted=small */
private fun accentForSize(bytes: Long): Color = when {
    bytes >= 500L * 1024 * 1024 -> Color(0xFFEF5350)   // ≥ 500 MB — red
    bytes >= 100L * 1024 * 1024 -> Color(0xFFFF9800)   // ≥ 100 MB — orange
    bytes >= 50L  * 1024 * 1024 -> Color(0xFF26C6DA)   // ≥  50 MB — cyan
    else                        -> Color(0xFF5B8DEF)   // < 50 MB  — blue
}

private fun formatBytes(bytes: Long): String {
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

private fun formatDate(epochMs: Long): String {
    if (epochMs == 0L) return "unknown"
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))
}

private fun timeAgo(epochMs: Long): String {
    if (epochMs == 0L) return "never"
    val diff  = System.currentTimeMillis() - epochMs
    val days  = TimeUnit.MILLISECONDS.toDays(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val mins  = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        days  > 365 -> "${days / 365}y ago"
        days  > 30  -> "${days / 30}mo ago"
        days  > 0   -> "${days}d ago"
        hours > 0   -> "${hours}h ago"
        mins  > 0   -> "${mins}m ago"
        else        -> "just now"
    }
}