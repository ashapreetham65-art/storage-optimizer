package com.example.storageoptimizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.storageoptimizer.ui.theme.StorageOptimizerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImageItem(
    val id: Long,
    val uri: Uri
)

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StorageOptimizerTheme {

                var images by remember { mutableStateOf(listOf<ImageItem>()) }
                var permissionDenied by remember { mutableStateOf(false) }
                var permanentlyDenied by remember { mutableStateOf(false) }
                var isScanning by remember { mutableStateOf(false) }
                var selectionMode by remember { mutableStateOf(false) }
                var selectedImages by remember { mutableStateOf(setOf<Long>()) }

                val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                fun scanImages() {
                    isScanning = true
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            loadImages()
                        }
                        images = result
                        isScanning = false
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Spacer(modifier = Modifier.height(48.dp))

                        Text(
                            text = "Images found: ${images.size}",
                            style = MaterialTheme.typography.headlineMedium
                        )

                        // Only visible during selection mode
                        if (selectionMode) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Selected: ${selectedImages.size}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

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
                            enabled = !isScanning
                        ) {
                            Text("Scan Storage")
                        }

                        // Permission status messages
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // Scanning indicator
                        if (isScanning) {
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

                        // Image grid
                        if (images.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    items = images,
                                    key = { it.id }
                                ) { image ->

                                    val isSelected = selectedImages.contains(image.id)

                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    if (selectionMode) {
                                                        // Toggle selection
                                                        val updated = if (isSelected)
                                                            selectedImages - image.id
                                                        else
                                                            selectedImages + image.id

                                                        selectedImages = updated

                                                        // Auto-exit selection mode
                                                        // when nothing is selected
                                                        if (updated.isEmpty()) {
                                                            selectionMode = false
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    selectionMode = true
                                                    selectedImages = selectedImages + image.id
                                                }
                                            )
                                    ) {
                                        // Image
                                        AsyncImage(
                                            model = image.uri,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        // Overlay + checkmark — only on selected images
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
                    }
                }
            }
        }
    }

    private fun loadImages(): List<ImageItem> {
        val imageList = mutableListOf<ImageItem>()

        val projection = arrayOf(MediaStore.Images.Media._ID)

        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                imageList.add(ImageItem(id, uri))
            }
        }

        return imageList
    }
}