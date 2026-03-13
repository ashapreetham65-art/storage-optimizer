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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.storageoptimizer.ui.theme.StorageOptimizerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StorageOptimizerTheme {

                var imageCount by remember { mutableStateOf(0) }
                var permissionDenied by remember { mutableStateOf(false) }
                var permanentlyDenied by remember { mutableStateOf(false) }

                val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        permissionDenied = false
                        permanentlyDenied = false
                        imageCount = countImages()
                    } else {
                        // After denial, if shouldShowRationale is false → permanently denied
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
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Text(
                            text = "Images found: $imageCount",
                            style = MaterialTheme.typography.headlineMedium
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    requiredPermission
                                ) == PackageManager.PERMISSION_GRANTED

                                if (granted) {
                                    permissionDenied = false
                                    permanentlyDenied = false
                                    imageCount = countImages()
                                } else {
                                    val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                                        this@MainActivity,
                                        requiredPermission
                                    )
                                    if (canAsk || !permanentlyDenied) {
                                        // Show system popup
                                        permissionLauncher.launch(requiredPermission)
                                    } else {
                                        // Permanently denied — open app settings
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", packageName, null)
                                        }
                                        startActivity(intent)
                                    }
                                }
                            }
                        ) {
                            Text("Scan Storage")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when {
                            permissionDenied -> Text(
                                text = "No permission granted. Tap \"Scan Storage\" to try again.",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            permanentlyDenied -> Text(
                                text = "Permission permanently denied. Tap \"Scan Storage\" to open Settings and enable it manually.",
                                color = Color(0xFFFF6600),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun countImages(): Int {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )
        val count = cursor?.count ?: 0
        cursor?.close()
        return count
    }
}