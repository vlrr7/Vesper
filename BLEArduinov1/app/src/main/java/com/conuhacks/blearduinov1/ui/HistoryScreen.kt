package com.conuhacks.blearduinov1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.conuhacks.blearduinov1.data.VesperEvent
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    events: List<VesperEvent>,
    onBackClick: () -> Unit,
    onExportClick: () -> Unit,
    onClearClick: () -> Unit,
) {
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val filtered = if (selectedFilter == null) events
    else events.filter { it.eventType == selectedFilter }

    val fallCount = events.count { it.eventType == "FALL" }
    val nearFallCount = events.count { it.eventType == "NEAR_FALL" }
    val activityChangeCount = events.count { it.eventType == "ACTIVITY_CHANGE" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "HISTORY",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onExportClick) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = "Export CSV",
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = onClearClick) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "Clear all",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Summary card
        SummaryCard(fallCount, nearFallCount, activityChangeCount)

        // Filter chips
        FilterChipRow(selectedFilter) { selectedFilter = it }

        // Event list
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No events recorded",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered) { event ->
                    EventRow(event)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(falls: Int, nearFalls: Int, activityChanges: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem("Falls", falls, MaterialTheme.colorScheme.primary)
            StatItem("Near-falls", nearFalls, MaterialTheme.colorScheme.tertiary)
            StatItem("Activity", activityChanges, MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun StatItem(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FilterChipRow(selected: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val filters = listOf("All" to null, "Falls" to "FALL", "Near" to "NEAR_FALL", "Activity" to "ACTIVITY_CHANGE")
        filters.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun EventRow(event: VesperEvent) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    val icon = when (event.eventType) {
        "FALL" -> Icons.Default.Warning
        "NEAR_FALL" -> Icons.Default.TrendingDown
        else -> Icons.Default.SwapVert
    }
    val iconColor = when (event.eventType) {
        "FALL" -> MaterialTheme.colorScheme.primary
        "NEAR_FALL" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconColor)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.eventType.replace("_", " "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "${event.activity} — ${event.details}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Text(
            text = dateFormat.format(Date(event.timestamp)),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
