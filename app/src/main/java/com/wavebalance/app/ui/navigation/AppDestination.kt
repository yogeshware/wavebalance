package com.wavebalance.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Groups used to section the desktop navigation panel.
 */
enum class DestinationGroup(val label: String) {
    OVERVIEW("Overview"),
    ANALYZE("Analyze"),
    TOOLS("Tools")
}

enum class AppDestination(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val contentDescription: String,
    val group: DestinationGroup,
    val shortcut: String,
    // The phone bottom bar has room for five items; the rest are reached from the top bar
    val inBottomBar: Boolean = true
) {
    DASHBOARD(
        label = "Dashboard",
        subtitle = "Wi-Fi Dashboard & RF Health",
        icon = Icons.Default.GridView,
        contentDescription = "WaveBalance Dashboard",
        group = DestinationGroup.OVERVIEW,
        shortcut = "D"
    ),
    NETWORKS(
        label = "Networks",
        subtitle = "Radar, Access Points & Details",
        icon = Icons.Default.WifiTethering,
        contentDescription = "Networks: radar, list and details",
        group = DestinationGroup.ANALYZE,
        shortcut = "N"
    ),
    OPTIMIZER(
        label = "Optimizer",
        subtitle = "Channel Interference Optimizer",
        icon = Icons.Default.AutoFixHigh,
        contentDescription = "Channel Optimizer",
        group = DestinationGroup.TOOLS,
        shortcut = "O"
    ),
    SURVEY(
        label = "Survey",
        subtitle = "Site Survey & RF Heatmap",
        icon = Icons.Default.Layers,
        contentDescription = "Site Survey & RF Heatmap",
        group = DestinationGroup.TOOLS,
        shortcut = "H"
    ),
    SPEED(
        label = "Speed Test",
        subtitle = "Speed, Ping & Bufferbloat",
        icon = Icons.Default.Speed,
        contentDescription = "Speed & Latency Diagnostic",
        group = DestinationGroup.TOOLS,
        shortcut = "T"
    )
}
