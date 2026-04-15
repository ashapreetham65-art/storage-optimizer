package com.example.storageoptimizer.ui.theme.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.storageoptimizer.data.ImageItem

@Composable
fun GroupsContent(
    groups:            List<List<ImageItem>>,
    selectedIds:       Set<Long>,
    isScanning:        Boolean,
    isDeleting:        Boolean,
    modifier:          Modifier = Modifier,
    onSelectionChange: (Set<Long>) -> Unit,
    onViewGroup:       (List<ImageItem>) -> Unit,
    onSelectGroup:     (Set<Long>) -> Unit,
    onUnselectGroup:   (Set<Long>) -> Unit,
    onDelete:          () -> Unit
) {
    if (groups.isEmpty()) {
        Text(
            text     = if (isScanning) "" else "No similar groups found",
            style    = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp)
        )
        return
    }

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
                    // Extracted to a real @Composable so weight(1f) has RowScope
                    GrpImageRow(
                        rowImages         = rowImages,
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
private fun GrpImageRow(
    rowImages:         List<ImageItem>,
    selectedIds:       Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rowImages.forEach { image ->
            val isSelected = selectedIds.contains(image.id)
            Box(
                modifier = Modifier
                    .weight(1f)               // works — inside RowScope now
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
                // No Keep tag — user decides manually in Groups tab
            }
        }
        repeat(3 - rowImages.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}