package com.example.storageoptimizer.ui.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import com.example.storageoptimizer.data.FileItem
import com.example.storageoptimizer.data.MainViewModel

// ── Palette ───────────────────────────────────────────────────────────────────
private val BgTop        = Color(0xFF0F1422)
private val BgBottom     = Color(0xFF1A1F35)
private val TabPillBg    = Color(0xFF1C2340)
private val TabActive1   = Color(0xFF5B8DEF)
private val TabActive2   = Color(0xFF9C6FE4)
private val TabTextAct   = Color.White
private val TabTextInact = Color(0xFF8A90A8)
private val AccentBlue   = Color(0xFF5B8DEF)
private val AccentPurple = Color(0xFF9C6FE4)
private val CardBg       = Color(0xFF1E2540)
private val CardBorder1  = Color(0xFF3A4468)
private val CardBorder2  = Color(0xFF4D5580)
private val SortPillBg   = Color(0xFF1C2340)
private val SubText      = Color(0xFF8A90A8)
private val HeaderText   = Color(0xFFEEF0F8)
private val CountPillBg  = Color(0xFF1E2A4A)
private val IconBg       = Color(0xFF252D4A)
private val OverlayDim   = Color(0x88000000)

// ── Tabs ──────────────────────────────────────────────────────────────────────
private enum class FileTab { ALL_FILES, DUPLICATES }

// ── Sort options ──────────────────────────────────────────────────────────────
enum class FileSortOrder {
    TYPE, SIZE, DATE, NAME
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

enum class FileDupSortOrder {
    MOST_DUPLICATES, LEAST_DUPLICATES, SIZE_LARGE, SIZE_SMALL
}

// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FilesScreen(
    viewModel:      MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context          = LocalContext.current
    val files            by viewModel.files.collectAsState()
    val isScanningFiles  by viewModel.isScanningFiles.collectAsState()
    val isHashingFiles   by viewModel.isHashingFiles.collectAsState()
    val dupGroups        by viewModel.fileDuplicateGroups.collectAsState()

    var activeTab                by remember { mutableStateOf(FileTab.ALL_FILES) }
    var sortOrder                by remember { mutableStateOf(FileSortOrder.TYPE) }
    var sortDirection            by remember { mutableStateOf(SortDirection.ASCENDING) }
    var dupSortOrder             by remember { mutableStateOf(FileDupSortOrder.MOST_DUPLICATES) }
    var dupSelectedIds           by remember { mutableStateOf(setOf<Long>()) }
    var isDeletingFiles          by remember { mutableStateOf(false) }
    var showDeleteDialog         by remember { mutableStateOf(false) }
    var allFilesSelectedIds      by remember { mutableStateOf(setOf<Long>()) }
    var isDeletingAllFiles       by remember { mutableStateOf(false) }
    var showAllFilesDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(dupGroups) {
        dupSelectedIds = viewModel.autoSelectedFileDuplicateIds()
    }

    // ── Permission handling ───────────────────────────────────────────────────
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    val legacyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.scanFiles(context.contentResolver) }

    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (hasAllFilesAccess()) viewModel.scanFiles(context.contentResolver) }

    fun requestPermissionAndScan() {
        when {
            hasAllFilesAccess() -> viewModel.scanFiles(context.contentResolver)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                allFilesSettingsLauncher.launch(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            else -> legacyPermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ── Grouped lists ─────────────────────────────────────────────────────────
    val groupedFiles: List<Pair<String, List<FileItem>>> = remember(files, sortOrder, sortDirection) {
        val ascending = sortDirection == SortDirection.ASCENDING
        when (sortOrder) {
            FileSortOrder.TYPE -> groupedByType(files)
            FileSortOrder.SIZE -> groupedBySize(files).let { if (ascending) it.reversed() else it }
            FileSortOrder.DATE -> groupedByDate(files).let { if (ascending) it else it.reversed() }
            FileSortOrder.NAME -> groupedByName(files).let { if (ascending) it else it.reversed() }
        }
    }

    val sortedDupGroups = remember(dupGroups, dupSortOrder) {
        when (dupSortOrder) {
            FileDupSortOrder.MOST_DUPLICATES  -> dupGroups.sortedByDescending { it.size }
            FileDupSortOrder.LEAST_DUPLICATES -> dupGroups.sortedBy { it.size }
            FileDupSortOrder.SIZE_LARGE       -> dupGroups.sortedByDescending { g -> g.sumOf { it.size } }
            FileDupSortOrder.SIZE_SMALL       -> dupGroups.sortedBy { g -> g.sumOf { it.size } }
        }
    }

    // ── Root ──────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(8.dp))

            // ── Top bar with sliding tab pill ─────────────────────────────────
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
                FileSlidingTabPill(
                    activeTab     = activeTab,
                    onTabSelected = { activeTab = it },
                    modifier      = Modifier
                        .weight(1f)
                        .height(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Status indicators ─────────────────────────────────────────────
            if (isScanningFiles) {
                StatusRow(text = "Scanning files...", color = AccentBlue)
            } else if (isHashingFiles && activeTab == FileTab.DUPLICATES) {
                StatusRow(text = "Analysing for duplicates...", color = AccentPurple)
            }

            // ── Tab content ───────────────────────────────────────────────────
            when (activeTab) {
                FileTab.ALL_FILES -> AllFilesTab(
                    groupedFiles      = groupedFiles,
                    isScanning        = isScanningFiles,
                    isDeleting        = isDeletingAllFiles,
                    sortOrder         = sortOrder,
                    sortDirection     = sortDirection,
                    selectedIds       = allFilesSelectedIds,
                    onSortChange      = { sortOrder = it },
                    onDirectionToggle = {
                        sortDirection =
                            if (sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING
                            else SortDirection.ASCENDING
                    },
                    onSelectionChange = { allFilesSelectedIds = it },
                    onOpenFile        = { file ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(file.uri, file.mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                    },
                    onDelete          = {
                        if (allFilesSelectedIds.isNotEmpty()) showAllFilesDeleteDialog = true
                    }
                )

                FileTab.DUPLICATES -> DuplicatesTab(
                    groups            = sortedDupGroups,
                    selectedIds       = dupSelectedIds,
                    isHashing         = isHashingFiles,
                    isDeleting        = isDeletingFiles,
                    sortOrder         = dupSortOrder,
                    onSortChange      = { dupSortOrder = it },
                    onSelectionChange = { dupSelectedIds = it },
                    onUnselectGroup   = { ids -> dupSelectedIds = dupSelectedIds - ids },
                    onDelete          = {
                        if (dupSelectedIds.isNotEmpty()) showDeleteDialog = true
                    },
                    onOpenFile        = { file ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(file.uri, file.mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                    }
                )
            }
        }

        // ── Delete confirmation dialog (Duplicates tab) ───────────────────────
        if (showDeleteDialog) {
            FileDeleteConfirmDialog(
                count     = dupSelectedIds.size,
                onConfirm = {
                    showDeleteDialog = false
                    isDeletingFiles  = true
                    val toDelete     = dupSelectedIds
                    viewModel.deleteFiles(toDelete, context.contentResolver) {
                        isDeletingFiles = false
                        dupSelectedIds  = viewModel.autoSelectedFileDuplicateIds()
                    }
                },
                onDismiss = { showDeleteDialog = false }
            )
        }

        // ── Delete confirmation dialog (All Files tab) ────────────────────────
        if (showAllFilesDeleteDialog) {
            FileDeleteConfirmDialog(
                count     = allFilesSelectedIds.size,
                onConfirm = {
                    showAllFilesDeleteDialog = false
                    isDeletingAllFiles       = true
                    val toDelete             = allFilesSelectedIds
                    viewModel.deleteFiles(toDelete, context.contentResolver) {
                        isDeletingAllFiles  = false
                        allFilesSelectedIds = emptySet()
                    }
                },
                onDismiss = { showAllFilesDeleteDialog = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sliding tab pill
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FileSlidingTabPill(
    activeTab:     FileTab,
    onTabSelected: (FileTab) -> Unit,
    modifier:      Modifier = Modifier
) {
    val tabs = listOf(
        FileTab.ALL_FILES  to "All Files",
        FileTab.DUPLICATES to "Duplicates"
    )
    val tabCount    = tabs.size
    val activeIndex = tabs.indexOfFirst { it.first == activeTab }.toFloat()

    val indicatorPos by animateFloatAsState(
        targetValue   = activeIndex,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ),
        label = "fileTabIndicator"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(TabPillBg)
            .drawBehind {
                val pad        = 4.dp.toPx()
                val slotWidth  = (size.width - pad * 2) / tabCount
                val pillHeight = size.height - pad * 2
                val cornerPx   = 18.dp.toPx()
                val pillX      = pad + indicatorPos * slotWidth

                drawRoundRect(
                    brush        = Brush.horizontalGradient(
                        colors = listOf(TabActive1, TabActive2),
                        startX = pillX,
                        endX   = pillX + slotWidth
                    ),
                    topLeft      = Offset(pillX, pad),
                    size         = Size(slotWidth, pillHeight),
                    cornerRadius = CornerRadius(cornerPx, cornerPx)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { (tab, label) ->
                val isActive = activeTab == tab
                Box(
                    modifier         = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = label,
                        color      = if (isActive) TabTextAct else TabTextInact,
                        fontSize   = 14.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status indicator row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StatusRow(text: String, color: Color) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier    = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color       = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = SubText, fontSize = 13.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ALL FILES tab
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AllFilesTab(
    groupedFiles:      List<Pair<String, List<FileItem>>>,
    isScanning:        Boolean,
    isDeleting:        Boolean,
    sortOrder:         FileSortOrder,
    sortDirection:     SortDirection,
    selectedIds:       Set<Long>,
    onSortChange:      (FileSortOrder) -> Unit,
    onDirectionToggle: () -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpenFile:        (FileItem) -> Unit,
    onDelete:          () -> Unit
) {
    var dropdownOpen   by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery    by remember { mutableStateOf("") }
    val listState      = rememberLazyListState()

    // Filter groups by search query while preserving group structure
    val displayedGroups: List<Pair<String, List<FileItem>>> = remember(groupedFiles, searchQuery, isSearchActive) {
        if (isSearchActive && searchQuery.isNotBlank()) {
            groupedFiles.mapNotNull { (header, items) ->
                val filtered = items.filter { it.name.contains(searchQuery, ignoreCase = true) }
                if (filtered.isNotEmpty()) header to filtered else null
            }
        } else {
            groupedFiles
        }
    }

    LaunchedEffect(sortOrder) { listState.scrollToItem(0) }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ── Sort / Search bar ─────────────────────────────────────────────
            if (isSearchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(SortPillBg),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier          = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.Search,
                                contentDescription = null,
                                tint               = AccentBlue,
                                modifier           = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value         = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine    = true,
                                textStyle     = androidx.compose.ui.text.TextStyle(
                                    color    = Color.White,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(AccentBlue),
                                modifier    = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text     = "Search files...",
                                            color    = SubText,
                                            fontSize = 14.sp
                                        )
                                    }
                                    inner()
                                }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SortPillBg)
                            .clickable { isSearchActive = false; searchQuery = "" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Close,
                            contentDescription = "Close search",
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // Direction toggle — hidden for TYPE (order is fixed)
                    if (sortOrder != FileSortOrder.TYPE) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SortPillBg)
                                .clickable { onDirectionToggle() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text       = if (sortDirection == SortDirection.ASCENDING) "↑" else "↓",
                                color      = AccentBlue,
                                fontSize   = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
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
                                    text       = "Group By: ${sortLabel(sortOrder)}",
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
                                FileSortOrder.TYPE to "Type",
                                FileSortOrder.SIZE to "Size",
                                FileSortOrder.DATE to "Date",
                                FileSortOrder.NAME to "Name"
                            ).forEach { (order, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text       = label,
                                            color      = if (sortOrder == order) AccentBlue else Color.White,
                                            fontSize   = 14.sp,
                                            fontWeight = if (sortOrder == order) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { onSortChange(order); dropdownOpen = false },
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

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SortPillBg)
                            .clickable { isSearchActive = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Search,
                            contentDescription = "Search files",
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (!isScanning && displayedGroups.isEmpty()) {
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text     = if (isSearchActive) "No files match \"$searchQuery\""
                        else "No files found",
                        color    = SubText,
                        fontSize = 15.sp
                    )
                }
                return@Column
            }

            // ── Grouped file list ─────────────────────────────────────────────
            LazyColumn(
                state          = listState,
                modifier       = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = 4.dp,
                    bottom = 100.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                displayedGroups.forEachIndexed { groupIdx, (header, items) ->

                    // ── Section header
                    item(key = "hdr_${groupIdx}_$header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top    = if (groupIdx == 0) 4.dp else 20.dp,
                                    bottom = 8.dp
                                ),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Coloured accent line
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(TabActive1, TabActive2)
                                            )
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text       = header,
                                    color      = HeaderText,
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.3.sp
                                )
                            }
                            // Item count pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CountPillBg)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text       = "${items.size}",
                                    color      = AccentBlue,
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // ── Files in this group
                    items(items = items, key = { "f_${groupIdx}_${it.id}" }) { file ->
                        val isSelected = file.id in selectedIds
                        FileCard(
                            file           = file,
                            isSelected     = isSelected,
                            isInSelectMode = selectedIds.isNotEmpty(),
                            onClick        = { onOpenFile(file) },
                            onLongClick    = { onSelectionChange(selectedIds + file.id) },
                            onCircleClick  = {
                                onSelectionChange(
                                    if (isSelected) selectedIds - file.id
                                    else            selectedIds + file.id
                                )
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // ── Animated floating delete button ───────────────────────────────────
        AnimatedVisibility(
            visible  = selectedIds.isNotEmpty(),
            enter    = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) +
                    fadeIn(animationSpec = tween(300)),
            exit     = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) +
                    fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "allFilesDeleteGrad")
            val shimmer by infiniteTransition.animateFloat(
                initialValue  = 0f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(
                    animation  = keyframes {
                        durationMillis = 4000
                        0.0f at 0; 1.0f at 1500; 1.0f at 4000
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "allFilesShimmer"
            )
            val animatedBrush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f                               to Color(0xFFEE2E2E),
                    (shimmer - 0.1f).coerceAtLeast(0f) to Color(0xFFE53935),
                    shimmer                            to Color(0xFFFF5252),
                    (shimmer + 0.1f).coerceAtMost(1f)  to Color(0xFFE53935),
                    1.0f                               to Color(0xFFEE2E2E)
                ),
                start = Offset(x = lerp(-200f, 800f, shimmer), y = 0f),
                end   = Offset(800f, 0f)
            )
            Button(
                onClick        = onDelete,
                enabled        = !isDeleting,
                shape          = CircleShape,
                colors         = ButtonDefaults.buttonColors(
                    containerColor         = Color.Transparent,
                    disabledContainerColor = Color(0x66C62828)
                ),
                elevation      = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
                modifier       = Modifier
                    .padding(horizontal = 48.dp, vertical = 24.dp)
                    .height(58.dp)
                    .background(brush = animatedBrush, shape = CircleShape)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Deleting...", color = Color.White,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Delete, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Delete  (${selectedIds.size})", color = Color.White,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DUPLICATES tab
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DuplicatesTab(
    groups:            List<List<FileItem>>,
    selectedIds:       Set<Long>,
    isHashing:         Boolean,
    isDeleting:        Boolean,
    sortOrder:         FileDupSortOrder,
    onSortChange:      (FileDupSortOrder) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit,
    onDelete:          () -> Unit,
    onOpenFile:        (FileItem) -> Unit
) {
    var dropdownOpen by remember { mutableStateOf(false) }
    val listState    = rememberLazyListState()

    LaunchedEffect(sortOrder) { listState.scrollToItem(0) }

    val sortLabel = when (sortOrder) {
        FileDupSortOrder.MOST_DUPLICATES  -> "Most Duplicates"
        FileDupSortOrder.LEAST_DUPLICATES -> "Least Duplicates"
        FileDupSortOrder.SIZE_LARGE       -> "Size (Large)"
        FileDupSortOrder.SIZE_SMALL       -> "Size (Small)"
    }

    if (!isHashing && groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "No duplicate files found", color = SubText, fontSize = 15.sp)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

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
                        FileDupSortOrder.MOST_DUPLICATES  to "Most Duplicates",
                        FileDupSortOrder.LEAST_DUPLICATES to "Least Duplicates",
                        FileDupSortOrder.SIZE_LARGE       to "Size (Large)",
                        FileDupSortOrder.SIZE_SMALL       to "Size (Small)"
                    ).forEach { (order, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text       = label,
                                    color      = if (sortOrder == order) AccentBlue else Color.White,
                                    fontSize   = 14.sp,
                                    fontWeight = if (sortOrder == order) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            onClick = { onSortChange(order); dropdownOpen = false },
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

            LazyColumn(
                state          = listState,
                modifier       = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) {
                        item(key = "fdup_gap_$groupIndex") {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    fileDuplicateGroupCard(
                        groupIndex        = groupIndex,
                        group             = group,
                        selectedIds       = selectedIds,
                        showSizeHeader    = sortOrder == FileDupSortOrder.SIZE_LARGE ||
                                sortOrder == FileDupSortOrder.SIZE_SMALL,
                        onSelectionChange = onSelectionChange,
                        onUnselectGroup   = onUnselectGroup,
                        onOpenFile        = onOpenFile
                    )
                }
            }
        }

        AnimatedVisibility(
            visible  = selectedIds.isNotEmpty(),
            enter    = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) +
                    fadeIn(animationSpec = tween(300)),
            exit     = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) +
                    fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "fileDeleteGradient")
            val shimmer by infiniteTransition.animateFloat(
                initialValue  = 0f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(
                    animation  = keyframes {
                        durationMillis = 4000
                        0.0f at 0; 1.0f at 1500; 1.0f at 4000
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "fileShimmer"
            )
            val animatedBrush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f                               to Color(0xFFEE2E2E),
                    (shimmer - 0.1f).coerceAtLeast(0f) to Color(0xFFE53935),
                    shimmer                            to Color(0xFFFF5252),
                    (shimmer + 0.1f).coerceAtMost(1f)  to Color(0xFFE53935),
                    1.0f                               to Color(0xFFEE2E2E)
                ),
                start = Offset(x = lerp(-200f, 800f, shimmer), y = 0f),
                end   = Offset(800f, 0f)
            )
            Button(
                onClick        = onDelete,
                enabled        = !isDeleting,
                shape          = CircleShape,
                colors         = ButtonDefaults.buttonColors(
                    containerColor         = Color.Transparent,
                    disabledContainerColor = Color(0x66C62828)
                ),
                elevation      = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
                modifier       = Modifier
                    .padding(horizontal = 48.dp, vertical = 24.dp)
                    .height(58.dp)
                    .background(brush = animatedBrush, shape = CircleShape)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Deleting...", color = Color.White,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Delete, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Delete  (${selectedIds.size})", color = Color.White,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// File duplicate group card
// ─────────────────────────────────────────────────────────────────────────────
private fun LazyListScope.fileDuplicateGroupCard(
    groupIndex:        Int,
    group:             List<FileItem>,
    selectedIds:       Set<Long>,
    showSizeHeader:    Boolean,
    onSelectionChange: (Set<Long>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit,
    onOpenFile:        (FileItem) -> Unit
) {
    val sortedGroup  = group.sortedByDescending { it.size }
    val groupIds     = sortedGroup.map { it.id }.toSet()
    val selectedSize = sortedGroup.filter { it.id in selectedIds }.sumOf { it.size }

    item(key = "fdup_hdr_$groupIndex") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(CardBg)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(listOf(CardBorder1, CardBorder2)),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
        ) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CountPillBg)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(AccentBlue.copy(alpha = 0.35f), AccentPurple.copy(alpha = 0.35f))
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    when {
                        showSizeHeader && selectedSize > 0L -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatBytes(selectedSize), color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("selected", color = HeaderText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        showSizeHeader -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatBytes(sortedGroup.sumOf { it.size }), color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("total size", color = HeaderText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${group.size}", color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("duplicates found", color = HeaderText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(IconBg)
                        .clickable { onUnselectGroup(groupIds) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = "Unselect group",
                        tint               = Color(0xFFEF5350),
                        modifier           = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    sortedGroup.forEachIndexed { fileIndex, file ->
        val isLastItem = fileIndex == sortedGroup.lastIndex
        val isSelected = file.id in selectedIds
        val category   = mimeCategory(file.mimeType)
        val (accent, bgColor) = categoryColors(category)

        item(key = "fdup_${groupIndex}_file_${file.id}") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = CardBg,
                        shape = if (isLastItem)
                            RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        else
                            RoundedCornerShape(0.dp)
                    )
                    .drawBehind {
                        val strokeWidth  = 1.dp.toPx()
                        val cornerRadius = 16.dp.toPx()
                        val paint = Paint().apply {
                            style            = PaintingStyle.Stroke
                            this.strokeWidth = strokeWidth
                            shader = android.graphics.LinearGradient(
                                0f, 0f, size.width, size.height,
                                android.graphics.Color.parseColor("#FF3A4468"),
                                android.graphics.Color.parseColor("#FF4D5580"),
                                android.graphics.Shader.TileMode.CLAMP
                            )
                        }
                        drawIntoCanvas { canvas ->
                            if (isLastItem) {
                                canvas.drawPath(Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(0f, size.height - cornerRadius)
                                    quadraticBezierTo(0f, size.height, cornerRadius, size.height)
                                    lineTo(size.width - cornerRadius, size.height)
                                    quadraticBezierTo(size.width, size.height, size.width, size.height - cornerRadius)
                                    lineTo(size.width, 0f)
                                }, paint)
                            } else {
                                canvas.drawLine(Offset(0f, 0f), Offset(0f, size.height), paint)
                                canvas.drawLine(Offset(size.width, 0f), Offset(size.width, size.height), paint)
                            }
                        }
                    }
                    .clickable { onOpenFile(file) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier         = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .drawBehind {
                                drawRoundRect(
                                    color        = accent.copy(alpha = 0.35f),
                                    topLeft      = Offset.Zero,
                                    size         = size,
                                    cornerRadius = CornerRadius(10.dp.toPx()),
                                    style        = Stroke(width = 1.dp.toPx())
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = fileEmoji(category), fontSize = 18.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text       = file.name,
                            color      = HeaderText,
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(bgColor)
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text       = category,
                                    color      = accent,
                                    fontSize   = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(text = formatBytes(file.size), color = SubText, fontSize = 11.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) AccentBlue.copy(alpha = 0.15f) else IconBg)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AccentBlue else Color(0xFF3A4468),
                                shape = CircleShape
                            )
                            .clickable {
                                onSelectionChange(
                                    if (isSelected) selectedIds - file.id else selectedIds + file.id
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector        = Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint               = AccentBlue,
                                modifier           = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Delete confirmation dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FileDeleteConfirmDialog(
    count:     Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg     = Color(0xFF1C2138)
    val dialogBorder = Color(0xFF2E3555)
    val dangerRed    = Color(0xFFEF5350)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties       = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(24.dp))
                .padding(28.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(dangerRed.copy(alpha = 0.12f))
                        .border(1.dp, dangerRed.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Delete,
                        contentDescription = null,
                        tint               = dangerRed,
                        modifier           = Modifier.size(30.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text       = "Delete ${if (count == 1) "File" else "$count Files"}?",
                    color      = HeaderText,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text      = if (count == 1)
                        "This file will be permanently deleted\nfrom your device."
                    else
                        "These $count files will be permanently\ndeleted from your device.",
                    color     = SubText,
                    fontSize  = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 21.sp
                )
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = onDismiss,
                        shape    = RoundedCornerShape(14.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, dialogBorder),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(
                            text       = "Cancel",
                            color      = HeaderText,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick  = onConfirm,
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = dangerRed),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(
                            text       = "Delete",
                            color      = Color.White,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single file card (All Files tab)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file:           FileItem,
    isSelected:     Boolean,
    isInSelectMode: Boolean,
    onClick:        () -> Unit,
    onLongClick:    () -> Unit,
    onCircleClick:  () -> Unit
) {
    val category = mimeCategory(file.mimeType)
    val (accent, bgColor) = categoryColors(category)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) CardBg.copy(alpha = 0.7f) else CardBg)
            .border(
                width = 1.dp,
                brush = if (isSelected)
                    Brush.linearGradient(listOf(AccentBlue.copy(alpha = 0.6f), AccentPurple.copy(alpha = 0.6f)))
                else
                    Brush.linearGradient(listOf(CardBorder1, CardBorder2)),
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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
                Text(text = fileEmoji(category), fontSize = 20.sp)
            }

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
                    Text(text = formatBytes(file.size), color = SubText, fontSize = 12.sp)
                }
            }

            if (isInSelectMode) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) AccentBlue.copy(alpha = 0.15f) else IconBg)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AccentBlue else Color(0xFF3A4468),
                            shape = CircleShape
                        )
                        .clickable { onCircleClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector        = Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint               = AccentBlue,
                            modifier           = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Grouping helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Groups files by category in the fixed order: PDF → Document → APK → Archive → Spreadsheet → Presentation → Other */
private fun groupedByType(files: List<FileItem>): List<Pair<String, List<FileItem>>> {
    val order = listOf("PDF", "Document", "APK", "Archive", "Spreadsheet", "Presentation", "Other")
    return order.mapNotNull { cat ->
        val group = files.filter { mimeCategory(it.mimeType) == cat }
        if (group.isNotEmpty()) cat to group else null
    }
}

/** Groups files into 10 MB size buckets. The largest file determines how many buckets are needed. */
private fun groupedBySize(files: List<FileItem>): List<Pair<String, List<FileItem>>> {
    if (files.isEmpty()) return emptyList()

    val mb      = 1024L * 1024L
    val maxSize = files.maxOf { it.size }

    data class Bucket(val label: String, val min: Long, val max: Long)

    val buckets = mutableListOf<Bucket>()
    buckets.add(Bucket("Below 1 MB", 0L, mb - 1))

    var lo = 1L
    while (lo * mb <= maxSize) {
        val hi = lo + 9L   // 10 MB wide slices: 1-10, 11-20, 21-30 …
        val label = if (lo == 1L) "1 – 10 MB" else "${lo} – ${hi} MB"
        buckets.add(Bucket(label, lo * mb, hi * mb + mb - 1))
        lo += 10L
    }

    return buckets.mapNotNull { b ->
        val group = files.filter { it.size in b.min..b.max }
        if (group.isNotEmpty()) b.label to group.sortedByDescending { it.size } else null
    }
}

/** Groups files by calendar day (Today / Yesterday / date string), newest bucket first. */
private fun groupedByDate(files: List<FileItem>): List<Pair<String, List<FileItem>>> {
    if (files.isEmpty()) return emptyList()

    val fmt       = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
    val today     = java.util.Calendar.getInstance()
    val yesterday = java.util.Calendar.getInstance()
        .also { it.add(java.util.Calendar.DAY_OF_YEAR, -1) }

    fun calFor(secs: Long): java.util.Calendar =
        java.util.Calendar.getInstance().also { it.timeInMillis = secs * 1_000L }

    fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)

    fun dayKey(secs: Long): Long {
        val c = calFor(secs)
        return c.get(java.util.Calendar.YEAR).toLong() * 10_000L +
                (c.get(java.util.Calendar.MONTH) + 1).toLong() * 100L +
                c.get(java.util.Calendar.DAY_OF_MONTH).toLong()
    }

    fun label(secs: Long): String {
        val c = calFor(secs)
        return when {
            sameDay(c, today)     -> "Today"
            sameDay(c, yesterday) -> "Yesterday"
            else                  -> fmt.format(c.time)
        }
    }

    fun effectiveDate(f: FileItem) = if (f.dateAdded > 0L) f.dateAdded else f.dateModified

    return files
        .groupBy { dayKey(effectiveDate(it)) }
        .entries
        .sortedByDescending { it.key }
        .map { (_, group) ->
            val sorted = group.sortedByDescending { effectiveDate(it) }
            label(effectiveDate(sorted.first())) to sorted
        }
}

/** Groups files alphabetically by the first letter of the filename. */
private fun groupedByName(files: List<FileItem>): List<Pair<String, List<FileItem>>> {
    return files
        .groupBy {
            val first = it.name.uppercase().firstOrNull()
            when {
                first == null          -> "#"
                first.isLetter()       -> first.toString()
                else                   -> "#"   // digits, dots, symbols → "#" bucket
            }
        }
        .entries
        .sortedWith(compareBy {
            // Letters come first in A–Z order, then "#" at the end
            if (it.key == "#") "\uFFFF" else it.key
        })
        .map { (letter, group) -> letter to group.sortedBy { it.name.lowercase() } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Category / display helpers
// ─────────────────────────────────────────────────────────────────────────────

internal fun mimeCategory(mime: String): String = when {
    mime == "application/vnd.android.package-archive"                        -> "APK"
    mime.contains("zip") || mime.contains("rar") || mime.contains("tar") ||
            mime.contains("7z") || mime.contains("compress")                 -> "Archive"
    mime.contains("pdf")                                                     -> "PDF"
    mime.contains("word") || mime.contains("document") ||
            mime == "text/plain" || mime.contains("rtf")                     -> "Document"
    mime.contains("sheet") || mime.contains("excel") || mime.contains("csv")-> "Spreadsheet"
    mime.contains("presentation") || mime.contains("powerpoint")             -> "Presentation"
    else                                                                     -> "Other"
}

private fun sortLabel(order: FileSortOrder): String = when (order) {
    FileSortOrder.TYPE -> "Type"
    FileSortOrder.SIZE -> "Size"
    FileSortOrder.DATE -> "Date"
    FileSortOrder.NAME -> "Name"
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