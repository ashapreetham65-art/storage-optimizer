package com.example.storageoptimizer.ui.theme.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.ImageItem

// ── Palette ───────────────────────────────────────────────────────────────────
private val CardBg       = Color(0xFF1E2540)
private val CardBorder1  = Color(0xFF3A4468)
private val CardBorder2  = Color(0xFF4D5580)
private val HeaderText   = Color(0xFFEEF0F8)
private val SubText      = Color(0xFF8A90A8)
private val AccentBlue   = Color(0xFF5B8DEF)
private val AccentPurple = Color(0xFF9C6FE4)
private val OverlayDim   = Color(0x88000000)
private val IconBg       = Color(0xFF252D4A)
private val CountPillBg  = Color(0xFF1E2A4A)
private val SortPillBg   = Color(0xFF1C2340)
private val TileBorder1  = Color(0x55FFFFFF)
private val TileBorder2  = Color(0x15FFFFFF)

// ── Sort options specific to Duplicates tab ───────────────────────────────────
enum class DuplicateSortOrder {
    MOST_DUPLICATES,    // default — group with most copies first
    LEAST_DUPLICATES,   // group with fewest copies first
    SIZE_LARGE,         // group whose images are largest first
    SIZE_SMALL          // group whose images are smallest first
}

// ── Helper ────────────────────────────────────────────────────────────────────
private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else      -> "${bytes / 1024} KB"
    }
}

@Composable
fun DuplicatesContent(
    groups:            List<List<ImageItem>>,
    selectedIds:       Set<Long>,
    isScanning:        Boolean,
    isDeleting:        Boolean,
    modifier:          Modifier = Modifier,
    onSelectionChange: (Set<Long>) -> Unit,
    onViewGroup:       (List<ImageItem>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit,
    onDelete:          () -> Unit
) {
    // ── Sort state lives here — completely independent of the All Images tab
    var sortOrder    by remember { mutableStateOf(DuplicateSortOrder.MOST_DUPLICATES) }
    var dropdownOpen by remember { mutableStateOf(false) }

    val sortLabel = when (sortOrder) {
        DuplicateSortOrder.MOST_DUPLICATES  -> "Most Duplicates"
        DuplicateSortOrder.LEAST_DUPLICATES -> "Least Duplicates"
        DuplicateSortOrder.SIZE_LARGE       -> "Size (Large)"
        DuplicateSortOrder.SIZE_SMALL       -> "Size (Small)"
    }

    // Apply sort to the groups list
    val sortedGroups = remember(groups, sortOrder) {
        when (sortOrder) {
            DuplicateSortOrder.MOST_DUPLICATES  -> groups.sortedByDescending { it.size }
            DuplicateSortOrder.LEAST_DUPLICATES -> groups.sortedBy { it.size }
            DuplicateSortOrder.SIZE_LARGE       -> groups.sortedByDescending { g ->
                g.sumOf { it.size }
            }
            DuplicateSortOrder.SIZE_SMALL       -> groups.sortedBy { g ->
                g.sumOf { it.size }
            }
        }
    }

    if (groups.isEmpty()) {
        Box(
            modifier         = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text  = if (isScanning) "" else "No exact duplicates found",
                color = SubText,
                fontSize = 15.sp
            )
        }
        return
    }

    Box(modifier = modifier) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ── Sort bar — own dropdown, scoped to Duplicates only
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
                        DuplicateSortOrder.MOST_DUPLICATES  to "Most Duplicates",
                        DuplicateSortOrder.LEAST_DUPLICATES to "Least Duplicates",
                        DuplicateSortOrder.SIZE_LARGE       to "Size (Large)",
                        DuplicateSortOrder.SIZE_SMALL       to "Size (Small)"
                    ).forEach { (order, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text       = label,
                                    color      = if (sortOrder == order) AccentBlue
                                    else Color.White,
                                    fontSize   = 14.sp,
                                    fontWeight = if (sortOrder == order) FontWeight.SemiBold
                                    else FontWeight.Normal
                                )
                            },
                            onClick = {
                                sortOrder    = order
                                dropdownOpen = false
                            },
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

            // ── Scrollable group list
            LazyColumn(
                modifier       = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = 4.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                sortedGroups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) {
                        item(key = "gap_$groupIndex") {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    duplicateGroupCard(
                        groupIndex        = groupIndex,
                        group             = group,
                        selectedIds       = selectedIds,
                        showSizeInHeader  = sortOrder == DuplicateSortOrder.SIZE_LARGE ||
                                sortOrder == DuplicateSortOrder.SIZE_SMALL,
                        onSelectionChange = onSelectionChange,
                        onViewGroup       = onViewGroup,
                        onUnselectGroup   = onUnselectGroup
                    )
                }
            }
        }

        // ── Animated delete button
        AnimatedVisibility(
            visible  = selectedIds.isNotEmpty(),
            enter    = slideInVertically(
                initialOffsetY = { it },
                animationSpec  = tween(300)
            ) + fadeIn(animationSpec = tween(300)),
            exit     = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(250)
            ) + fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
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
                        text          = "Delete  (${selectedIds.size})",
                        color         = Color.White,
                        fontSize      = 17.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ── Single group card ─────────────────────────────────────────────────────────
private fun LazyListScope.duplicateGroupCard(
    groupIndex:        Int,
    group:             List<ImageItem>,
    selectedIds:       Set<Long>,
    showSizeInHeader:  Boolean,   // true when sorted by size — swaps header label
    onSelectionChange: (Set<Long>) -> Unit,
    onViewGroup:       (List<ImageItem>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit
) {
    val sortedGroup  = group.sortedByDescending { it.size }
    val groupIds     = sortedGroup.map { it.id }.toSet()
    val rows         = sortedGroup.chunked(3)

    // Size of only the selected images in this group
    val selectedSizeBytes = sortedGroup
        .filter { it.id in selectedIds }
        .sumOf { it.size }

    // ── Header
    item(key = "dup_header_$groupIndex") {
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
                // Left — pill label switches based on sort mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CountPillBg)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    AccentBlue.copy(alpha = 0.35f),
                                    AccentPurple.copy(alpha = 0.35f)
                                )
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    if (showSizeInHeader && selectedSizeBytes > 0L) {
                        // Size mode + something selected → show selected size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = formatBytes(selectedSizeBytes),
                                color      = AccentBlue,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text       = "selected",
                                color      = HeaderText,
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else if (showSizeInHeader) {
                        // Size mode but nothing selected yet → show total group size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = formatBytes(sortedGroup.sumOf { it.size }),
                                color      = AccentBlue,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text       = "total size",
                                color      = HeaderText,
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // Default mode → show duplicate count
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = "${group.size}",
                                color      = AccentBlue,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text       = "duplicates found",
                                color      = HeaderText,
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Right — icon buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(IconBg)
                            .clickable { onViewGroup(sortedGroup) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.RemoveRedEye,
                            contentDescription = "View group",
                            tint               = AccentBlue,
                            modifier           = Modifier.size(18.dp)
                        )
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
    }

    // ── Image rows
    rows.forEachIndexed { rowIndex, rowImages ->
        val isLastRow = rowIndex == rows.lastIndex

        item(key = "dup_${groupIndex}_row_$rowIndex") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = CardBg,
                        shape = if (isLastRow)
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
                            if (isLastRow) {
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(0f, size.height - cornerRadius)
                                    quadraticBezierTo(
                                        0f, size.height,
                                        cornerRadius, size.height
                                    )
                                    lineTo(size.width - cornerRadius, size.height)
                                    quadraticBezierTo(
                                        size.width, size.height,
                                        size.width, size.height - cornerRadius
                                    )
                                    lineTo(size.width, 0f)
                                }
                                canvas.drawPath(path, paint)
                            } else {
                                canvas.drawLine(
                                    p1    = Offset(0f, 0f),
                                    p2    = Offset(0f, size.height),
                                    paint = paint
                                )
                                canvas.drawLine(
                                    p1    = Offset(size.width, 0f),
                                    p2    = Offset(size.width, size.height),
                                    paint = paint
                                )
                            }
                        }
                    }
                    .padding(
                        start  = 10.dp,
                        end    = 10.dp,
                        top    = 4.dp,
                        bottom = if (isLastRow) 10.dp else 4.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowImages.forEach { image ->
                    val isSelected = selectedIds.contains(image.id)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(TileBorder1, TileBorder2)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clip(RoundedCornerShape(10.dp))
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
                            modifier           = Modifier.fillMaxSize()
                        )

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(OverlayDim)
                            )
                            Icon(
                                imageVector        = Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint               = Color.White,
                                modifier           = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(18.dp)
                            )
                        }

                        // ── Size badge (only when sorting by size) ──
                        if (showSizeInHeader) {
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
                                    .background(
                                        color  = Color(0x99000000),
                                        shape  = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
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