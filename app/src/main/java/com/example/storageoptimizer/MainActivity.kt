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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val hash: Long? = null,
    val size: Long = 0L
)

enum class ActiveTab { ALL_IMAGES, DUPLICATES, GROUPS }

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

                var images           by remember { mutableStateOf(listOf<ImageItem>()) }
                var exactGroups      by remember { mutableStateOf(listOf<List<ImageItem>>()) }
                var similarGroups    by remember { mutableStateOf(listOf<List<ImageItem>>()) }
                var permissionDenied  by remember { mutableStateOf(false) }
                var permanentlyDenied by remember { mutableStateOf(false) }
                var isScanning        by remember { mutableStateOf(false) }
                var isDeleting        by remember { mutableStateOf(false) }
                var selectionMode     by remember { mutableStateOf(false) }
                var selectedImages    by remember { mutableStateOf(setOf<Long>()) }
                var dupSelectedIds    by remember { mutableStateOf(setOf<Long>()) }
                var groupSelectedIds  by remember { mutableStateOf(setOf<Long>()) }
                var viewerOpen        by remember { mutableStateOf(false) }
                var viewerIndex       by remember { mutableStateOf(0) }
                var viewerImages      by remember { mutableStateOf(listOf<ImageItem>()) }
                var activeTab         by remember { mutableStateOf(ActiveTab.ALL_IMAGES) }

                val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                val hashSemaphore = remember { Semaphore(4) }

                fun autoSelectDuplicates(groups: List<List<ImageItem>>): Set<Long> =
                    groups.flatMap { group ->
                        group.sortedByDescending { it.size }.drop(1)
                    }.map { it.id }.toSet()

                fun scanImages() {
                    isScanning       = true
                    exactGroups      = emptyList()
                    similarGroups    = emptyList()
                    dupSelectedIds   = emptySet()
                    groupSelectedIds = emptySet()

                    lifecycleScope.launch {
                        val baseImages = withContext(Dispatchers.IO) { loadImages() }

                        val hashedImages = withContext(Dispatchers.IO) {
                            coroutineScope {
                                baseImages.map { image ->
                                    async {
                                        hashSemaphore.withPermit {
                                            image.copy(hash = calculatePerceptualHash(image.uri))
                                        }
                                    }
                                }.awaitAll()
                            }
                        }

                        images = hashedImages

                        val exact   = withContext(Dispatchers.IO) {
                            findGroupsByThreshold(hashedImages, threshold = 0)
                        }
                        val similar = withContext(Dispatchers.IO) {
                            findGroupsByThreshold(hashedImages, threshold = 5)
                        }

                        exactGroups    = exact
                        similarGroups  = similar
                        dupSelectedIds = autoSelectDuplicates(exact)
                        isScanning     = false
                    }
                }

                onDeleteCancelled = { isDeleting = false }

                onDeleteConfirmed = {
                    val deletedIds    = pendingDeleteIds
                    val updatedImages = images.filter { it.id !in deletedIds }
                    images = updatedImages

                    selectedImages = selectedImages - deletedIds
                    if (selectedImages.isEmpty()) selectionMode = false

                    val newExact   = findGroupsByThreshold(updatedImages, threshold = 0)
                    val newSimilar = findGroupsByThreshold(updatedImages, threshold = 5)
                    exactGroups   = newExact
                    similarGroups = newSimilar

                    dupSelectedIds = (dupSelectedIds - deletedIds)
                        .intersect(newExact.flatten().map { it.id }.toSet())
                    groupSelectedIds = (groupSelectedIds - deletedIds)
                        .intersect(newSimilar.flatten().map { it.id }.toSet())

                    pendingDeleteIds = emptySet()
                    isDeleting       = false
                }

                fun launchDelete(toDelete: Set<Long>) {
                    if (toDelete.isEmpty()) return
                    pendingDeleteIds = toDelete
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val uris = toDelete.map { id ->
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                        }
                        val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                        deleteRequestLauncher.launch(
                            IntentSenderRequest.Builder(pi.intentSender).build()
                        )
                        isDeleting = true
                    } else {
                        isDeleting = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            toDelete.forEach { id ->
                                contentResolver.delete(
                                    ContentUris.withAppendedId(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                                    ), null, null
                                )
                            }
                            withContext(Dispatchers.Main) { onDeleteConfirmed?.invoke() }
                        }
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        permissionDenied  = false
                        permanentlyDenied = false
                        scanImages()
                    } else {
                        val canStillAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity, requiredPermission
                        )
                        if (canStillAsk) { permissionDenied = true;  permanentlyDenied = false }
                        else             { permissionDenied = false; permanentlyDenied = true  }
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

                            val showingSelectionToolbar =
                                selectionMode && activeTab == ActiveTab.ALL_IMAGES

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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        if (isDeleting) {
                                            CircularProgressIndicator(
                                                modifier    = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color       = Color.White
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

                                Button(
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            this@MainActivity, requiredPermission
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            permissionDenied  = false
                                            permanentlyDenied = false
                                            scanImages()
                                        } else {
                                            val canAsk = ActivityCompat
                                                .shouldShowRequestPermissionRationale(
                                                    this@MainActivity, requiredPermission
                                                )
                                            if (canAsk || !permanentlyDenied) {
                                                permissionLauncher.launch(requiredPermission)
                                            } else {
                                                startActivity(
                                                    Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    ).apply {
                                                        data = Uri.fromParts(
                                                            "package", packageName, null
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    enabled = !isScanning && !isDeleting
                                ) { Text("Scan Storage") }

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
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier              = Modifier.padding(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier    = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text  = "Scanning images...",
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
                                    // ── modifier = Modifier.weight(1f) passed in
                                    // so LazyColumn inside can use it correctly
                                    DuplicatesContent(
                                        groups            = exactGroups,
                                        selectedIds       = dupSelectedIds,
                                        isScanning        = isScanning,
                                        isDeleting        = isDeleting,
                                        onSelectionChange = { dupSelectedIds = it },
                                        onViewGroup       = { group ->
                                            viewerImages = group
                                            viewerIndex  = 0
                                            viewerOpen   = true
                                        },
                                        onUnselectGroup   = { groupIds ->
                                            dupSelectedIds = dupSelectedIds - groupIds
                                        },
                                        onDelete          = { launchDelete(dupSelectedIds) },
                                        modifier          = Modifier.weight(1f)
                                    )
                                }

                                ActiveTab.GROUPS -> {
                                    // ── modifier = Modifier.weight(1f) passed in
                                    GroupsContent(
                                        groups            = similarGroups,
                                        selectedIds       = groupSelectedIds,
                                        isScanning        = isScanning,
                                        isDeleting        = isDeleting,
                                        onSelectionChange = { groupSelectedIds = it },
                                        onViewGroup       = { group ->
                                            viewerImages = group
                                            viewerIndex  = 0
                                            viewerOpen   = true
                                        },
                                        onSelectGroup     = { groupIds ->
                                            groupSelectedIds = groupSelectedIds + groupIds
                                        },
                                        onUnselectGroup   = { groupIds ->
                                            groupSelectedIds = groupSelectedIds - groupIds
                                        },
                                        onDelete          = { launchDelete(groupSelectedIds) },
                                        modifier          = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                    } else {

                        val pagerState = rememberPagerState(
                            initialPage = viewerIndex,
                            pageCount   = { viewerImages.size }
                        )
                        var isZoomed by remember { mutableStateOf(false) }

                        BackHandler { viewerOpen = false }

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
        }
    }

    // ── DUPLICATES TAB composable
    // modifier param added — receives Modifier.weight(1f) from call site
    @Composable
    private fun DuplicatesContent(
        groups: List<List<ImageItem>>,
        selectedIds: Set<Long>,
        isScanning: Boolean,
        isDeleting: Boolean,
        onSelectionChange: (Set<Long>) -> Unit,
        onViewGroup: (List<ImageItem>) -> Unit,
        onUnselectGroup: (Set<Long>) -> Unit,
        onDelete: () -> Unit,
        modifier: Modifier = Modifier          // ← added
    ) {
        if (groups.isEmpty()) {
            Text(
                text     = if (isScanning) "" else "No exact duplicates found",
                style    = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        // modifier (which carries weight(1f)) applied here
        LazyColumn(modifier = modifier) {
            groups.forEachIndexed { groupIndex, group ->

                val sortedGroup = group.sortedByDescending { it.size }
                val groupIds    = sortedGroup.map { it.id }.toSet()
                val anySelected = groupIds.any { it in selectedIds }

                item(key = "dup_header_$groupIndex") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text  = "Group ${groupIndex + 1}  •  ${group.size} duplicates",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (!anySelected) {
                                Text(
                                    text  = "No images selected for deletion",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                        Row {
                            IconButton(onClick = { onViewGroup(sortedGroup) }) {
                                Icon(
                                    imageVector        = Icons.Filled.Search,
                                    contentDescription = "View group",
                                    tint               = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { onUnselectGroup(groupIds) }) {
                                Icon(
                                    imageVector        = Icons.Filled.Close,
                                    contentDescription = "Unselect group",
                                    tint               = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                sortedGroup.chunked(3).forEachIndexed { rowIndex, rowImages ->
                    item(key = "dup_${groupIndex}_row_$rowIndex") {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowImages.forEachIndexed { posInRow, image ->
                                val isSelected = selectedIds.contains(image.id)
                                val isKeep     = rowIndex == 0 && posInRow == 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable {
                                            onSelectionChange(
                                                if (isSelected) selectedIds - image.id
                                                else            selectedIds + image.id
                                            )
                                        }
                                ) {
                                    AsyncImage(
                                        model              = image.uri,
                                        contentDescription = null,
                                        contentScale       = ContentScale.Crop,
                                        modifier           = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp)
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.4f))
                                        )
                                        Icon(
                                            imageVector        = Icons.Filled.CheckCircle,
                                            contentDescription = "Selected for deletion",
                                            tint               = Color.White,
                                            modifier           = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(20.dp)
                                        )
                                    }
                                    if (isKeep) {
                                        Text(
                                            text     = "Keep",
                                            color    = Color.White,
                                            style    = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .background(Color(0xFF4CAF50).copy(alpha = 0.85f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            repeat(3 - rowImages.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (selectedIds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick  = onDelete,
                enabled  = !isDeleting,
                colors   = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isDeleting) "Deleting..." else "Delete Selected (${selectedIds.size})")
            }
        }
    }

    // ── GROUPS TAB composable
    // modifier param added — receives Modifier.weight(1f) from call site
    @Composable
    private fun GroupsContent(
        groups: List<List<ImageItem>>,
        selectedIds: Set<Long>,
        isScanning: Boolean,
        isDeleting: Boolean,
        onSelectionChange: (Set<Long>) -> Unit,
        onViewGroup: (List<ImageItem>) -> Unit,
        onSelectGroup: (Set<Long>) -> Unit,
        onUnselectGroup: (Set<Long>) -> Unit,
        onDelete: () -> Unit,
        modifier: Modifier = Modifier          // ← added
    ) {
        if (groups.isEmpty()) {
            Text(
                text     = if (isScanning) "" else "No similar groups found",
                style    = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        // modifier (which carries weight(1f)) applied here
        LazyColumn(modifier = modifier) {
            groups.forEachIndexed { groupIndex, group ->

                val sortedGroup = group.sortedByDescending { it.size }
                val groupIds    = sortedGroup.map { it.id }.toSet()
                val allSelected = groupIds.isNotEmpty() && groupIds.all { it in selectedIds }

                item(key = "grp_header_$groupIndex") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            text  = "Group ${groupIndex + 1}  •  ${group.size} similar",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (allSelected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = if (allSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        if (allSelected) onUnselectGroup(groupIds)
                                        else             onSelectGroup(groupIds)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (allSelected) {
                                    Icon(
                                        imageVector        = Icons.Filled.CheckCircle,
                                        contentDescription = "Deselect all in group",
                                        tint               = Color.White,
                                        modifier           = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(onClick = { onViewGroup(sortedGroup) }) {
                                Icon(
                                    imageVector        = Icons.Filled.Search,
                                    contentDescription = "View group",
                                    tint               = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(onClick = { onUnselectGroup(groupIds) }) {
                                Icon(
                                    imageVector        = Icons.Filled.Close,
                                    contentDescription = "Deselect group",
                                    tint               = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                sortedGroup.chunked(3).forEachIndexed { rowIndex, rowImages ->
                    item(key = "grp_${groupIndex}_row_$rowIndex") {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowImages.forEach { image ->
                                val isSelected = selectedIds.contains(image.id)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable {
                                            onSelectionChange(
                                                if (isSelected) selectedIds - image.id
                                                else            selectedIds + image.id
                                            )
                                        }
                                ) {
                                    AsyncImage(
                                        model              = image.uri,
                                        contentDescription = null,
                                        contentScale       = ContentScale.Crop,
                                        modifier           = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp)
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.4f))
                                        )
                                        Icon(
                                            imageVector        = Icons.Filled.CheckCircle,
                                            contentDescription = "Selected for deletion",
                                            tint               = Color.White,
                                            modifier           = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(20.dp)
                                        )
                                    }
                                }
                            }
                            repeat(3 - rowImages.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (selectedIds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick  = onDelete,
                enabled  = !isDeleting,
                colors   = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isDeleting) "Deleting..." else "Delete Selected (${selectedIds.size})")
            }
        }
    }

    private fun loadImages(): List<ImageItem> {
        val imageList  = mutableListOf<ImageItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            val idCol   = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (it.moveToNext()) {
                val id  = it.getLong(idCol)
                val sz  = it.getLong(sizeCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                imageList.add(ImageItem(id, uri, null, sz))
            }
        }
        return imageList
    }

    private fun calculatePerceptualHash(uri: Uri): Long? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize      = 4
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val original = android.graphics.BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()
            if (original == null) return null

            val resized = android.graphics.Bitmap.createScaledBitmap(original, 9, 8, true)
            original.recycle()

            val pixels = IntArray(9 * 8)
            resized.getPixels(pixels, 0, 9, 0, 0, 9, 8)
            resized.recycle()

            fun luma(p: Int): Int {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8)  and 0xFF
                val b =  p         and 0xFF
                return (299 * r + 587 * g + 114 * b) / 1000
            }

            var hash = 0L
            var bit  = 0
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    if (luma(pixels[row * 9 + col]) > luma(pixels[row * 9 + col + 1]))
                        hash = hash or (1L shl bit)
                    bit++
                }
            }
            hash
        } catch (e: Exception) { null }
    }

    private fun hammingDistance(a: Long, b: Long): Int =
        java.lang.Long.bitCount(a xor b)

    private fun findGroupsByThreshold(
        images: List<ImageItem>,
        threshold: Int
    ): List<List<ImageItem>> {
        val valid = images.filter { it.hash != null }
        val n     = valid.size

        val neighbors = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (hammingDistance(valid[i].hash!!, valid[j].hash!!) <= threshold) {
                    neighbors[i].add(j)
                    neighbors[j].add(i)
                }
            }
        }

        val visited = BooleanArray(n)
        val groups  = mutableListOf<List<ImageItem>>()

        for (start in 0 until n) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val group = mutableListOf<ImageItem>()
            queue.add(start); visited[start] = true
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                group.add(valid[cur])
                for (nb in neighbors[cur]) {
                    if (!visited[nb]) { visited[nb] = true; queue.add(nb) }
                }
            }
            if (group.size > 1) groups.add(group)
        }

        return groups.sortedByDescending { it.size }
    }
}