package com.example.storageoptimizer.ui.theme.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.MainViewModel
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Colours
private val BackgroundTop    = Color(0xFF0F1422)
private val BackgroundBottom = Color(0xFF1A1F35)
private val ArcBlue          = Color(0xFF4FC3F7)
private val ArcPurple        = Color(0xFF9C6FE4)
private val GlowBlue         = Color(0x554FC3F7)
private val GlowPurple       = Color(0x559C6FE4)
private val CardBackground   = Color(0xFF1C2138)
private val CardBorderStart  = Color(0xFF4FC3F7)
private val CardBorderEnd    = Color(0xFF9C6FE4)
private val BarStart         = Color(0xFF4FC3F7)
private val BarEnd           = Color(0xFF9C6FE4)
private val TextPrimary      = Color(0xFFEEF0F8)
private val TextSecondary    = Color(0xFF8A90A8)
private val ButtonBackground = Color(0xFF252B42)
private val ReviewButton     = Color(0xFF2A3050)

@Composable
fun HomeScreen(
    viewModel:          MainViewModel,
    onReviewClick:      () -> Unit,
    onFilesReviewClick: () -> Unit,
    onAppsReviewClick:  () -> Unit
) {
    val context         = LocalContext.current
    val prefs           = remember {
        context.getSharedPreferences("storage_optimizer_prefs", android.content.Context.MODE_PRIVATE)
    }
    val isScanning      by viewModel.isScanning.collectAsState()
    val isLoadingFromDb by viewModel.isLoadingFromDb.collectAsState()
    val images          by viewModel.images.collectAsState()
    val exactGroups     by viewModel.exactGroups.collectAsState()
    val lastScannedAt   by viewModel.lastScannedAt.collectAsState()
    val files           by viewModel.files.collectAsState()
    val isScanningFiles by viewModel.isScanningFiles.collectAsState()
    val fileDuplicateGroups by viewModel.fileDuplicateGroups.collectAsState()
    val apps            by viewModel.apps.collectAsState()
    val isScanningApps  by viewModel.isScanningApps.collectAsState()

    val scope = rememberCoroutineScope()

    val storageStat  = remember { StatFs(Environment.getDataDirectory().path) }
    val totalBytes   = storageStat.totalBytes
    val freeBytes    = storageStat.availableBytes
    val usedBytes    = totalBytes - freeBytes
    val usedFraction = usedBytes.toFloat() / totalBytes.toFloat()

    val imageCount       = images.size
    val duplicateCount   = exactGroups.sumOf { it.size - 1 }
    val reclaimableBytes = viewModel.reclaimableBytes
    val previewImages    = images.take(3)

    // Apps preview — first 4 icons, computed only when list changes
    val previewAppIcons = remember(apps) {
        apps.take(4).map { it.icon }
    }
    val totalAppsSize = remember(apps) { apps.sumOf { it.apkSize + it.dataSize } }

    val arcProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        arcProgress.animateTo(
            targetValue   = usedFraction,
            animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(Unit) {
        // Only auto-refresh if:
        // 1. User has done at least one scan before (lastScannedAt != null)
        // 2. Apps list is empty (not yet loaded this session)
        // 3. Not already scanning
        if (lastScannedAt != null && apps.isEmpty() && !isScanningApps) {
            viewModel.scanApps(context)
        }
    }

    val requiredImagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAllFilesAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    var imageGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredImagePermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.scanFiles(context.contentResolver)
    }

    val usageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User came back from Usage Access settings — re-scan apps with whatever was granted
        viewModel.scanApps(context)
    }

    fun requestUsageAccessAndScanApps() {
        if (com.example.storageoptimizer.engine.AppEngine.hasUsageStatsPermission(context)) {
            viewModel.scanApps(context)
        } else {
            usageAccessLauncher.launch(
                android.content.Intent(
                    android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun scanFilesWithPermission() {
        if (hasAllFilesAccess()) {
            viewModel.scanFiles(context.contentResolver)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesSettingsLauncher.launch(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
            )
        } else {
            viewModel.scanFiles(context.contentResolver)
        }
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User came back from app settings — check if permission was granted now
        val granted = ContextCompat.checkSelfPermission(
            context, requiredImagePermission
        ) == PackageManager.PERMISSION_GRANTED
        imageGranted = granted   // ← update state
        if (granted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
            scanFilesWithPermission()
            requestUsageAccessAndScanApps()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        imageGranted = isGranted   // ← update state so UI recomposes immediately
        if (isGranted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
            scanFilesWithPermission()
            requestUsageAccessAndScanApps()
        }
    }



    fun handleScanClick() {
        if (imageGranted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
            scanFilesWithPermission()
            requestUsageAccessAndScanApps()
        } else {
            // Check if we should show the rationale or go straight to settings.
            // shouldShowRequestPermissionRationale returns false in two cases:
            //   1. Permission never asked before → show dialog normally
            //   2. Permission permanently denied → must go to settings
            val activity = context as? androidx.activity.ComponentActivity
            val canShowRationale = activity?.shouldShowRequestPermissionRationale(
                requiredImagePermission
            ) ?: true

            if (!canShowRationale && prefs.getBoolean("permission_asked_before", false)) {
                // Permanently denied — send to app settings
                appSettingsLauncher.launch(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null)
                    )
                )
            } else {
                // First time or can show rationale — show the dialog
                prefs.edit().putBoolean("permission_asked_before", true).apply()
                permissionLauncher.launch(requiredImagePermission)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(72.dp))

            StorageGauge(
                progress   = arcProgress.value,
                totalBytes = totalBytes,
                modifier   = Modifier.size(200.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { handleScanClick() },
                enabled = !isScanning && !isScanningFiles && !isScanningApps && !isLoadingFromDb,
                shape   = RoundedCornerShape(16.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = ButtonBackground),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isScanning || isScanningFiles || isScanningApps) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text       = "Scanning...",
                        color      = TextPrimary,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text       = "Scan Storage",
                        color      = TextPrimary,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))


            val scannedAt = lastScannedAt
            // Detect if permission is permanently denied so we can show guidance
            // Replace the static permissionPermanentlyDenied remember block with this:
            var permissionPermanentlyDenied by remember { mutableStateOf(false) }

            // Recheck on every resume — this fires after permission dialogs dismiss
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, requiredImagePermission
                        ) == PackageManager.PERMISSION_GRANTED
                        imageGranted = granted
                        permissionPermanentlyDenied = !granted &&
                                prefs.getBoolean("permission_asked_before", false) &&
                                (context as? androidx.activity.ComponentActivity)
                                    ?.shouldShowRequestPermissionRationale(requiredImagePermission) == false
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            Text(
                text = when {
                    isLoadingFromDb                                  -> "Loading saved data..."
                    isScanning || isScanningFiles || isScanningApps  -> "Scanning your storage..."
                    scannedAt != null                                -> "Last scanned ${timeAgo(scannedAt)}"
                    imageCount > 0                                   -> "Data loaded — tap Scan Storage to refresh"
                    permissionPermanentlyDenied                      -> "Permissions → Files & media → Allow"
                    else                                             -> "Tap Scan Storage to get started"
                },
                color    = if (permissionPermanentlyDenied) Color(0xFFFF9800) else TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Images card ───────────────────────────────────────────────────
            ImagesCard(
                imageCount       = imageCount,
                duplicateCount   = duplicateCount,
                reclaimableBytes = reclaimableBytes,
                previewImages    = previewImages,
                hasData          = viewModel.hasData(),
                isScanning       = isScanning,
                onReviewClick    = {
                    onReviewClick()
                    scope.launch {
                        delay(350)
                        if (imageGranted) viewModel.refresh(context.contentResolver)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Files card ────────────────────────────────────────────────────
            FilesCard(
                fileCount          = files.size,
                totalFileSize      = files.sumOf { it.size },
                duplicateFileCount = fileDuplicateGroups.sumOf { it.size - 1 },
                isScanning         = isScanningFiles,
                hasData            = files.isNotEmpty(),
                onReviewClick      = {
                    onFilesReviewClick()
                    scope.launch {
                        delay(350)
                        scanFilesWithPermission()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Apps card ─────────────────────────────────────────────────────
            AppsCard(
                appCount      = apps.size,
                totalAppsSize = totalAppsSize,
                appIcons      = previewAppIcons,
                isScanning    = isScanningApps,
                hasData       = apps.isNotEmpty(),
                onReviewClick = {
                    onAppsReviewClick()
                    scope.launch {
                        delay(350)
                        viewModel.scanApps(context)
                    }
                }
            )
        }
    }
}

// ── Storage gauge ─────────────────────────────────────────────────────────────
@Composable
private fun StorageGauge(
    progress:   Float,
    totalBytes: Long,
    modifier:   Modifier = Modifier
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke     = 18.dp.toPx()
            val glowStroke = 38.dp.toPx()
            val padding    = glowStroke / 2f
            val diameter   = min(size.width, size.height) - padding * 2
            val arcSize    = Size(diameter, diameter)
            val topLeft    = Offset(
                x = (size.width  - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val startAngle  = -90f
            val fullSweep   = 360f
            val filledSweep = fullSweep * progress.coerceIn(0f, 1f)

            drawArc(
                color      = Color(0xFF2A2F4A),
                startAngle = startAngle,
                sweepAngle = fullSweep,
                useCenter  = false,
                topLeft    = topLeft,
                size       = arcSize,
                style      = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            if (filledSweep > 0f) {
                drawIntoCanvas { canvas ->
                    val glowPaint = Paint().apply {
                        asFrameworkPaint().apply {
                            isAntiAlias = true
                            style       = android.graphics.Paint.Style.STROKE
                            strokeWidth = glowStroke
                            strokeCap   = android.graphics.Paint.Cap.ROUND
                            maskFilter  = android.graphics.BlurMaskFilter(
                                30f, android.graphics.BlurMaskFilter.Blur.NORMAL
                            )
                        }
                        shader = android.graphics.LinearGradient(
                            topLeft.x, topLeft.y,
                            topLeft.x + arcSize.width, topLeft.y + arcSize.height,
                            intArrayOf(GlowBlue.toArgb(), GlowPurple.toArgb()),
                            null, android.graphics.Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawArc(
                        left = topLeft.x, top = topLeft.y,
                        right = topLeft.x + arcSize.width, bottom = topLeft.y + arcSize.height,
                        startAngle = startAngle, sweepAngle = filledSweep,
                        useCenter = false, paint = glowPaint
                    )
                }
                drawIntoCanvas { canvas ->
                    val arcPaint = Paint().apply {
                        asFrameworkPaint().apply {
                            isAntiAlias = true
                            style       = android.graphics.Paint.Style.STROKE
                            strokeWidth = stroke
                            strokeCap   = android.graphics.Paint.Cap.ROUND
                        }
                        shader = android.graphics.LinearGradient(
                            topLeft.x, topLeft.y,
                            topLeft.x + arcSize.width, topLeft.y + arcSize.height,
                            intArrayOf(ArcBlue.toArgb(), ArcPurple.toArgb()),
                            null, android.graphics.Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawArc(
                        left = topLeft.x, top = topLeft.y,
                        right = topLeft.x + arcSize.width, bottom = topLeft.y + arcSize.height,
                        startAngle = startAngle, sweepAngle = filledSweep,
                        useCenter = false, paint = arcPaint
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text       = "${(progress * 100).toInt()}%",
                color      = TextPrimary,
                fontSize   = 42.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp
            )
            Text(
                text     = "${formatBytes(totalBytes)} Total",
                color    = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

// ── Images summary card ───────────────────────────────────────────────────────
@Composable
private fun ImagesCard(
    imageCount:       Int,
    duplicateCount:   Int,
    reclaimableBytes: Long,
    previewImages:    List<com.example.storageoptimizer.data.ImageItem>,
    hasData:          Boolean,
    isScanning:       Boolean,
    onReviewClick:    () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(CardBorderStart, CardBorderEnd)))
            .padding(1.5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(19.dp))
                .background(CardBackground)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(text = "Images", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (reclaimableBytes > 0L) {
                        Text(
                            text     = "${formatBytes(reclaimableBytes)} reclaimable",
                            color    = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(BarStart, BarEnd)))
                )
                Spacer(modifier = Modifier.height(10.dp))
                val statsText = buildString {
                    when {
                        isScanning && imageCount == 0 -> append("Scanning your images...")
                        imageCount > 0 -> {
                            append("$imageCount photos")
                            if (duplicateCount > 0) append(" • $duplicateCount duplicates")
                            if (reclaimableBytes > 0L) append(" • Can free ${formatBytes(reclaimableBytes)}")
                        }
                        else -> append("Tap Scan Storage to analyse your images")
                    }
                }
                Text(text = statsText, color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                if (isScanning) {
                    LinearProgressIndicator(
                        modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color      = BarStart,
                        trackColor = Color(0xFF2A2F4A)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (previewImages.isNotEmpty()) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        previewImages.forEach { image ->
                            AsyncImage(
                                model              = image.uri,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            )
                        }
                        repeat(3 - previewImages.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick        = onReviewClick,
                        enabled        = hasData && !isScanning,
                        shape          = RoundedCornerShape(14.dp),
                        colors         = ButtonDefaults.buttonColors(containerColor = ReviewButton),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(if (hasData) "Review" else "Scan first", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Files summary card ────────────────────────────────────────────────────────
@Composable
private fun FilesCard(
    fileCount:          Int,
    totalFileSize:      Long,
    duplicateFileCount: Int,
    isScanning:         Boolean,
    hasData:            Boolean,
    onReviewClick:      () -> Unit
) {
    val filePurple = Color(0xFF9C6FE4)
    val fileBlue   = Color(0xFF4FC3F7)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(filePurple, fileBlue)))
            .padding(1.5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(19.dp))
                .background(CardBackground)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(text = "Files", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (hasData && totalFileSize > 0L) {
                        Text(text = "${formatBytes(totalFileSize)} total", color = TextSecondary, fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(filePurple, fileBlue)))
                )
                Spacer(modifier = Modifier.height(10.dp))
                val statsText = when {
                    isScanning -> "Scanning your files..."
                    hasData    -> buildString {
                        append("$fileCount files found")
                        if (duplicateFileCount > 0) append(" • $duplicateFileCount duplicates found")
                        else append(" • No duplicates found")
                    }
                    else -> "Tap Scan Storage to analyse your files"
                }
                Text(text = statsText, color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                if (isScanning) {
                    LinearProgressIndicator(
                        modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color      = filePurple,
                        trackColor = Color(0xFF2A2F4A)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (hasData) {
                    FileTypePreview()
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick        = onReviewClick,
                        enabled        = hasData && !isScanning,
                        shape          = RoundedCornerShape(14.dp),
                        colors         = ButtonDefaults.buttonColors(containerColor = ReviewButton),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(if (hasData) "Review" else "Scan first", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Apps summary card ─────────────────────────────────────────────────────────
@Composable
private fun AppsCard(
    appCount:      Int,
    totalAppsSize: Long,
    appIcons:      List<android.graphics.drawable.Drawable>,
    isScanning:    Boolean,
    hasData:       Boolean,
    onReviewClick: () -> Unit
) {
    val appStart = Color(0xFF5C6BC0)
    val appEnd   = Color(0xFF26C6DA)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(appStart, appEnd)))
            .padding(1.5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(19.dp))
                .background(CardBackground)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(text = "Apps", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (hasData && totalAppsSize > 0L) {
                        Text(text = "${formatBytes(totalAppsSize)} total", color = TextSecondary, fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(appStart, appEnd)))
                )
                Spacer(modifier = Modifier.height(10.dp))
                val statsText = when {
                    isScanning -> "Scanning your apps..."
                    hasData    -> "$appCount apps installed • ${formatBytes(totalAppsSize)} used"
                    else       -> "Tap Scan Storage to analyse your apps"
                }
                Text(text = statsText, color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                if (isScanning) {
                    LinearProgressIndicator(
                        modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color      = appStart,
                        trackColor = Color(0xFF2A2F4A)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (hasData && appIcons.isNotEmpty()) {
                    AppIconPreview(appIcons = appIcons)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick        = onReviewClick,
                        enabled        = hasData && !isScanning,
                        shape          = RoundedCornerShape(14.dp),
                        colors         = ButtonDefaults.buttonColors(
                            containerColor         = ReviewButton,
                            disabledContainerColor = ReviewButton.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(
                                text       = if (hasData) "Review" else "Scan first",
                                color      = if (hasData) TextPrimary else TextPrimary.copy(alpha = 0.5f),
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── App icon preview row ──────────────────────────────────────────────────────
@Composable
private fun AppIconPreview(appIcons: List<android.graphics.drawable.Drawable>) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .weight(1f).aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF12151F)),
                contentAlignment = Alignment.Center
            ) {
                if (index < appIcons.size) {
                    val bitmap = remember(appIcons[index]) {
                        val bmp = android.graphics.Bitmap.createBitmap(
                            appIcons[index].intrinsicWidth.coerceAtLeast(1),
                            appIcons[index].intrinsicHeight.coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bmp)
                        appIcons[index].setBounds(0, 0, canvas.width, canvas.height)
                        appIcons[index].draw(canvas)
                        bmp.asImageBitmap()
                    }
                    Image(
                        bitmap             = bitmap,
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
            }
        }
    }
}

// ── File type preview tiles ───────────────────────────────────────────────────
@Composable
private fun FileTypePreview() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FileTile(label = "PDFs")     { PdfDocIcon() }
        FileTile(label = "APKs")     { ApkAndroidIcon() }
        FileTile(label = "Archives") { ArchiveDocIcon() }
    }
}

@Composable
private fun RowScope.FileTile(label: String, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f).aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF12151F)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier            = Modifier.padding(horizontal = 6.dp).padding(bottom = 1.dp)
        ) {
            icon()
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun PdfDocIcon() {
    Canvas(modifier = Modifier.width(38.dp).height(46.dp)) {
        val w = size.width; val h = size.height; val fold = min(w,h)*0.25f; val r = 6.dp.toPx()
        drawPath(docBodyPath(w,h,fold,r), color = Color(0xFFE53935))
        drawPath(foldTriPath(w,fold),     color = Color(0xFFB71C1C))
        drawDocLines(w, h, Color.White)
    }
}
@Composable private fun ApkAndroidIcon() {
    Canvas(modifier = Modifier.width(38.dp).height(46.dp)) {
        val w = size.width; val h = size.height; val fold = min(w,h)*0.25f; val r = 6.dp.toPx()
        drawPath(docBodyPath(w,h,fold,r), color = Color(0xFF43A047))
        drawPath(foldTriPath(w,fold),     color = Color(0xFF2E7D32))
        drawDocLines(w, h, Color.White)
    }
}
@Composable private fun ArchiveDocIcon() {
    Canvas(modifier = Modifier.width(38.dp).height(46.dp)) {
        val w = size.width; val h = size.height; val fold = min(w,h)*0.25f; val r = 6.dp.toPx()
        drawPath(docBodyPath(w,h,fold,r), color = Color(0xFFA67C52))
        drawPath(foldTriPath(w,fold),     color = Color(0xFF7A5535))
        drawDocLines(w, h, Color.White)
    }
}

private fun docBodyPath(w: Float, h: Float, fold: Float, r: Float) = Path().apply {
    moveTo(r, 0f); lineTo(w - fold, 0f); lineTo(w, fold); lineTo(w, h - r)
    quadraticBezierTo(w, h, w - r, h); lineTo(r, h); quadraticBezierTo(0f, h, 0f, h - r)
    lineTo(0f, r); quadraticBezierTo(0f, 0f, r, 0f); close()
}
private fun foldTriPath(w: Float, fold: Float) = Path().apply {
    moveTo(w - fold, 0f); lineTo(w, fold); lineTo(w - fold, fold); close()
}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDocLines(w: Float, h: Float, color: Color) {
    val x1 = w*0.15f; val x2L = w*0.83f; val x2S = w*0.57f; val sw = 3.2f; val cap = StrokeCap.Round
    drawLine(color, Offset(x1, h*0.45f), Offset(x2L, h*0.45f), sw, cap)
    drawLine(color, Offset(x1, h*0.58f), Offset(x2L, h*0.58f), sw, cap)
    drawLine(color, Offset(x1, h*0.71f), Offset(x2S, h*0.71f), sw, cap)
}

private fun timeAgo(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    val s = diff/1000; val m = s/60; val h = m/60; val d = h/24
    return when {
        s < 10  -> "just now"; s < 60  -> "${s}s ago"
        m == 1L -> "1 min ago"; m < 60  -> "${m} mins ago"
        h == 1L -> "1 hour ago"; h < 24  -> "${h} hours ago"
        d == 1L -> "yesterday"; else    -> "${d} days ago"
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb)
    else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}

