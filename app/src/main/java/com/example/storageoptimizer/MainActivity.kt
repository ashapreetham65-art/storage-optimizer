package com.example.storageoptimizer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.storageoptimizer.ui.theme.StorageOptimizerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ImageItem(
    val id: Long,
    val uri: Uri,
    val hash: String? = null,
    val size: Long = 0L
)

class MainActivity : ComponentActivity() {

    private lateinit var deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingDeleteIds: Set<Long> = emptySet()
    private var onDeleteConfirmed: (() -> Unit)? = null
    private var onDeleteCancelled: (() -> Unit)? = null

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deleteRequestLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                onDeleteConfirmed?.invoke()
            } else {
                pendingDeleteIds = emptySet()
                onDeleteCancelled?.invoke()
            }
        }

        setContent {
            StorageOptimizerTheme {

                var images by remember { mutableStateOf(listOf<ImageItem>()) }
                var duplicateGroups by remember { mutableStateOf(listOf<List<ImageItem>>()) }
                var permissionDenied by remember { mutableStateOf(false) }
                var permanentlyDenied by remember { mutableStateOf(false) }
                var isScanning by remember { mutableStateOf(false) }
                var isDeleting by remember { mutableStateOf(false) }
                var selectionMode by remember { mutableStateOf(false) }
                var selectedImages by remember { mutableStateOf(setOf<Long>()) }
                var duplicateSelectedIds by remember { mutableStateOf(setOf<Long>()) }
                var viewerOpen by remember { mutableStateOf(false) }
                var viewerIndex by remember { mutableStateOf(0) }
                // Separate image list for viewer — can be full gallery or a single group
                var viewerImages by remember { mutableStateOf(listOf<ImageItem>()) }
                var showDuplicates by remember { mutableStateOf(false) }

                val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                val hashSemaphore = remember { Semaphore(4) }

                fun rebuildDuplicateGroups(currentImages: List<ImageItem>): List<List<ImageItem>> {
                    return findDuplicateGroups(currentImages)
                }

                fun autoSelectDuplicates(groups: List<List<ImageItem>>): Set<Long> {
                    return groups.flatMap { group ->
                        group.sortedByDescending { it.size }.drop(1)
                    }.map { it.id }.toSet()
                }

                fun scanImages() {
                    isScanning = true
                    duplicateGroups = emptyList()
                    duplicateSelectedIds = emptySet()
                    lifecycleScope.launch {
                        val baseImages = withContext(Dispatchers.IO) {
                            loadImages()
                        }
                        val hashedImages = withContext(Dispatchers.IO) {
                            coroutineScope {
                                baseImages.map { image ->
                                    async {
                                        hashSemaphore.withPermit {
                                            val hash = calculateHash(image.uri)
                                            image.copy(hash = hash)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                        images = hashedImages
                        val groups = rebuildDuplicateGroups(hashedImages)
                        duplicateGroups = groups
                        // Auto-select runs ONCE after scan — never after user interaction
                        duplicateSelectedIds = autoSelectDuplicates(groups)
                        isScanning = false
                    }
                }

                onDeleteCancelled = {
                    isDeleting = false
                }

                onDeleteConfirmed = {
                    val deletedIds = pendingDeleteIds
                    val updatedImages = images.filter { it.id !in deletedIds }
                    images = updatedImages
                    val updatedGroups = rebuildDuplicateGroups(updatedImages)
                    duplicateGroups = updatedGroups
                    duplicateSelectedIds = duplicateSelectedIds - deletedIds
                    val allGroupIds = updatedGroups.flatten().map { it.id }.toSet()
                    duplicateSelectedIds = duplicateSelectedIds.intersect(allGroupIds)
                    pendingDeleteIds = emptySet()
                    isDeleting = false
                }

                fun deleteSelectedDuplicates() {
                    val toDelete = duplicateSelectedIds
                    if (toDelete.isEmpty()) return
                    pendingDeleteIds = toDelete

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val uris = toDelete.map { id ->
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                        }
                        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                        deleteRequestLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                        isDeleting = true
                    } else {
                        isDeleting = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            toDelete.forEach { id ->
                                val uri = ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                                )
                                contentResolver.delete(uri, null, null)
                            }
                            withContext(Dispatchers.Main) {
                                onDeleteConfirmed?.invoke()
                            }
                        }
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        permissionDenied = false
                        permanentlyDenied = false
                        scanImages()
                    } else {
                        val canStillAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity,
                            requiredPermission
                        )
                        if (canStillAsk) {
                            permissionDenied = true
                            permanentlyDenied = false
                        } else {
                            permissionDenied = false
                            permanentlyDenied = true
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {

                    if (!viewerOpen) {

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {

                            Spacer(modifier = Modifier.height(48.dp))

                            if (!selectionMode || showDuplicates) {
                                Text(
                                    text = "Images found: ${images.size}",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = {
                                        selectionMode = false
                                        selectedImages = emptySet()
                                    }) {
                                        Text("Cancel")
                                    }
                                    Text(
                                        text = "Selected: ${selectedImages.size} / ${images.size}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.width(64.dp))
                                }
                            }

                            if (!selectionMode || showDuplicates) {

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            requiredPermission
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            permissionDenied = false
                                            permanentlyDenied = false
                                            scanImages()
                                        } else {
                                            val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                                                this@MainActivity,
                                                requiredPermission
                                            )
                                            if (canAsk || !permanentlyDenied) {
                                                permissionLauncher.launch(requiredPermission)
                                            } else {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                ).apply {
                                                    data = Uri.fromParts("package", packageName, null)
                                                }
                                                startActivity(intent)
                                            }
                                        }
                                    },
                                    enabled = !isScanning && !isDeleting
                                ) {
                                    Text("Scan Storage")
                                }

                                when {
                                    permissionDenied -> {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "No permission granted. Tap \"Scan Storage\" to try again.",
                                            color = Color.Red,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 32.dp)
                                        )
                                    }
                                    permanentlyDenied -> {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Permission permanently denied. Tap \"Scan Storage\" to open Settings and enable it manually.",
                                            color = Color(0xFFFF6600),
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 32.dp)
                                        )
                                    }
                                }

                                if (isScanning) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Scanning images...",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }

                                if (images.isNotEmpty() && !isScanning) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (!showDuplicates) {
                                            Button(
                                                onClick = { showDuplicates = false },
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) { Text("All Images") }
                                            OutlinedButton(
                                                onClick = { showDuplicates = true },
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) { Text("Duplicates") }
                                        } else {
                                            OutlinedButton(
                                                onClick = { showDuplicates = false },
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) { Text("All Images") }
                                            Button(
                                                onClick = { showDuplicates = true },
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) { Text("Duplicates") }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (!showDuplicates) {

                                if (images.isNotEmpty()) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        itemsIndexed(
                                            items = images,
                                            key = { _, image -> image.id }
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
                                                                if (updated.isEmpty()) {
                                                                    selectionMode = false
                                                                }
                                                            } else {
                                                                // Open viewer with full image list
                                                                viewerImages = images
                                                                viewerIndex = index
                                                                viewerOpen = true
                                                            }
                                                        },
                                                        onLongClick = {
                                                            selectionMode = true
                                                            selectedImages = selectedImages + image.id
                                                        }
                                                    )
                                            ) {
                                                AsyncImage(
                                                    model = image.uri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.4f))
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Filled.CheckCircle,
                                                        contentDescription = "Selected",
                                                        tint = Color.White,
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(6.dp)
                                                            .size(22.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                            } else {

                                if (duplicateGroups.isEmpty()) {
                                    Text(
                                        text = if (isScanning) "" else "No duplicates found",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.weight(1f)) {
                                        duplicateGroups.forEachIndexed { groupIndex, group ->

                                            // Sorted same order as displayed — largest first = keep
                                            val sortedGroup = group.sortedByDescending { it.size }
                                            val groupIds = sortedGroup.map { it.id }.toSet()
                                            val anySelectedInGroup = groupIds.any {
                                                it in duplicateSelectedIds
                                            }

                                            // Group header with eye + close buttons
                                            item(key = "header_$groupIndex") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            start = 8.dp,
                                                            top = 12.dp,
                                                            bottom = 4.dp
                                                        ),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Group label + safe indicator
                                                    Column {
                                                        Text(
                                                            text = "Group ${groupIndex + 1}  •  ${group.size} duplicates",
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                        if (!anySelectedInGroup) {
                                                            Text(
                                                                text = "No images selected for deletion",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFF4CAF50)
                                                            )
                                                        }
                                                    }

                                                    Row {
                                                        // 👁 Eye — open group in fullscreen viewer
                                                        IconButton(onClick = {
                                                            // Use sorted order so viewer matches grid
                                                            viewerImages = sortedGroup
                                                            viewerIndex = 0
                                                            viewerOpen = true
                                                        }) {
                                                            Icon(
                                                                imageVector = Icons.Filled.Search,
                                                                contentDescription = "View group",
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }

                                                        // ✖ Close — unselect all in group
                                                        IconButton(onClick = {
                                                            // Remove all group ids from selection
                                                            // Does NOT trigger auto-select again
                                                            duplicateSelectedIds = duplicateSelectedIds - groupIds
                                                        }) {
                                                            Icon(
                                                                imageVector = Icons.Filled.Close,
                                                                contentDescription = "Unselect group",
                                                                tint = MaterialTheme.colorScheme.error
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // Image rows — 3 per row
                                            val rows = sortedGroup.chunked(3)
                                            rows.forEachIndexed { rowIndex, rowImages ->
                                                item(key = "group_${groupIndex}_row_$rowIndex") {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        rowImages.forEachIndexed { posInRow, image ->
                                                            val isSelected = duplicateSelectedIds.contains(image.id)
                                                            val isKeep = rowIndex == 0 && posInRow == 0

                                                            Box(
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .aspectRatio(1f)
                                                                    .clickable {
                                                                        val updated = if (isSelected)
                                                                            duplicateSelectedIds - image.id
                                                                        else
                                                                            duplicateSelectedIds + image.id
                                                                        duplicateSelectedIds = updated
                                                                    }
                                                            ) {
                                                                AsyncImage(
                                                                    model = image.uri,
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier
                                                                        .fillMaxSize()
                                                                        .padding(2.dp)
                                                                )

                                                                if (isSelected) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .fillMaxSize()
                                                                            .background(
                                                                                Color.Black.copy(alpha = 0.4f)
                                                                            )
                                                                    )
                                                                    Icon(
                                                                        imageVector = Icons.Filled.CheckCircle,
                                                                        contentDescription = "Selected for deletion",
                                                                        tint = Color.White,
                                                                        modifier = Modifier
                                                                            .align(Alignment.TopEnd)
                                                                            .padding(4.dp)
                                                                            .size(20.dp)
                                                                    )
                                                                }

                                                                if (isKeep) {
                                                                    Text(
                                                                        text = "Keep",
                                                                        color = Color.White,
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        modifier = Modifier
                                                                            .align(Alignment.BottomStart)
                                                                            .background(
                                                                                Color(0xFF4CAF50).copy(alpha = 0.85f)
                                                                            )
                                                                            .padding(
                                                                                horizontal = 6.dp,
                                                                                vertical = 2.dp
                                                                            )
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        repeat(3 - rowImages.size) {
                                                            Spacer(modifier = Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Sticky delete button
                                    if (duplicateSelectedIds.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { deleteSelectedDuplicates() },
                                            enabled = !isDeleting,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
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
                                                else "Delete Selected (${duplicateSelectedIds.size})"
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    } else {

                        // ── Fullscreen Pager Viewer ──
                        // Uses viewerImages — either full gallery or a single group
                        val pagerState = rememberPagerState(
                            initialPage = viewerIndex,
                            pageCount = { viewerImages.size }
                        )

                        var isZoomed by remember { mutableStateOf(false) }

                        BackHandler {
                            viewerOpen = false
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                userScrollEnabled = !isZoomed,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->

                                var scale by remember { mutableStateOf(1f) }
                                var offsetX by remember { mutableStateOf(0f) }
                                var offsetY by remember { mutableStateOf(0f) }

                                LaunchedEffect(pagerState.currentPage) {
                                    if (pagerState.currentPage != page) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                        isZoomed = false
                                    }
                                }

                                AsyncImage(
                                    model = viewerImages[page].uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clipToBounds()
                                        .pointerInput(page) {
                                            awaitEachGesture {
                                                do {
                                                    val event = awaitPointerEvent()
                                                    val canceled = event.changes.any { it.isConsumed }
                                                    if (canceled) break

                                                    val zoomChange = event.calculateZoom()
                                                    val panChange = event.calculatePan()
                                                    val fingerCount = event.changes.count { it.pressed }

                                                    if (fingerCount >= 2) {
                                                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                                        scale = newScale
                                                        isZoomed = scale > 1f
                                                        if (scale > 1f) {
                                                            val maxX = (size.width * (scale - 1f)) / 2f
                                                            val maxY = (size.height * (scale - 1f)) / 2f
                                                            offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                                            offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                                                        } else {
                                                            offsetX = 0f
                                                            offsetY = 0f
                                                            isZoomed = false
                                                        }
                                                        event.changes.forEach { it.consume() }
                                                    } else if (fingerCount == 1 && scale > 1f) {
                                                        val maxX = (size.width * (scale - 1f)) / 2f
                                                        val maxY = (size.height * (scale - 1f)) / 2f
                                                        offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                                        offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                } while (event.changes.any { it.pressed })
                                            }
                                        }
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offsetX,
                                            translationY = offsetY
                                        )
                                )
                            }

                            IconButton(
                                onClick = { viewerOpen = false },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .statusBarsPadding()
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }

                            // Counter reflects current viewer list size, not total gallery
                            Text(
                                text = "${pagerState.currentPage + 1} / ${viewerImages.size}",
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
        }
    }

    private fun loadImages(): List<ImageItem> {
        val imageList = mutableListOf<ImageItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val size = it.getLong(sizeColumn)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                imageList.add(ImageItem(id, uri, null, size))
            }
        }
        return imageList
    }

    private fun calculateHash(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            inputStream.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun findDuplicateGroups(images: List<ImageItem>): List<List<ImageItem>> {
        return images
            .filter { it.hash != null }
            .groupBy { it.hash }
            .values
            .filter { it.size > 1 }
            .sortedByDescending { it.size }
    }
}