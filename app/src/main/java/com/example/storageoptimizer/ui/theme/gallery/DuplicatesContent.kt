package com.example.storageoptimizer.ui.theme.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.ImageItem

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
    if (groups.isEmpty()) {
        Text(
            text     = if (isScanning) "" else "No exact duplicates found",
            style    = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp)
        )
        return
    }

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
                    // Extracted to a real @Composable so weight(1f) has RowScope
                    DupImageRow(
                        rowImages         = rowImages,
                        rowIndex          = rowIndex,
                        selectedIds       = selectedIds,
                        onSelectionChange = onSelectionChange
                    )
                }
            }
        }
    }

    if (selectedIds.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick  = onDelete,
            enabled  = !isDeleting,
            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isDeleting) "Deleting..." else "Delete Selected (${selectedIds.size})")
        }
    }
}

// Separate @Composable → has its own RowScope → weight(1f) compiles fine
@Composable
private fun DupImageRow(
    rowImages:         List<ImageItem>,
    rowIndex:          Int,
    selectedIds:       Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rowImages.forEachIndexed { posInRow, image ->
            val isSelected = selectedIds.contains(image.id)
            val isKeep     = rowIndex == 0 && posInRow == 0

            Box(
                modifier = Modifier
                    .weight(1f)               // works — we are inside RowScope now
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
                    modifier           = Modifier.fillMaxSize().padding(2.dp)
                )
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                    Icon(
                        imageVector        = Icons.Filled.CheckCircle,
                        contentDescription = "Selected for deletion",
                        tint               = Color.White,
                        modifier           = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
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
        // Fill empty slots so partial rows still span full width
        repeat(3 - rowImages.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}