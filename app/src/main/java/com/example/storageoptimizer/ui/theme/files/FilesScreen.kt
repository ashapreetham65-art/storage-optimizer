package com.example.storageoptimizer.ui.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.storageoptimizer.data.FileItem
import com.example.storageoptimizer.data.MainViewModel

// ── Palette — mirrors GalleryScreen / DuplicatesContent ──────────────────────
private val BgTop        = Color(0xFF0F1422)
private val BgBottom     = Color(0xFF1A1F35)
private val TabPillBg    = Color(0xFF1C2340)
private val AccentBlue   = Color(0xFF5B8DEF)
private val AccentPurple = Color(0xFF9C6FE4)
private val CardBg       = Color(0xFF1E2540)
private val CardBorder1  = Color(0xFF3A4468)
private val CardBorder2  = Color(0xFF4D5580)
private val SortPillBg   = Color(0xFF1C2340)
private val SubText      = Color(0xFF8A90A8)
private val HeaderText   = Color(0xFFEEF0F8)
private val CountPillBg  = Color(0xFF1E2A4A)

// ── Sort options ──────────────────────────────────────────────────────────────
enum class FileSortOrder {
    NEWEST,
    OLDEST,
    SIZE_LARGE,
    SIZE_SMALL,
    NAME_AZ,
    NAME_ZA,
    TYPE
}

@Composable
fun FilesScreen(
    viewModel:      MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context         = LocalContext.current
    val files           by viewModel.files.collectAsState()
    val isScanningFiles by viewModel.isScanningFiles.collectAsState()

    var sortOrder    by remember { mutableStateOf(FileSortOrder.NEWEST) }
    var dropdownOpen by remember { mutableStateOf(false) }
    val listState    = rememberLazyListState()

    // ── Permission handling ───────────────────────────────────────────────────
    // Android 11+ (API 30): MANAGE_EXTERNAL_STORAGE lets us read ALL files.
    // Android <= 10 (API 29): READ_EXTERNAL_STORAGE is enough.
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    // Launcher for READ_EXTERNAL_STORAGE runtime dialog (API <= 29 only)
    val legacyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanFiles(context.contentResolver)
    }

    // Launcher that opens the "All files access" Settings screen (API 30+)
    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasAllFilesAccess()) viewModel.scanFiles(context.contentResolver)
    }

    fun requestPermissionAndScan() {
        when {
            hasAllFilesAccess() -> {
                viewModel.scanFiles(context.contentResolver)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                allFilesSettingsLauncher.launch(intent)
            }
            else -> {
                legacyPermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    // Trigger on first entry
    LaunchedEffect(Unit) {
        if (files.isEmpty() && !isScanningFiles) {
            requestPermissionAndScan()
        }
    }

    // Reset scroll on sort change
    LaunchedEffect(sortOrder) {
        listState.scrollToItem(0)
    }

    val sortedFiles = remember(files, sortOrder) {
        when (sortOrder) {
            FileSortOrder.NEWEST     -> files.sortedByDescending {
                if (it.dateAdded > 0L) it.dateAdded else it.dateModified
            }
            FileSortOrder.OLDEST     -> files.sortedBy {
                if (it.dateAdded > 0L) it.dateAdded else it.dateModified
            }
            FileSortOrder.SIZE_LARGE -> files.sortedByDescending { it.size }
            FileSortOrder.SIZE_SMALL -> files.sortedBy { it.size }
            FileSortOrder.NAME_AZ    -> files.sortedBy { it.name.lowercase() }
            FileSortOrder.NAME_ZA    -> files.sortedByDescending { it.name.lowercase() }
            FileSortOrder.TYPE       -> files.sortedBy { mimeCategory(it.mimeType) }
        }
    }

    val sortLabel = when (sortOrder) {
        FileSortOrder.NEWEST     -> "Newest"
        FileSortOrder.OLDEST     -> "Oldest"
        FileSortOrder.SIZE_LARGE -> "Size (Large)"
        FileSortOrder.SIZE_SMALL -> "Size (Small)"
        FileSortOrder.NAME_AZ    -> "Name (A–Z)"
        FileSortOrder.NAME_ZA    -> "Name (Z–A)"
        FileSortOrder.TYPE       -> "Type"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(8.dp))

            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier         = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
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

                // Title + file count pill
                Row(
                    modifier          = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text       = "Files",
                        color      = HeaderText,
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (files.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CountPillBg)
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        listOf(
                                            AccentBlue.copy(alpha = 0.35f),
                                            AccentPurple.copy(alpha = 0.35f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text       = "${files.size}",
                                    color      = AccentBlue,
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text       = "files",
                                    color      = HeaderText,
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (files.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text     = formatBytes(files.sumOf { it.size }),
                                color    = SubText,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Scanning indicator ────────────────────────────────────────────
            if (isScanningFiles) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color       = AccentBlue
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Scanning files...", color = SubText, fontSize = 13.sp)
                }
            }

            // ── Sort dropdown ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SortPillBg)
                        .clickable { dropdownOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text       = "Sort By: $sortLabel",
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
                        FileSortOrder.NEWEST     to "Newest",
                        FileSortOrder.OLDEST     to "Oldest",
                        FileSortOrder.SIZE_LARGE to "Size (Large)",
                        FileSortOrder.SIZE_SMALL to "Size (Small)",
                        FileSortOrder.NAME_AZ    to "Name (A–Z)",
                        FileSortOrder.NAME_ZA    to "Name (Z–A)",
                        FileSortOrder.TYPE       to "Type"
                    ).forEach { (order, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text       = label,
                                    color      = if (sortOrder == order) AccentBlue else Color.White,
                                    fontSize   = 14.sp,
                                    fontWeight = if (sortOrder == order) FontWeight.SemiBold
                                    else FontWeight.Normal
                                )
                            },
                            onClick = {
                                sortOrder    = order
                                dropdownOpen = false
                            },
                            trailingIcon = {
                                if (sortOrder == order) {
                                    Icon(
                                        imageVector        = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint               = AccentBlue,
                                        modifier           = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (!isScanningFiles && files.isEmpty()) {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text     = "No files found",
                        color    = SubText,
                        fontSize = 15.sp
                    )
                }
                return@Column
            }

            // ── File list ─────────────────────────────────────────────────────
            LazyColumn(
                state          = listState,
                modifier       = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = 4.dp,
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = sortedFiles,
                    key   = { it.id }
                ) { file ->
                    FileCard(
                        file    = file,
                        onClick = {
                            // Open file with external app
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(file.uri, file.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) { /* no app to handle */ }
                        }
                    )
                }
            }
        }
    }
}

// ── Single file card ──────────────────────────────────────────────────────────
@Composable
private fun FileCard(
    file:    FileItem,
    onClick: () -> Unit
) {
    val category = mimeCategory(file.mimeType)
    val (accent, bgColor) = categoryColors(category)

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
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // File type icon circle
            Box(
                modifier         = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .drawBehind {
                        drawRoundRect(
                            color        = accent.copy(alpha = 0.35f),
                            topLeft      = Offset.Zero,
                            size         = size,
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style        = Stroke(width = 1.dp.toPx())
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = fileEmoji(category),
                    fontSize   = 20.sp
                )
            }

            // Name + meta
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = file.name,
                    color      = HeaderText,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // Category pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(bgColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text       = category,
                            color      = accent,
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text     = formatBytes(file.size),
                        color    = SubText,
                        fontSize = 12.sp
                    )
                }
            }

            // Size on the right
            Text(
                text       = formatBytes(file.size),
                color      = AccentBlue,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun mimeCategory(mime: String): String = when {
    mime == "application/vnd.android.package-archive"                        -> "APK"
    mime.contains("zip") || mime.contains("rar") || mime.contains("tar") ||
            mime.contains("7z")  || mime.contains("compress")                        -> "Archive"
    mime.contains("pdf")                                                     -> "PDF"
    mime.contains("word") || mime.contains("document") ||
            mime == "text/plain" || mime.contains("rtf")                             -> "Document"
    mime.contains("sheet") || mime.contains("excel") || mime.contains("csv")-> "Spreadsheet"
    mime.contains("presentation") || mime.contains("powerpoint")            -> "Presentation"
    else                                                                     -> "Other"
}

private fun fileEmoji(category: String): String = when (category) {
    "APK"          -> "📦"
    "Archive"      -> "🗜️"
    "PDF"          -> "📄"
    "Document"     -> "📝"
    "Spreadsheet"  -> "📊"
    "Presentation" -> "📑"
    else           -> "📁"
}

private fun categoryColors(category: String): Pair<Color, Color> = when (category) {
    "APK"          -> Color(0xFF66BB6A) to Color(0x1566BB6A)
    "Archive"      -> Color(0xFFFF9800) to Color(0x15FF9800)
    "PDF"          -> Color(0xFFEF5350) to Color(0x15EF5350)
    "Document"     -> Color(0xFF4FC3F7) to Color(0x154FC3F7)
    "Spreadsheet"  -> Color(0xFF26A69A) to Color(0x1526A69A)
    "Presentation" -> Color(0xFFAB47BC) to Color(0x15AB47BC)
    else           -> Color(0xFF9C6FE4) to Color(0x159C6FE4)
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