package com.example.storageoptimizer.ui.theme.gallery

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.ActiveTab
import com.example.storageoptimizer.data.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel:      MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context         = LocalContext.current
    val contentResolver = context.contentResolver

    // Read all data from ViewModel — no local scan state
    val images        by viewModel.images.collectAsState()
    val exactGroups   by viewModel.exactGroups.collectAsState()
    val similarGroups by viewModel.similarGroups.collectAsState()
    val isScanning    by viewModel.isScanning.collectAsState()

    // UI-only state that belongs to this screen, not the ViewModel
    var activeTab        by remember { mutableStateOf(ActiveTab.ALL_IMAGES) }
    var selectionMode    by remember { mutableStateOf(false) }
    var selectedImages   by remember { mutableStateOf(setOf<Long>()) }
    var viewerOpen       by remember { mutableStateOf(false) }
    var viewerIndex      by remember { mutableStateOf(0) }
    var viewerImages     by remember { mutableStateOf(listOf<com.example.storageoptimizer.data.ImageItem>()) }

    // Duplicates tab starts with the largest copy pre-selected to keep,
    // rest auto-selected for deletion. Re-computed when exactGroups changes.
    var dupSelectedIds   by remember { mutableStateOf(setOf<Long>()) }
    var groupSelectedIds by remember { mutableStateOf(setOf<Long>()) }

    // Sync auto-selection whenever exactGroups updates (after scan or delete)
    LaunchedEffect(exactGroups) {
        dupSelectedIds = viewModel.autoSelectedDuplicateIds()
    }

    // Bridge Activity-level delete result back into ViewModel
    var pendingDeleteIds  by remember { mutableStateOf(setOf<Long>()) }
    var deleteConfirmFlag by remember { mutableStateOf(false) }
    var deleteCancelFlag  by remember { mutableStateOf(false) }
    var isDeleting        by remember { mutableStateOf(false) }

    // ---------- STEP 1: launchers ----------

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            deleteConfirmFlag = true
        } else {
            pendingDeleteIds = emptySet()
            deleteCancelFlag = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.refresh(contentResolver)
    }

    // ---------- STEP 2: local delete functions ----------

    fun launchDelete(toDelete: Set<Long>) {
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
            viewModel.viewModelScopeDelete(toDelete, contentResolver) {
                deleteConfirmFlag = true
            }
        }
    }

    // ---------- STEP 3: effects ----------

    LaunchedEffect(deleteConfirmFlag) {
        if (!deleteConfirmFlag) return@LaunchedEffect
        deleteConfirmFlag = false

        val deletedIds = pendingDeleteIds

        // Update UI-only selection state
        selectedImages   = selectedImages - deletedIds
        if (selectedImages.isEmpty()) selectionMode = false
        dupSelectedIds   = (dupSelectedIds - deletedIds)
            .intersect(viewModel.exactGroups.value.flatten().map { it.id }.toSet())
        groupSelectedIds = (groupSelectedIds - deletedIds)
            .intersect(viewModel.similarGroups.value.flatten().map { it.id }.toSet())

        // Delegate actual data update to ViewModel
        viewModel.onDeleteConfirmed(deletedIds)
        pendingDeleteIds = emptySet()
        isDeleting       = false
    }

    LaunchedEffect(deleteCancelFlag) {
        if (!deleteCancelFlag) return@LaunchedEffect
        deleteCancelFlag = false
        isDeleting       = false
    }

    // ---------- UI ----------

    Surface(modifier = Modifier.fillMaxSize()) {

        if (!viewerOpen) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.height(48.dp))

                val showingSelectionToolbar = selectionMode && activeTab == ActiveTab.ALL_IMAGES

                if (!showingSelectionToolbar) {
                    Text(
                        text  = "Images found: ${images.size}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectionMode  = false
                            selectedImages = emptySet()
                        }) { Text("Cancel") }
                        Text(
                            text  = "Selected: ${selectedImages.size} / ${images.size}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(64.dp))
                    }
                    if (selectedImages.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick  = { launchDelete(selectedImages) },
                            enabled  = !isDeleting,
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                if (isDeleting) "Deleting..."
                                else "Delete Selected (${selectedImages.size})"
                            )
                        }
                    }
                }

                if (!showingSelectionToolbar) {

                    Spacer(modifier = Modifier.height(16.dp))

                    // "Refresh" replaces the old "Scan Storage" — re-runs full scan
                    Button(
                        onClick  = { viewModel.refresh(contentResolver) },
                        enabled  = !isScanning && !isDeleting,
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color       = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...")
                        } else {
                            Text("Refresh")
                        }
                    }

                    if (isScanning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier              = Modifier.padding(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Scanning images...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    if (images.isNotEmpty() && !isScanning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            listOf(
                                ActiveTab.ALL_IMAGES to "All Images",
                                ActiveTab.DUPLICATES  to "Duplicates",
                                ActiveTab.GROUPS      to "Groups"
                            ).forEach { (tab, label) ->
                                if (activeTab == tab) {
                                    Button(
                                        onClick  = { activeTab = tab },
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) { Text(label) }
                                } else {
                                    OutlinedButton(
                                        onClick  = { activeTab = tab },
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) { Text(label) }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (activeTab) {

                    ActiveTab.ALL_IMAGES -> {
                        if (images.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns               = GridCells.Fixed(3),
                                modifier              = Modifier.weight(1f),
                                contentPadding        = PaddingValues(4.dp),
                                verticalArrangement   = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(
                                    items = images,
                                    key   = { _, image -> image.id }
                                ) { index, image ->
                                    val isSelected = selectedImages.contains(image.id)
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    if (selectionMode) {
                                                        val updated = if (isSelected)
                                                            selectedImages - image.id
                                                        else
                                                            selectedImages + image.id
                                                        selectedImages = updated
                                                        if (updated.isEmpty()) selectionMode = false
                                                    } else {
                                                        viewerImages = images
                                                        viewerIndex  = index
                                                        viewerOpen   = true
                                                    }
                                                },
                                                onLongClick = {
                                                    selectionMode  = true
                                                    selectedImages = selectedImages + image.id
                                                }
                                            )
                                    ) {
                                        AsyncImage(
                                            model              = image.uri,
                                            contentDescription = null,
                                            contentScale       = ContentScale.Crop,
                                            modifier           = Modifier.fillMaxSize()
                                        )
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.Black.copy(alpha = 0.4f))
                                            )
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
                                    }
                                }
                            }
                        }
                    }

                    ActiveTab.DUPLICATES -> {
                        DuplicatesContent(
                            groups            = exactGroups,
                            selectedIds       = dupSelectedIds,
                            isScanning        = isScanning,
                            isDeleting        = isDeleting,
                            modifier          = Modifier.weight(1f),
                            onSelectionChange = { dupSelectedIds = it },
                            onViewGroup       = { group ->
                                viewerImages = group; viewerIndex = 0; viewerOpen = true
                            },
                            onUnselectGroup   = { groupIds ->
                                dupSelectedIds = dupSelectedIds - groupIds
                            },
                            onDelete          = { launchDelete(dupSelectedIds) }
                        )
                    }

                    ActiveTab.GROUPS -> {
                        GroupsContent(
                            groups            = similarGroups,
                            selectedIds       = groupSelectedIds,
                            isScanning        = isScanning,
                            isDeleting        = isDeleting,
                            modifier          = Modifier.weight(1f),
                            onSelectionChange = { groupSelectedIds = it },
                            onViewGroup       = { group ->
                                viewerImages = group; viewerIndex = 0; viewerOpen = true
                            },
                            onSelectGroup     = { groupIds ->
                                groupSelectedIds = groupSelectedIds + groupIds
                            },
                            onUnselectGroup   = { groupIds ->
                                groupSelectedIds = groupSelectedIds - groupIds
                            },
                            onDelete          = { launchDelete(groupSelectedIds) }
                        )
                    }
                }
            }

        } else {

            // Fullscreen pager viewer
            val pagerState = rememberPagerState(
                initialPage = viewerIndex,
                pageCount   = { viewerImages.size }
            )
            var isZoomed by remember { mutableStateOf(false) }

            androidx.activity.compose.BackHandler { viewerOpen = false }

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
                        modifier = Modifier
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
                    onClick  = { viewerOpen = false },
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
                    text  = "${pagerState.currentPage + 1} / ${viewerImages.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                )
            }
        }
    }
}