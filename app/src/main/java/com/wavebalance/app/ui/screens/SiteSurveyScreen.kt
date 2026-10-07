package com.wavebalance.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.SiteSurveyEngine
import com.wavebalance.app.model.SurveyPoint
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.components.HeatmapDisplayMode
import com.wavebalance.app.ui.components.RfHeatmapCanvas
import com.wavebalance.app.ui.theme.CardSurfaceSlate
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.ErrorRed
import com.wavebalance.app.ui.theme.NeonCyan
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SiteSurveyScreen(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeConn by viewModel.activeConnection.collectAsState()
    val surveyPoints by viewModel.surveyPoints.collectAsState()
    val surveyAnalytics by viewModel.surveyAnalytics.collectAsState()

    var cursorX by rememberSaveable { mutableFloatStateOf(0.50f) }
    var cursorY by rememberSaveable { mutableFloatStateOf(0.40f) }
    var displayMode by rememberSaveable { mutableStateOf(HeatmapDisplayMode.FULL_HEATMAP) }

    // Selected room tag
    val currentRoom = remember(cursorX, cursorY) {
        SiteSurveyEngine.findRoomForCoordinate(cursorX, cursorY)
    }

    // Estimated signal at cursor
    // No estimate until there is at least one measured pin to interpolate from
    val cursorEstimatedRssi = remember(cursorX, cursorY, surveyPoints) {
        if (surveyPoints.isEmpty()) null else SiteSurveyEngine.interpolateRssi(cursorX, cursorY, surveyPoints).toInt()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        // 1. Active Telemetry HUD & Quick Pin Header
        item {
            SurveyTelemetryHud(
                activeSsid = activeConn?.cleanSsid ?: "Offline / Unconnected",
                activeBssid = activeConn?.bssid ?: "00:00:00:00:00:00",
                activeRssi = activeConn?.rssi ?: -70,
                activeBand = activeConn?.band ?: FrequencyBand.BAND_5_GHZ,
                activeChannel = activeConn?.channel ?: 36,
                currentRoom = currentRoom,
                cursorX = cursorX,
                cursorY = cursorY,
                estimatedRssiAtCursor = cursorEstimatedRssi,
                // A pin records the live connection; without one there is nothing real to record
                onPinMeasurement = activeConn?.let { conn ->
                    {
                        viewModel.addSurveyPoint(
                            SurveyPoint(
                                x = cursorX,
                                y = cursorY,
                                roomName = currentRoom,
                                bssid = conn.bssid,
                                ssid = conn.cleanSsid,
                                rssi = conn.rssi,
                                frequencyMhz = conn.frequencyMhz,
                                channel = conn.channel,
                                band = conn.band
                            )
                        )
                    }
                }
            )
        }

        // 2. Mode Filter & Canvas Control Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = displayMode == HeatmapDisplayMode.FULL_HEATMAP,
                        onClick = { displayMode = HeatmapDisplayMode.FULL_HEATMAP },
                        label = { Text("Heatmap Overlay", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                            selectedLabelColor = NeonCyan
                        )
                    )
                    FilterChip(
                        selected = displayMode == HeatmapDisplayMode.DEAD_ZONES_ONLY,
                        onClick = { displayMode = HeatmapDisplayMode.DEAD_ZONES_ONLY },
                        label = { Text("Dead Zones Only", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ErrorRed.copy(alpha = 0.2f),
                            selectedLabelColor = ErrorRed
                        )
                    )
                    FilterChip(
                        selected = displayMode == HeatmapDisplayMode.BLUEPRINT_ONLY,
                        onClick = { displayMode = HeatmapDisplayMode.BLUEPRINT_ONLY },
                        label = { Text("Blueprint", fontSize = 11.sp) }
                    )
                }
            }
        }

        // 3. 2D Interactive RF Heatmap Canvas
        item {
            RfHeatmapCanvas(
                points = surveyPoints,
                analytics = surveyAnalytics,
                cursorX = cursorX,
                cursorY = cursorY,
                displayMode = displayMode,
                onCursorMoved = { nx, ny ->
                    cursorX = nx
                    cursorY = ny
                }
            )
        }

        // 4. Quick Actions (Simulate Walkthrough, Export, Clear)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val ssid = activeConn?.cleanSsid ?: "SuNsTeR"
                        val bssid = activeConn?.bssid ?: "1a:2b:3c:4d:5e:6f"
                        viewModel.populateSimulatedWalkthrough(ssid, bssid)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceSlate),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = "Simulate",
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Auto Walk", fontSize = 12.sp, color = NeonCyan)
                }

                Button(
                    onClick = {
                        exportSurveyReport(context, surveyPoints, surveyAnalytics, activeConn?.cleanSsid ?: "Wi-Fi")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceSlate),
                    shape = RoundedCornerShape(10.dp),
                    enabled = surveyPoints.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }

                OutlinedButton(
                    onClick = { viewModel.clearSurveyPoints() },
                    modifier = Modifier.weight(0.9f),
                    shape = RoundedCornerShape(10.dp),
                    enabled = surveyPoints.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear",
                        tint = ErrorRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 12.sp, color = ErrorRed)
                }
            }
        }

        // 5. Airspace Coverage & Remediation Card
        item {
            SurveyAnalyticsCard(
                analytics = surveyAnalytics,
                totalPoints = surveyPoints.size
            )
        }

        // 6. Section Header for Pinned Waypoints
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PINNED WAYPOINTS (${surveyPoints.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (surveyPoints.isNotEmpty()) {
                    Text(
                        text = "Sorted by recency",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 7. List of Pinned Waypoints
        if (surveyPoints.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Empty",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No Survey Waypoints Recorded",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap on any room in the floor plan above and tap 'Pin RF Measurement', or click 'Auto Walk' to load a demo walkthrough.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            itemsIndexed(surveyPoints.reversed(), key = { _, pt -> pt.id }) { index, pt ->
                SurveyPointItemCard(
                    point = pt,
                    index = surveyPoints.size - index,
                    onDelete = { viewModel.removeSurveyPoint(pt.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SurveyTelemetryHud(
    activeSsid: String,
    activeBssid: String,
    activeRssi: Int,
    activeBand: FrequencyBand,
    activeChannel: Int,
    currentRoom: String,
    cursorX: Float,
    cursorY: Float,
    estimatedRssiAtCursor: Int?,
    onPinMeasurement: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Live Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(SecondaryContainerEmerald)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activeSsid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = getRssiColor(activeRssi).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "$activeRssi dBm",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = getRssiColor(activeRssi),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Current Placement Coordinates & Selected Room
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceContainerHigh)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TARGET LOCATION",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentRoom,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonCyan
                    )
                    Text(
                        text = "Grid (${String.format(Locale.US, "%.2f", cursorX)}, ${String.format(Locale.US, "%.2f", cursorY)}) • " + (estimatedRssiAtCursor?.let { "Est: $it dBm" } ?: "No pins yet"),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { onPinMeasurement?.invoke() },
                    enabled = onPinMeasurement != null,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = "Pin Signal",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pin RF Here",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun SurveyAnalyticsCard(
    analytics: com.wavebalance.app.model.SurveyAnalytics,
    totalPoints: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = "Analytics",
                        tint = SecondaryContainerEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "COVERAGE ASSESSMENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryContainerEmerald
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (analytics.coveragePercent >= 75) SecondaryContainerEmerald.copy(alpha = 0.2f) else TertiaryContainerAmber.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "${analytics.coveragePercent}% Adequate",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (analytics.coveragePercent >= 75) SecondaryContainerEmerald else TertiaryContainerAmber,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4-cell metric grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricMiniCell(
                    label = "SAMPLES",
                    value = totalPoints.toString(),
                    color = NeonCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCell(
                    label = "AVG RSSI",
                    value = "${analytics.avgRssi} dBm",
                    color = getRssiColor(analytics.avgRssi.toFloat()),
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCell(
                    label = "OPTIMAL",
                    value = "${analytics.optimalZonesDetected}",
                    color = SecondaryContainerEmerald,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCell(
                    label = "DEAD ZONES",
                    value = "${analytics.deadZonesDetected}",
                    color = if (analytics.deadZonesDetected > 0) ErrorRed else Color(0xFF64748B),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Remediation Directive Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (analytics.deadZonesDetected > 0) ErrorRed.copy(alpha = 0.12f)
                        else SecondaryContainerEmerald.copy(alpha = 0.12f)
                    )
                    .border(
                        1.dp,
                        if (analytics.deadZonesDetected > 0) ErrorRed.copy(alpha = 0.4f)
                        else SecondaryContainerEmerald.copy(alpha = 0.4f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (analytics.deadZonesDetected > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = "Status",
                            tint = if (analytics.deadZonesDetected > 0) ErrorRed else SecondaryContainerEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (analytics.deadZonesDetected > 0) "DEAD ZONE REMEDIATION DIRECTIVE" else "AIRSPACE COVERAGE VERIFIED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (analytics.deadZonesDetected > 0) ErrorRed else SecondaryContainerEmerald
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = analytics.recommendationSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricMiniCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun SurveyPointItemCard(
    point: SurveyPoint,
    index: Int,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = getRssiColor(point.rssi.toFloat()).copy(alpha = 0.2f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "#$index",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = getRssiColor(point.rssi.toFloat())
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = point.roomName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Ch ${point.channel} • ${point.band.label} • ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(point.timestamp))}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = getRssiColor(point.rssi.toFloat()).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${point.rssi} dBm",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = getRssiColor(point.rssi.toFloat()),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Delete Pin",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun getRssiColor(rssi: Float): Color {
    return when {
        rssi >= -55f -> SecondaryContainerEmerald
        rssi >= -65f -> NeonCyan
        rssi >= -74f -> TertiaryContainerAmber
        else -> ErrorRed
    }
}

private fun getRssiColor(rssi: Int): Color = getRssiColor(rssi.toFloat())

private fun exportSurveyReport(
    context: Context,
    points: List<SurveyPoint>,
    analytics: com.wavebalance.app.model.SurveyAnalytics,
    activeSsid: String
) {
    val report = SiteSurveyEngine.generateSurveyReportMarkdown(points, analytics, activeSsid)
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, report)
        putExtra(Intent.EXTRA_SUBJECT, "WaveBalance RF Site Survey Report - $activeSsid")
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, "Share RF Site Survey Report")
    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(shareIntent)
}
