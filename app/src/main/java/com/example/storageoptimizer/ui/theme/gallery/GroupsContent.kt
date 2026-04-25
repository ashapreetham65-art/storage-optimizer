package com.example.storageoptimizer.ui.theme.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.ImageItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

// ── Sort options ──────────────────────────────────────────────────────────────
enum class GroupSortOrder {
    DATE_NEWEST,
    DATE_OLDEST,
    SIMILAR,
    SIZE_MOST,
    SIZE_LEAST
}

// ── Size bucket helpers ───────────────────────────────────────────────────────
private data class SizeBucket(val label: String, val images: List<ImageItem>)

private fun bucketBySize(images: List<ImageItem>): List<SizeBucket> {
    data class Range(val label: String, val minBytes: Long, val maxBytes: Long)
    val ranges = listOf(
        Range("Below 1 MB",   0L,               1L * 1024 * 1024 - 1),
        Range("1 – 5 MB",     1L * 1024 * 1024, 5L * 1024 * 1024),
        Range("6 – 10 MB",    6L * 1024 * 1024, 10L * 1024 * 1024),
        Range("11 – 15 MB",  11L * 1024 * 1024, 15L * 1024 * 1024),
        Range("16 – 20 MB",  16L * 1024 * 1024, 20L * 1024 * 1024),
        Range("21 – 30 MB",  21L * 1024 * 1024, 30L * 1024 * 1024),
        Range("31 – 50 MB",  31L * 1024 * 1024, 50L * 1024 * 1024),
        Range("51 – 100 MB", 51L * 1024 * 1024, 100L * 1024 * 1024),
        Range("100+ MB",    101L * 1024 * 1024, Long.MAX_VALUE)
    )
    return ranges.mapNotNull { r ->
        val matching = images.filter { it.size in r.minBytes..r.maxBytes }
        if (matching.isNotEmpty()) SizeBucket(r.label, matching) else null
    }
}

// ── Date bucket helpers ───────────────────────────────────────────────────────
private data class DateBucket(
    val label:       String,
    val epochDayKey: Long,       // comparable Long: YYYYMMDD as Long for correct sort
    val images:      List<ImageItem>
)

private fun bucketByDate(images: List<ImageItem>): List<DateBucket> {
    val fmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

    // Each image gets its own Calendar so groupBy and map never share mutable state.
    fun calForSeconds(secs: Long): Calendar =
        Calendar.getInstance().also { it.timeInMillis = secs * 1_000L }

    fun dayKey(secs: Long): Long {
        val c = calForSeconds(secs)
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1   // 0-indexed → 1-indexed
        val d = c.get(Calendar.DAY_OF_MONTH)
        return y.toLong() * 10_000L + m.toLong() * 100L + d.toLong()
    }

    fun dayLabel(secs: Long): String {
        val c         = calForSeconds(secs)
        val today     = Calendar.getInstance()
        val yesterday = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay(c, today)     -> "Today"
            sameDay(c, yesterday) -> "Yesterday"
            else                  -> fmt.format(c.time)
        }
    }

    // If dateAdded is 0 (e.g. rows migrated from V9/V10 DB before dateAdded was added),
    // fall back to dateModified which has always been stored correctly.
    fun effectiveDate(img: ImageItem): Long =
        if (img.dateAdded > 0L) img.dateAdded else img.dateModified

    return images
        .groupBy { img -> dayKey(effectiveDate(img)) }
        .map { (key, dayImages) ->
            DateBucket(dayLabel(effectiveDate(dayImages.first())), key, dayImages)
        }
}

// ── Format bytes ──────────────────────────────────────────────────────────────
private fun fmtBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else      -> "${bytes / 1024} KB"
    }
}

// ── Composable entry point ────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupsContent(
    groups:            List<List<ImageItem>>,
    allImages:         List<ImageItem>,
    selectedIds:       Set<Long>,
    isScanning:        Boolean,
    isDeleting:        Boolean,
    modifier:          Modifier = Modifier,
    onSelectionChange: (Set<Long>) -> Unit,
    // Bug 2 fix: signature now carries the start index so viewer opens at tapped image
    onViewGroup:       (List<ImageItem>, Int) -> Unit,
    onSelectGroup:     (Set<Long>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit,
    onDelete:          () -> Unit
) {
    var sortOrder    by remember { mutableStateOf(GroupSortOrder.DATE_NEWEST) }
    var dropdownOpen by remember { mutableStateOf(false) }
    val listState    = rememberLazyListState()

    val sortLabel = when (sortOrder) {
        GroupSortOrder.DATE_NEWEST -> "Date (Newest)"
        GroupSortOrder.DATE_OLDEST -> "Date (Oldest)"
        GroupSortOrder.SIMILAR     -> "Similar"
        GroupSortOrder.SIZE_MOST   -> "Size (Most)"
        GroupSortOrder.SIZE_LEAST  -> "Size (Least)"
    }

    // Bug 1 fix: date buckets sorted by epochDayKey (a plain Long) so
    // newest-first and oldest-first are numerically unambiguous.
    // Bug 1 fix: images within each date bucket sorted newest-first too.
    val displayGroups: List<Pair<String, List<ImageItem>>> = remember(groups, allImages, sortOrder) {
        when (sortOrder) {
            GroupSortOrder.SIMILAR ->
                groups.map { g -> "similar" to g.sortedByDescending { it.size } }

            GroupSortOrder.DATE_NEWEST ->
                bucketByDate(allImages)
                    .sortedByDescending { it.epochDayKey }
                    .map { bucket ->
                        // Use dateModified as fallback when dateAdded is 0 (migrated rows)
                        bucket.label to bucket.images.sortedByDescending {
                            if (it.dateAdded > 0L) it.dateAdded else it.dateModified
                        }
                    }

            GroupSortOrder.DATE_OLDEST ->
                bucketByDate(allImages)
                    .sortedBy { it.epochDayKey }
                    .map { bucket ->
                        bucket.label to bucket.images.sortedBy {
                            if (it.dateAdded > 0L) it.dateAdded else it.dateModified
                        }
                    }

            GroupSortOrder.SIZE_MOST ->
                bucketBySize(allImages)
                    .sortedByDescending { b -> b.images.maxOfOrNull { it.size } ?: 0L }
                    .map { it.label to it.images.sortedByDescending { it.size } }

            GroupSortOrder.SIZE_LEAST ->
                bucketBySize(allImages)
                    .sortedBy { b -> b.images.maxOfOrNull { it.size } ?: 0L }
                    .map { it.label to it.images.sortedBy { it.size } }
        }
    }

    // Bug 1 fix: reset scroll whenever sort changes
    LaunchedEffect(sortOrder) {
        listState.scrollToItem(0)
    }

    val isEmpty = if (sortOrder == GroupSortOrder.SIMILAR) groups.isEmpty()
    else allImages.isEmpty()

    if (isEmpty) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text     = if (isScanning) "" else "No groups found",
                color    = SubText,
                fontSize = 15.sp
            )
        }
        return
    }

    Box(modifier = modifier) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ── Sort bar
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
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
                        GroupSortOrder.DATE_NEWEST to "Date (Newest)",
                        GroupSortOrder.DATE_OLDEST to "Date (Oldest)",
                        GroupSortOrder.SIMILAR     to "Similar",
                        GroupSortOrder.SIZE_MOST   to "Size (Most)",
                        GroupSortOrder.SIZE_LEAST  to "Size (Least)"
                    ).forEach { (order, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text       = label,
                                    color      = if (sortOrder == order) AccentBlue else Color.White,
                                    fontSize   = 14.sp,
                                    fontWeight = if (sortOrder == order) FontWeight.SemiBold
                                    else FontWeight.Normal
                                )
                            },
                            onClick = { sortOrder = order; dropdownOpen = false },
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

            // ── Group list
            LazyColumn(
                state          = listState,
                modifier       = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = 4.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                displayGroups.forEachIndexed { groupIndex, (headerLabel, groupImages) ->
                    if (groupIndex > 0) {
                        item(key = "grp_gap_$groupIndex") {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    groupCard(
                        groupIndex        = groupIndex,
                        groupImages       = groupImages,
                        headerLabel       = headerLabel,
                        sortOrder         = sortOrder,
                        selectedIds       = selectedIds,
                        onSelectionChange = onSelectionChange,
                        onViewGroup       = onViewGroup,
                        onSelectGroup     = onSelectGroup,
                        onUnselectGroup   = onUnselectGroup
                    )
                }
            }
        }

        // ── Animated delete button
        AnimatedVisibility(
            visible  = selectedIds.isNotEmpty(),
            enter    = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) +
                    fadeIn(animationSpec = tween(300)),
            exit     = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) +
                    fadeOut(animationSpec = tween(250)),
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
                    animation  = keyframes {
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
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Deleting...", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Delete  (${selectedIds.size})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Single group card ─────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.groupCard(
    groupIndex:        Int,
    groupImages:       List<ImageItem>,   // already sorted by caller
    headerLabel:       String,
    sortOrder:         GroupSortOrder,
    selectedIds:       Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onViewGroup:       (List<ImageItem>, Int) -> Unit,
    onSelectGroup:     (Set<Long>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit
) {
    val groupIds    = groupImages.map { it.id }.toSet()
    val rows        = groupImages.chunked(3)

    // ── Header
    item(key = "grp_header_$groupIndex") {
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
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // Label pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CountPillBg)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(AccentBlue.copy(alpha = 0.35f), AccentPurple.copy(alpha = 0.35f))
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    when (sortOrder) {
                        GroupSortOrder.SIMILAR -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${groupImages.size}", color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("images in group", color = HeaderText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        else -> Column {
                            Text(headerLabel, color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("${groupImages.size} images", color = HeaderText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Icon buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    val allSelected = groupIds.isNotEmpty() && groupIds.all { it in selectedIds }

                    Box(
                        modifier = Modifier
                            .size(36.dp).clip(CircleShape)
                            .background(if (allSelected) AccentBlue.copy(alpha = 0.25f) else IconBg)
                            .border(1.dp, if (allSelected) AccentBlue else Color.Transparent, CircleShape)
                            .clickable { if (allSelected) onUnselectGroup(groupIds) else onSelectGroup(groupIds) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = if (allSelected) "Deselect all" else "Select all",
                            tint               = if (allSelected) AccentBlue else SubText,
                            modifier           = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp).clip(CircleShape).background(IconBg)
                            // View button opens at index 0 — shows first image in group
                            .clickable { onViewGroup(groupImages, 0) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.RemoveRedEye, contentDescription = "View group", tint = AccentBlue, modifier = Modifier.size(18.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp).clip(CircleShape).background(IconBg)
                            .clickable { onUnselectGroup(groupIds) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Unselect group", tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    // ── Image rows
    rows.forEachIndexed { rowIndex, rowImages ->
        val isLastRow = rowIndex == rows.lastIndex

        item(key = "grp_${groupIndex}_row_$rowIndex") {
            val anyInGroupSelected = groupIds.any { it in selectedIds }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = CardBg,
                        shape = if (isLastRow) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        else RoundedCornerShape(0.dp)
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
                                canvas.drawPath(Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(0f, size.height - cornerRadius)
                                    quadraticBezierTo(0f, size.height, cornerRadius, size.height)
                                    lineTo(size.width - cornerRadius, size.height)
                                    quadraticBezierTo(size.width, size.height, size.width, size.height - cornerRadius)
                                    lineTo(size.width, 0f)
                                }, paint)
                            } else {
                                canvas.drawLine(Offset(0f, 0f), Offset(0f, size.height), paint)
                                canvas.drawLine(Offset(size.width, 0f), Offset(size.width, size.height), paint)
                            }
                        }
                    }
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = if (isLastRow) 10.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowImages.forEachIndexed { posInRow, image ->
                    val isSelected = selectedIds.contains(image.id)

                    // Bug 2 fix: flat index in the full groupImages list, not per-row
                    // This is the index the viewer needs to open at the correct image.
                    val flatIndex = rowIndex * 3 + posInRow

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(1.dp, Brush.linearGradient(listOf(TileBorder1, TileBorder2)), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(
                                onClick = {
                                    if (anyInGroupSelected || isSelected) {
                                        // Already in selection mode — tap toggles this image
                                        onSelectionChange(
                                            if (isSelected) selectedIds - image.id
                                            else            selectedIds + image.id
                                        )
                                    } else {
                                        // Not in selection mode — open viewer at the tapped image
                                        onViewGroup(groupImages, flatIndex)
                                    }
                                },
                                onLongClick = {
                                    onSelectionChange(selectedIds + image.id)
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
                            Box(modifier = Modifier.fillMaxSize().background(OverlayDim))
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint               = Color.White,
                                modifier           = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp)
                            )
                        }

                        if (sortOrder == GroupSortOrder.SIZE_MOST || sortOrder == GroupSortOrder.SIZE_LEAST) {
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

                repeat(3 - rowImages.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}