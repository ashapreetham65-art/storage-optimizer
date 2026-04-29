package com.example.storageoptimizer.ui.theme.gallery

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.util.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.ActiveTab
import com.example.storageoptimizer.data.ImageItem
import com.example.storageoptimizer.data.MainViewModel

// ── Palette (matches HomeScreen) ─────────────────────────────────────────────
private val BgTop         = Color(0xFF0F1422)
private val BgBottom      = Color(0xFF1A1F35)
private val TabPillBg     = Color(0xFF1C2340)
private val TabActive1    = Color(0xFF5B8DEF)
private val TabActive2    = Color(0xFF9C6FE4)
private val TabTextActive = Color.White
private val TabTextInact  = Color(0xFF8A90A8)
private val SortPillBg    = Color(0xFF1C2340)
private val AccentBlue    = Color(0xFF4FC3F7)
private val CardOverlay   = Color(0x66000000)

// Dialog palette
private val DialogBg      = Color(0xFF1C2138)
private val DialogBorder  = Color(0xFF2E3555)
private val DangerRed     = Color(0xFFEF5350)
private val DangerRedDark = Color(0xFFB71C1C)
private val SubText       = Color(0xFF8A90A8)
private val HeaderText    = Color(0xFFEEF0F8)

//for sorting of images in dropdown
enum class SortOrder { NEWEST, OLDEST, SIZE_LARGE, SIZE_SMALL }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel:      MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context         = LocalContext.current
    val contentResolver = context.contentResolver
    val gridState       = rememberLazyGridState()

    val images        by viewModel.images.collectAsState()
    val exactGroups   by viewModel.exactGroups.collectAsState()
    val similarGroups by viewModel.similarGroups.collectAsState()
    val isScanning    by viewModel.isScanning.collectAsState()

    var activeTab        by remember { mutableStateOf(ActiveTab.ALL_IMAGES) }
    var selectionMode    by remember { mutableStateOf(false) }
    var selectedImages   by remember { mutableStateOf(setOf<Long>()) }
    var viewerOpen       by remember { mutableStateOf(false) }
    var viewerIndex      by remember { mutableStateOf(0) }
    var viewerImages     by remember { mutableStateOf(listOf<ImageItem>()) }
    var dupSelectedIds   by remember { mutableStateOf(setOf<Long>()) }
    var groupSelectedIds by remember { mutableStateOf(setOf<Long>()) }
    var sortOrder        by remember { mutableStateOf(SortOrder.NEWEST) }

    LaunchedEffect(exactGroups) {
        dupSelectedIds = viewModel.autoSelectedDuplicateIds()
    }

    var pendingDeleteIds  by remember { mutableStateOf(setOf<Long>()) }
    var deleteConfirmFlag by remember { mutableStateOf(false) }
    var deleteCancelFlag  by remember { mutableStateOf(false) }
    var isDeleting        by remember { mutableStateOf(false) }

    // ── Custom confirm dialog state ──────────────────────────────────────────
    // We always show our own dialog first, then call MediaStore after the user
    // confirms. This works correctly even when MANAGE_EXTERNAL_STORAGE is held
    // (which causes MediaStore to skip its own system dialog).
    var showDeleteDialog  by remember { mutableStateOf(false) }
    var pendingDeleteQueue by remember { mutableStateOf(setOf<Long>()) }

    // ---------- launchers ----------

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) deleteConfirmFlag = true
        else { pendingDeleteIds = emptySet(); deleteCancelFlag = true }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (isGranted) viewModel.refresh(contentResolver) }

    // ---------- delete helpers ----------

    // Step 1: show our custom confirm dialog
    fun requestDelete(toDelete: Set<Long>) {
        if (toDelete.isEmpty()) return
        pendingDeleteQueue = toDelete
        showDeleteDialog   = true
    }

    // Step 2: called when user taps "Delete" in our dialog
    fun executeDelete(toDelete: Set<Long>) {
        if (toDelete.isEmpty()) return
        pendingDeleteIds = toDelete
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = toDelete.map { id ->
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteRequestLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
            )
            isDeleting = true
        } else {
            isDeleting = true
            viewModel.viewModelScopeDelete(toDelete, contentResolver) { deleteConfirmFlag = true }
        }
    }

    // ---------- effects ----------

    LaunchedEffect(deleteConfirmFlag) {
        if (!deleteConfirmFlag) return@LaunchedEffect
        deleteConfirmFlag = false
        val deletedIds = pendingDeleteIds
        selectedImages   = selectedImages - deletedIds
        if (selectedImages.isEmpty()) selectionMode = false
        dupSelectedIds   = (dupSelectedIds - deletedIds)
            .intersect(viewModel.exactGroups.value.flatten().map { it.id }.toSet())
        groupSelectedIds = (groupSelectedIds - deletedIds)
            .intersect(viewModel.similarGroups.value.flatten().map { it.id }.toSet())
        viewModel.onDeleteConfirmed(deletedIds)
        pendingDeleteIds = emptySet()
        isDeleting       = false
    }

    LaunchedEffect(deleteCancelFlag) {
        if (!deleteCancelFlag) return@LaunchedEffect
        deleteCancelFlag = false
        isDeleting       = false
    }

    // ---------- root ----------

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {

        Column(modifier = Modifier.fillMaxSize()) {

            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(8.dp))

            TopBar(
                activeTab      = activeTab,
                selectionMode  = selectionMode,
                selectedCount  = selectedImages.size,
                totalCount     = images.size,
                onBack         = onNavigateBack,
                onTabSelected  = { activeTab = it },
                onCancelSelect = { selectionMode = false; selectedImages = emptySet() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (activeTab == ActiveTab.ALL_IMAGES) {
                SortBar(
                    sortOrder      = sortOrder,
                    onSortSelected = { sortOrder = it }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isScanning) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color       = AccentBlue
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Updating...", color = TabTextInact, fontSize = 13.sp)
                }
            }

            when (activeTab) {
                ActiveTab.ALL_IMAGES -> AllImagesGrid(
                    images            = images,
                    sortOrder         = sortOrder,
                    selectionMode     = selectionMode,
                    selectedImages    = selectedImages,
                    gridState         = gridState,
                    onImageClick      = { index, sortedList ->
                        viewerImages = sortedList
                        viewerIndex  = index
                        viewerOpen   = true
                    },
                    onImageLongClick  = { id ->
                        selectionMode  = true
                        selectedImages = selectedImages + id
                    },
                    onSelectionChange = { updated ->
                        selectedImages = updated
                        if (updated.isEmpty()) selectionMode = false
                    }
                )

                ActiveTab.DUPLICATES -> DuplicatesContent(
                    groups            = exactGroups,
                    selectedIds       = dupSelectedIds,
                    isScanning        = isScanning,
                    isDeleting        = isDeleting,
                    modifier          = Modifier.weight(1f),
                    onSelectionChange = { dupSelectedIds = it },
                    onViewGroup       = { group ->
                        viewerImages = group; viewerIndex = 0; viewerOpen = true
                    },
                    onUnselectGroup   = { groupIds -> dupSelectedIds = dupSelectedIds - groupIds },
                    onDelete          = { requestDelete(dupSelectedIds) }
                )

                ActiveTab.GROUPS -> GroupsContent(
                    groups            = similarGroups,
                    allImages         = images,
                    selectedIds       = groupSelectedIds,
                    isScanning        = isScanning,
                    isDeleting        = isDeleting,
                    modifier          = Modifier.weight(1f),
                    onSelectionChange = { groupSelectedIds = it },
                    onViewGroup = { group, startIndex ->
                        viewerImages = group
                        viewerIndex  = startIndex
                        viewerOpen   = true
                    },
                    onSelectGroup     = { groupIds -> groupSelectedIds = groupSelectedIds + groupIds },
                    onUnselectGroup   = { groupIds -> groupSelectedIds = groupSelectedIds - groupIds },
                    onDelete          = { requestDelete(groupSelectedIds) }
                )
            }
        }

        // ── Floating Delete Button ──
        if (activeTab == ActiveTab.ALL_IMAGES) {
            AnimatedVisibility(
                visible  = !viewerOpen && selectionMode && selectedImages.isNotEmpty(),
                enter    = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) +
                        fadeIn(animationSpec = tween(300)),
                exit     = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) +
                        fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
            ) {
                FloatingDeleteButton(
                    count      = selectedImages.size,
                    isDeleting = isDeleting,
                    onClick    = { requestDelete(selectedImages) }
                )
            }
        }

        // ── Fullscreen viewer ──
        if (viewerOpen) {
            FullscreenViewer(
                viewerImages = viewerImages,
                viewerIndex  = viewerIndex,
                onClose      = { viewerOpen = false }
            )
        }

        // ── Custom delete confirmation dialog ─────────────────────────────────
        if (showDeleteDialog) {
            DeleteConfirmDialog(
                count     = pendingDeleteQueue.size,
                onConfirm = {
                    showDeleteDialog = false
                    executeDelete(pendingDeleteQueue)
                    pendingDeleteQueue = emptySet()
                },
                onDismiss = {
                    showDeleteDialog   = false
                    pendingDeleteQueue = emptySet()
                }
            )
        }
    }
}

// ── Custom delete confirmation dialog ─────────────────────────────────────────
@Composable
private fun DeleteConfirmDialog(
    count:     Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(DialogBg)
                .border(1.dp, DialogBorder, RoundedCornerShape(24.dp))
                .padding(28.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                // Warning icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(DangerRed.copy(alpha = 0.12f))
                        .border(1.dp, DangerRed.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Delete,
                        contentDescription = null,
                        tint               = DangerRed,
                        modifier           = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text       = "Delete ${if (count == 1) "Photo" else "$count Photos"}?",
                    color      = HeaderText,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text      = if (count == 1)
                        "This photo will be permanently deleted\nfrom your device."
                    else
                        "These $count photos will be permanently\ndeleted from your device.",
                    color     = SubText,
                    fontSize  = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Buttons row
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel
                    OutlinedButton(
                        onClick  = onDismiss,
                        shape    = RoundedCornerShape(14.dp),
                        border   = androidx.compose.foundation.BorderStroke(
                            1.dp, DialogBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text(
                            text       = "Cancel",
                            color      = HeaderText,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Delete
                    Button(
                        onClick  = onConfirm,
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = DangerRed
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
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
// Sliding tab pill
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SlidingTabPill(
    activeTab:     ActiveTab,
    onTabSelected: (ActiveTab) -> Unit,
    modifier:      Modifier = Modifier
) {
    val tabs = listOf(
        ActiveTab.ALL_IMAGES to "All",
        ActiveTab.DUPLICATES  to "Duplicates",
        ActiveTab.GROUPS      to "Groups"
    )
    val tabCount    = tabs.size
    val activeIndex = tabs.indexOfFirst { it.first == activeTab }.toFloat()

    val indicatorPos by animateFloatAsState(
        targetValue   = activeIndex,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ),
        label = "tabIndicator"
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
                        color      = if (isActive) TabTextActive else TabTextInact,
                        fontSize   = 14.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(
    activeTab:      ActiveTab,
    selectionMode:  Boolean,
    selectedCount:  Int,
    totalCount:     Int,
    onBack:         () -> Unit,
    onTabSelected:  (ActiveTab) -> Unit,
    onCancelSelect: () -> Unit
) {
    if (!selectionMode) {
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
                    .clickable { onBack() },
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
            SlidingTabPill(
                activeTab     = activeTab,
                onTabSelected = onTabSelected,
                modifier      = Modifier
                    .weight(1f)
                    .height(44.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(44.dp)
        ) {
            Box(
                modifier         = Modifier
                    .align(Alignment.CenterStart)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TabPillBg)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint               = Color.White,
                    modifier           = Modifier.size(20.dp)
                )
            }

            Text(
                text       = "Selected: $selectedCount / $totalCount",
                color      = Color.White,
                fontSize   = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.align(Alignment.Center)
            )

            Box(
                modifier         = Modifier
                    .align(Alignment.CenterEnd)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TabPillBg)
                    .clickable { onCancelSelect() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Cancel selection",
                    tint               = Color.White,
                    modifier           = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Floating delete button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FloatingDeleteButton(
    count:      Int,
    isDeleting: Boolean,
    onClick:    () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "deleteGradient")

    val shimmer by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4000
                0.0f at 0
                1.0f at 1500
                1.0f at 4000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
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
        onClick        = onClick,
        enabled        = !isDeleting,
        shape          = CircleShape,
        colors         = ButtonDefaults.buttonColors(
            containerColor         = Color.Transparent,
            disabledContainerColor = Color(0x66C62828)
        ),
        elevation      = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
        modifier       = Modifier
            .padding(horizontal = 48.dp, vertical = 24.dp)
            .height(58.dp)
            .background(brush = animatedBrush, shape = CircleShape)
    ) {
        if (isDeleting) {
            CircularProgressIndicator(
                modifier    = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color       = Color.White
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text          = "Deleting...",
                color         = Color.White,
                fontSize      = 16.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        } else {
            Icon(
                imageVector        = Icons.Filled.Delete,
                contentDescription = null,
                tint               = Color.White,
                modifier           = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text          = "Delete",
                color         = Color.White,
                fontSize      = 17.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sort bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SortBar(
    sortOrder:      SortOrder,
    onSortSelected: (SortOrder) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val label = when (sortOrder) {
        SortOrder.NEWEST     -> "Newest"
        SortOrder.OLDEST     -> "Oldest"
        SortOrder.SIZE_LARGE -> "Size (Large)"
        SortOrder.SIZE_SMALL -> "Size (Small)"
    }

    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(SortPillBg)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = "Sort By: $label",
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
            expanded         = expanded,
            onDismissRequest = { expanded = false },
            modifier         = Modifier.background(Color(0xFF1C2340))
        ) {
            listOf(
                SortOrder.NEWEST     to "Newest",
                SortOrder.OLDEST     to "Oldest",
                SortOrder.SIZE_LARGE to "Size (Large)",
                SortOrder.SIZE_SMALL to "Size (Small)"
            ).forEach { (order, lbl) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text       = lbl,
                            color      = if (sortOrder == order) TabActive1 else Color.White,
                            fontSize   = 14.sp,
                            fontWeight = if (sortOrder == order) FontWeight.SemiBold
                            else FontWeight.Normal
                        )
                    },
                    onClick = { onSortSelected(order); expanded = false },
                    trailingIcon = {
                        if (sortOrder == order) {
                            Icon(
                                imageVector        = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint               = TabActive1,
                                modifier           = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// All Images grid
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllImagesGrid(
    images:            List<ImageItem>,
    sortOrder:         SortOrder,
    selectionMode:     Boolean,
    selectedImages:    Set<Long>,
    gridState:         LazyGridState,
    onImageClick:      (Int, List<ImageItem>) -> Unit,
    onImageLongClick:  (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit
) {
    if (images.isEmpty()) return

    LaunchedEffect(sortOrder) { gridState.scrollToItem(0) }

    val sortedImages = remember(images, sortOrder) {
        when (sortOrder) {
            SortOrder.NEWEST     -> images
            SortOrder.OLDEST     -> images.reversed()
            SortOrder.SIZE_LARGE -> images.sortedByDescending { it.size }
            SortOrder.SIZE_SMALL -> images.sortedBy { it.size }
        }
    }

    LazyVerticalGrid(
        columns               = GridCells.Fixed(3),
        state                 = gridState,
        modifier              = Modifier.fillMaxSize(),
        contentPadding        = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items = sortedImages, key = { _, image -> image.id }) { index, image ->
            val isSelected = selectedImages.contains(image.id)

            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .graphicsLayer {
                        shadowElevation = 12f
                        shape           = RoundedCornerShape(14.dp)
                        clip            = true
                    }
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0x40FFFFFF), Color(0x10FFFFFF))
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = {
                            if (selectionMode) {
                                val updated = if (isSelected) selectedImages - image.id
                                else            selectedImages + image.id
                                onSelectionChange(updated)
                            } else {
                                onImageClick(index, sortedImages)
                            }
                        },
                        onLongClick = { onImageLongClick(image.id) }
                    )
            ) {
                AsyncImage(
                    model              = image.uri,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(CardOverlay))
                    Icon(
                        imageVector        = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint               = Color.White,
                        modifier           = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                    )
                }
                if (sortOrder == SortOrder.SIZE_LARGE || sortOrder == SortOrder.SIZE_SMALL) {
                    val sizeText = remember(image.size) {
                        when {
                            image.size >= 1_048_576 -> "%.1f MB".format(image.size / 1_048_576f)
                            image.size >= 1_024     -> "%.0f KB".format(image.size / 1_024f)
                            else                    -> "${image.size} B"
                        }
                    }
                    Text(
                        text       = sizeText,
                        color      = Color.White,
                        fontSize   = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color(0x99000000), RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fullscreen pager viewer
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenViewer(
    viewerImages: List<ImageItem>,
    viewerIndex:  Int,
    onClose:      () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = viewerIndex,
        pageCount   = { viewerImages.size }
    )
    var isZoomed by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state             = pagerState,
            userScrollEnabled = !isZoomed,
            modifier          = Modifier.fillMaxSize()
        ) { page ->
            var scale   by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage != page) {
                    scale = 1f; offsetX = 0f; offsetY = 0f; isZoomed = false
                }
            }

            AsyncImage(
                model              = viewerImages[page].uri,
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(page) {
                        awaitEachGesture {
                            do {
                                val event       = awaitPointerEvent()
                                val canceled    = event.changes.any { it.isConsumed }
                                if (canceled) break
                                val zoomChange  = event.calculateZoom()
                                val panChange   = event.calculatePan()
                                val fingerCount = event.changes.count { it.pressed }
                                if (fingerCount >= 2) {
                                    val ns = (scale * zoomChange).coerceIn(1f, 5f)
                                    scale    = ns
                                    isZoomed = scale > 1f
                                    if (scale > 1f) {
                                        val mx = (size.width  * (scale - 1f)) / 2f
                                        val my = (size.height * (scale - 1f)) / 2f
                                        offsetX = (offsetX + panChange.x).coerceIn(-mx, mx)
                                        offsetY = (offsetY + panChange.y).coerceIn(-my, my)
                                    } else {
                                        offsetX = 0f; offsetY = 0f; isZoomed = false
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (fingerCount == 1 && scale > 1f) {
                                    val mx = (size.width  * (scale - 1f)) / 2f
                                    val my = (size.height * (scale - 1f)) / 2f
                                    offsetX = (offsetX + panChange.x).coerceIn(-mx, mx)
                                    offsetY = (offsetY + panChange.y).coerceIn(-my, my)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer(
                        scaleX       = scale,
                        scaleY       = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        }

        IconButton(
            onClick  = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(
                imageVector        = Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint               = Color.White
            )
        }

        Text(
            text     = "${pagerState.currentPage + 1} / ${viewerImages.size}",
            color    = Color.White,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        )
    }
}