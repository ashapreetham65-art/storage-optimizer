package com.example.storageoptimizer.ui.home

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
import androidx.compose.ui.geometry.Rect
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
    onFilesReviewClick: () -> Unit
) {
    val context         = LocalContext.current
    val isScanning      by viewModel.isScanning.collectAsState()
    val isLoadingFromDb by viewModel.isLoadingFromDb.collectAsState()
    val images          by viewModel.images.collectAsState()
    val exactGroups     by viewModel.exactGroups.collectAsState()
    val lastScannedAt   by viewModel.lastScannedAt.collectAsState()
    val files           by viewModel.files.collectAsState()
    val isScanningFiles by viewModel.isScanningFiles.collectAsState()

    val storageStat  = remember { StatFs(Environment.getDataDirectory().path) }
    val totalBytes   = storageStat.totalBytes
    val freeBytes    = storageStat.availableBytes
    val usedBytes    = totalBytes - freeBytes
    val usedFraction = usedBytes.toFloat() / totalBytes.toFloat()

    val imageCount       = images.size
    val duplicateCount   = exactGroups.sumOf { it.size - 1 }
    val reclaimableBytes = viewModel.reclaimableBytes
    val previewImages    = images.take(3)

    val arcProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        arcProgress.animateTo(
            targetValue   = usedFraction,
            animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
        )
    }

    // Auto-scan files silently on every app open if permission already granted.
    // Files are not persisted to DB so we re-scan from MediaStore each launch —
    // it's fast (no hashing) and avoids showing "Scan first" after a restart.
    LaunchedEffect(Unit) {
        val alreadyHasAccess =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.os.Environment.isExternalStorageManager()
            else
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

        if (alreadyHasAccess && files.isEmpty() && !isScanningFiles) {
            viewModel.scanFiles(context.contentResolver)
        }
    }

    val requiredImagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    // ── Step 2: after images are scanned, also scan files ────────────────────
    // MANAGE_EXTERNAL_STORAGE cannot be requested via a normal dialog —
    // we send the user to the Settings "All files access" page instead.
    fun hasAllFilesAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    // Returns from the "All files access" Settings page
    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User came back — scan files whether they granted or not
        // (if not granted we still get the ~11 app-owned files, but at least
        //  the scan runs rather than hanging forever)
        viewModel.scanFiles(context.contentResolver)
    }

    fun scanFilesWithPermission() {
        if (hasAllFilesAccess()) {
            viewModel.scanFiles(context.contentResolver)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            allFilesSettingsLauncher.launch(intent)
        } else {
            // Android <= 9: READ_EXTERNAL_STORAGE already granted at this point
            viewModel.scanFiles(context.contentResolver)
        }
    }

    // ── Step 1: image permission launcher — chains into file scan on grant ───
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
            // Chain: after images, also request file access
            scanFilesWithPermission()
        }
    }

    fun handleScanClick() {
        val imageGranted = ContextCompat.checkSelfPermission(
            context, requiredImagePermission
        ) == PackageManager.PERMISSION_GRANTED

        if (imageGranted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
            // Also scan files (will request All Files Access if needed)
            scanFilesWithPermission()
        } else {
            permissionLauncher.launch(requiredImagePermission)
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
                enabled = !isScanning && !isLoadingFromDb,
                shape   = RoundedCornerShape(16.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = ButtonBackground),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isScanning) {
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
            Text(
                text = when {
                    isLoadingFromDb   -> "Loading saved data..."
                    isScanning        -> "Scanning your storage..."
                    scannedAt != null -> "Last scanned ${timeAgo(scannedAt)}"
                    imageCount > 0    -> "Data loaded — tap Scan Storage to refresh"
                    else              -> "Tap Scan Storage to get started"
                },
                color    = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Images card
            ImagesCard(
                imageCount       = imageCount,
                duplicateCount   = duplicateCount,
                reclaimableBytes = reclaimableBytes,
                previewImages    = previewImages,
                hasData          = viewModel.hasData(),
                onReviewClick    = onReviewClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Files card
            FilesCard(
                fileCount       = files.size,
                totalFileSize   = files.sumOf { it.size },
                isScanning      = isScanningFiles,
                hasData         = files.isNotEmpty(),
                onReviewClick   = onFilesReviewClick,
                onScanClick     = { scanFilesWithPermission() }
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text       = "Images",
                        color      = TextPrimary,
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(BarStart, BarEnd)))
                )

                Spacer(modifier = Modifier.height(10.dp))

                val statsText = buildString {
                    if (imageCount > 0) {
                        append("$imageCount photos")
                        if (duplicateCount > 0) append(" • $duplicateCount duplicates")
                        if (reclaimableBytes > 0L)
                            append(" • Can free ${formatBytes(reclaimableBytes)}")
                    } else {
                        append("Tap Scan Storage to analyse your images")
                    }
                }
                Text(text = statsText, color = TextSecondary, fontSize = 13.sp)

                Spacer(modifier = Modifier.height(16.dp))

                if (previewImages.isNotEmpty()) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        previewImages.forEach { image ->
                            AsyncImage(
                                model              = image.uri,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                        repeat(3 - previewImages.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick        = onReviewClick,
                        enabled        = hasData,
                        shape          = RoundedCornerShape(14.dp),
                        colors         = ButtonDefaults.buttonColors(containerColor = ReviewButton),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text       = if (hasData) "Review" else "Scan first",
                            color      = TextPrimary,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ── Files summary card ────────────────────────────────────────────────────────
@Composable
private fun FilesCard(
    fileCount:     Int,
    totalFileSize: Long,
    isScanning:    Boolean,
    hasData:       Boolean,
    onReviewClick: () -> Unit,
    onScanClick:   () -> Unit
) {
    val filePurple = Color(0xFF9C6FE4)
    val fileBlue   = Color(0xFF4FC3F7)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(filePurple, fileBlue))
            )
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

                // Title row
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text       = "Files",
                        color      = TextPrimary,
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Show total size once we have data
                    if (hasData && totalFileSize > 0L) {
                        Text(
                            text     = "${formatBytes(totalFileSize)} total",
                            color    = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Gradient bar — reversed direction from Images
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(listOf(filePurple, fileBlue))
                        )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Stats text
                val statsText = when {
                    isScanning -> "Scanning files..."
                    hasData    -> buildString {
                        append("$fileCount files found")
                        if (totalFileSize > 0L) append(" • ${formatBytes(totalFileSize)} used")
                    }
                    else       -> "Tap Review to scan your files"
                }
                Text(text = statsText, color = TextSecondary, fontSize = 13.sp)

                Spacer(modifier = Modifier.height(16.dp))

                // File type visual — three square tiles mirroring the images thumbnail row
                if (!isScanning) {
                    FileTypePreview(fileCount = fileCount)
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    // Loading shimmer bar while scanning
                    LinearProgressIndicator(
                        modifier     = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color        = filePurple,
                        trackColor   = Color(0xFF2A2F4A)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick        = if (hasData) onReviewClick else onScanClick,
                        enabled        = !isScanning,
                        shape          = RoundedCornerShape(14.dp),
                        colors         = ButtonDefaults.buttonColors(
                            containerColor         = ReviewButton,
                            disabledContainerColor = ReviewButton.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color       = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text       = "Scanning...",
                                color      = TextPrimary,
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text       = if (hasData) "Review" else "Scan first",
                                color      = TextPrimary,
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

/**
 * Three equal-width square tiles — mirrors the 3-image thumbnail row in ImagesCard.
 * Matches the reference screenshot exactly: dark tile bg, icon centred, bold white
 * label underneath. Icons are drawn with Canvas to match the reference precisely.
 */
@Composable
private fun FileTypePreview(fileCount: Int) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FileTile(label = "PDFs")      { PdfDocIcon() }
        FileTile(label = "APKs")      { ApkAndroidIcon() }
        FileTile(label = "Archives")  { ArchiveDocIcon() }
    }
}

/** Dark tile — same shape/size as image thumbnail cells in ImagesCard */
@Composable
private fun RowScope.FileTile(
    label: String,
    icon:  @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF12151F)),   // same near-black used in reference
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier            = Modifier
                .padding(horizontal = 6.dp)
                .padding(bottom = 1.dp)
        ) {
            icon()
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text       = label,
                color      = Color.White,
                fontSize   = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF icon — red document with folded top-right corner + 3 white lines.
// Matches the blue "Documents" icon in the reference, recoloured red.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PdfDocIcon() {
    Canvas(
        modifier = Modifier
            .width(38.dp)
            .height(46.dp)
    ) {
        val w      = size.width
        val h      = size.height
        val fold   = min(w, h) * 0.25f
        val r      = 6.dp.toPx()
        val body   = Color(0xFFE53935)  // solid red
        val shadow = Color(0xFFB71C1C)  // darker red fold shadow
        val white  = Color.White

        // Main document body
        drawPath(
            path  = docBodyPath(w, h, fold, r),
            color = body
        )
        // Fold triangle (darker shade, top-right)
        drawPath(
            path  = foldTriPath(w, fold),
            color = shadow
        )
        // Three white text-lines
        drawDocLines(w, h, white)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// APK icon — green rounded-square badge + white Android bugdroid head.
// The head is a half-ellipse (dome), two angled antennae with dot tips,
// two circular eyes punched in green — exactly as shown in the reference.
// ─────────────────────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────────
// APK icon — green document shape, same layout as PDF/Archive icons.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ApkAndroidIcon() {
    Canvas(
        modifier = Modifier
            .width(38.dp)
            .height(46.dp)
    ) {
        val w      = size.width
        val h      = size.height
        val fold   = min(w, h) * 0.25f
        val r      = 6.dp.toPx()
        drawPath(docBodyPath(w, h, fold, r), color = Color(0xFF43A047))
        drawPath(foldTriPath(w, fold),        color = Color(0xFF2E7D32))
        drawDocLines(w, h, Color.White)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Archive icon — tan/brown document + folded corner + 3 white lines.
// Identical layout to the PDF icon, just a warm brown body colour.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ArchiveDocIcon() {
    Canvas(
        modifier = Modifier
            .width(38.dp)
            .height(46.dp)
    ) {
        val w      = size.width
        val h      = size.height
        val fold   = min(w, h) * 0.25f
        val r      = 6.dp.toPx()
        val body   = Color(0xFFA67C52)   // warm tan-brown matching the reference
        val shadow = Color(0xFF7A5535)   // darker brown fold shadow
        val white  = Color.White

        drawPath(docBodyPath(w, h, fold, r), color = body)
        drawPath(foldTriPath(w, fold),        color = shadow)
        drawDocLines(w, h, white)
    }
}

// ── Shared path helpers (used by both doc icons) ──────────────────────────────

private fun docBodyPath(w: Float, h: Float, fold: Float, r: Float) = Path().apply {
    moveTo(r, 0f)
    lineTo(w - fold, 0f)    // top edge → fold cut
    lineTo(w, fold)         // fold diagonal
    lineTo(w, h - r)
    quadraticBezierTo(w, h, w - r, h)
    lineTo(r, h)
    quadraticBezierTo(0f, h, 0f, h - r)
    lineTo(0f, r)
    quadraticBezierTo(0f, 0f, r, 0f)
    close()
}

private fun foldTriPath(w: Float, fold: Float) = Path().apply {
    moveTo(w - fold, 0f)
    lineTo(w, fold)
    lineTo(w - fold, fold)
    close()
}

/** Three white horizontal lines — top two full-width, bottom one ~65% width */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDocLines(
    w: Float, h: Float, color: Color
) {
    val x1  = w * 0.15f
    val x2L = w * 0.83f   // long end
    val x2S = w * 0.57f   // short end (bottom line)
    val sw  = 3.2f
    val cap = StrokeCap.Round
    drawLine(color, Offset(x1, h * 0.45f), Offset(x2L, h * 0.45f), sw, cap)
    drawLine(color, Offset(x1, h * 0.58f), Offset(x2L, h * 0.58f), sw, cap)
    drawLine(color, Offset(x1, h * 0.71f), Offset(x2S, h * 0.71f), sw, cap)
}

private fun timeAgo(epochMs: Long): String {
    val diffMs      = System.currentTimeMillis() - epochMs
    val diffSeconds = diffMs / 1000
    val diffMinutes = diffSeconds / 60
    val diffHours   = diffMinutes / 60
    val diffDays    = diffHours / 24

    return when {
        diffSeconds < 10  -> "just now"
        diffSeconds < 60  -> "${diffSeconds}s ago"
        diffMinutes == 1L -> "1 min ago"
        diffMinutes < 60  -> "${diffMinutes} mins ago"
        diffHours == 1L   -> "1 hour ago"
        diffHours < 24    -> "${diffHours} hours ago"
        diffDays == 1L    -> "yesterday"
        else              -> "${diffDays} days ago"
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb)
    else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}