package com.example.storageoptimizer.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
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
    viewModel:     MainViewModel,
    onReviewClick: () -> Unit
) {
    val context         = LocalContext.current
    val isScanning      by viewModel.isScanning.collectAsState()
    val isLoadingFromDb by viewModel.isLoadingFromDb.collectAsState()
    val images          by viewModel.images.collectAsState()
    val exactGroups     by viewModel.exactGroups.collectAsState()
    val lastScannedAt   by viewModel.lastScannedAt.collectAsState()

    // Real device storage
    val storageStat  = remember { StatFs(Environment.getDataDirectory().path) }
    val totalBytes   = storageStat.totalBytes
    val freeBytes    = storageStat.availableBytes
    val usedBytes    = totalBytes - freeBytes
    val usedFraction = usedBytes.toFloat() / totalBytes.toFloat()

    // Derived stats for the card
    val imageCount       = images.size
    val duplicateCount   = exactGroups.sumOf { it.size - 1 }
    val reclaimableBytes = viewModel.reclaimableBytes
    val previewImages    = images.take(3)

    // Arc animates to real value on first draw
    val arcProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        arcProgress.animateTo(
            targetValue   = usedFraction,
            animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
        )
    }

    // ── Permission setup ────────────────────────────────────────────────────
    // The permission needed depends on Android version.
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    // If permission is granted after the dialog, start the scan immediately.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
        }
        // If denied: button stays enabled so user can tap again.
        // We don't show an error here — GalleryScreen handles the detailed
        // permission messaging since it has the full settings-redirect flow.
    }

    // Helper called by the Scan Storage button — checks permission first.
    fun handleScanClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, requiredPermission
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            if (viewModel.hasData()) viewModel.refresh(context.contentResolver)
            else                     viewModel.scan(context.contentResolver)
        } else {
            // Check whether we can still ask, or if the user has permanently denied
            val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                context as androidx.activity.ComponentActivity,
                requiredPermission
            )
            if (canAsk) {
                // Show the system permission dialog
                permissionLauncher.launch(requiredPermission)
            } else {
                // First time asking OR permanently denied — try the dialog first.
                // If it's permanently denied the dialog won't appear and nothing
                // happens; on the next tap we could redirect to Settings, but
                // keeping it simple for V9: GalleryScreen handles the full flow.
                permissionLauncher.launch(requiredPermission)
            }
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(72.dp))

            StorageGauge(
                progress   = arcProgress.value,
                totalBytes = totalBytes,
                modifier   = Modifier.size(220.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Scan Storage button — checks permission, then triggers scan
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

            val scannedAt = lastScannedAt //get the value
            Text(
                text = when {
                    isLoadingFromDb       -> "Loading saved data..."
                    isScanning            -> "Scanning your storage..."
                    scannedAt != null -> "Last scanned ${timeAgo(scannedAt)}"
                    imageCount > 0        -> "Data loaded — tap Scan Storage to refresh"
                    else                  -> "Tap Scan Storage to get started"
                },
                color    = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            ImagesCard(
                imageCount       = imageCount,
                duplicateCount   = duplicateCount,
                reclaimableBytes = reclaimableBytes,
                previewImages    = previewImages,
                hasData          = viewModel.hasData(),
                onReviewClick    = onReviewClick
            )
        }
    }
}

// ── Storage gauge (startAngle = -90 = 12 o'clock) ────────────────────────────
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

// Returns a short human-readable string like "just now", "5 min ago",
// "2 hours ago", "3 days ago". Input is epoch milliseconds.
private fun timeAgo(epochMs: Long): String {
    val diffMs      = System.currentTimeMillis() - epochMs
    val diffSeconds = diffMs / 1000
    val diffMinutes = diffSeconds / 60
    val diffHours   = diffMinutes / 60
    val diffDays    = diffHours / 24

    return when {
        diffSeconds < 10          -> "just now"
        diffSeconds < 60          -> "${diffSeconds}s ago"
        diffMinutes == 1L         -> "1 min ago"
        diffMinutes < 60          -> "${diffMinutes} mins ago"
        diffHours == 1L           -> "1 hour ago"
        diffHours < 24            -> "${diffHours} hours ago"
        diffDays == 1L            -> "yesterday"
        else                      -> "${diffDays} days ago"
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb)
    else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}