package com.wavebalance.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiChannel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ActiveConnectionInfo
import com.wavebalance.app.model.ChannelWidth
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.WifiStandard
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.adaptive.LocalWindowLayout
import com.wavebalance.app.ui.adaptive.TwoColumnPage
import com.wavebalance.app.ui.components.RfQualityGauge
import com.wavebalance.app.ui.components.RoamingMonitorCard
import com.wavebalance.app.ui.components.SpectrumMiniWaterfall
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.DarkSurfaceContainerLow
import com.wavebalance.app.ui.theme.NeonCyan
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber
import com.wavebalance.app.ui.theme.SignalWeak
import com.wavebalance.app.ui.theme.SignalFair
import com.wavebalance.app.ui.theme.SignalGood
import com.wavebalance.app.ui.theme.SignalExcellent

@Composable
fun DashboardScreen(
    viewModel: ScanViewModel,
    onNavigateToRadar: () -> Unit,
    onNavigateToOptimizer: () -> Unit,
    onNavigateToDetails: () -> Unit = {},
    onNavigateToSurvey: () -> Unit = {},
    onNavigateToSpeedDiagnostic: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeConn by viewModel.activeConnection.collectAsState()
    val allAps by viewModel.allAccessPoints.collectAsState()
    val totalCount by viewModel.totalApCount.collectAsState()
    val stickyAlert by viewModel.stickyClientAlert.collectAsState()
    val roamingHistory by viewModel.roamingHistory.collectAsState()
    val isMockMode by viewModel.isMockMode.collectAsState()
    val context = LocalContext.current

    val ownNetworkBssids by viewModel.ownNetworkBssids.collectAsState()
    val collisionCount by viewModel.collisionCount.collectAsState()
    // Radios of the user's own router or mesh (all bands and SSIDs), and everyone else's
    val homeCount = ownNetworkBssids.size
    val neighborCount = (totalCount - homeCount).coerceAtLeast(0)

    // RF Quality Score calculation (based on SNR, link speed, and co-channel interference)
    val rfQualityScore = rememberRfScore(activeConn, collisionCount)

    // Match connected AP from scan results to inherit exact channel width & telemetry
    val connectedAp = remember(activeConn, allAps) {
        allAps.firstOrNull { it.isConnected }
            ?: allAps.firstOrNull { it.bssid.equals(activeConn?.bssid, ignoreCase = true) }
            ?: allAps.firstOrNull { it.ssid.equals(activeConn?.cleanSsid, ignoreCase = true) && it.frequencyMhz == activeConn?.frequencyMhz }
            ?: allAps.firstOrNull { it.ssid.equals(activeConn?.cleanSsid, ignoreCase = true) }
    }

    val heroCard: @Composable () -> Unit = {
        HeroConnectionCard(
            activeConn = activeConn,
            connectedAp = connectedAp,
            onQuickScan = { viewModel.triggerScan() },
            onViewDetails = onNavigateToDetails
        )
    }
    val performanceSection: @Composable () -> Unit = {
        PerformanceGaugeSection(
            score = rfQualityScore,
            activeConn = activeConn,
            onNavigateToSpeedDiagnostic = onNavigateToSpeedDiagnostic
        )
    }
    val airspaceSummary: @Composable () -> Unit = {
        SurroundingAirspaceTrio(
            homeCount = homeCount,
            neighborCount = neighborCount,
            collisionCount = collisionCount,
            onNavigateToRadar = onNavigateToRadar
        )
    }
    val spectrumPreview: @Composable () -> Unit = {
        SpectrumMiniWaterfall(
            accessPoints = allAps,
            activeBand = activeConn?.band ?: FrequencyBand.BAND_5_GHZ
        )
    }
    val roamingMonitor: @Composable () -> Unit = {
        RoamingMonitorCard(
            activeConnection = activeConn,
            stickyAlert = stickyAlert,
            roamingHistory = roamingHistory,
            onSimulateRoam = { viewModel.simulateRoamToCandidate() },
            onSimulateWalk = { viewModel.simulateWalkDegradation() },
            showSimulationControls = isMockMode
        )
    }
    val actionCenter: @Composable () -> Unit = {
        ActionCenterSection(
            collisionCount = collisionCount,
            onNavigateToRadar = {
                viewModel.triggerScan()
                onNavigateToRadar()
            },
            onNavigateToOptimizer = onNavigateToOptimizer,
            onNavigateToSurvey = onNavigateToSurvey,
            onNavigateToSpeedDiagnostic = onNavigateToSpeedDiagnostic,
            onExportReport = { viewModel.shareAuditReport(context) }
        )
    }
    val diagnosticTip: @Composable () -> Unit = {
        DiagnosticTipCard(
            activeConn = activeConn,
            collisionCount = collisionCount
        )
    }

    if (LocalWindowLayout.current.isExpanded) {
        // Desktop: the connection and its spectrum on the left, the airspace and next steps on the right.
        // The status pill is left out because the navigation panel already shows the connection.
        TwoColumnPage(
            modifier = modifier,
            primary = {
                heroCard()
                airspaceSummary()
                performanceSection()
                spectrumPreview()
            },
            secondary = {
                diagnosticTip()
                roamingMonitor()
                actionCenter()
            }
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Network Status Header Pill
        item { LiveStatusPill(activeConn = activeConn) }

        // 2. Hero Card: Connected Network Telemetry
        item { heroCard() }

        // 3. Link & RF Performance Gauge Section
        item { performanceSection() }

        // 4. Surrounding Airspace Summary Pills (3 columns)
        item { airspaceSummary() }

        // 5. Spectrum Congestion Waterfall Preview
        item { spectrumPreview() }

        // 6. Mesh Roaming & Sticky Client Walk-Test Monitor
        item { roamingMonitor() }

        // 7. High-Affordance Action Center
        item { actionCenter() }

        // 8. Contextual Diagnostic Tip
        item { diagnosticTip() }
    }
}

@Composable
fun LiveStatusPill(activeConn: ActiveConnectionInfo?) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (activeConn != null) SecondaryContainerEmerald.copy(alpha = alpha)
                            else TertiaryContainerAmber.copy(alpha = alpha)
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (activeConn != null) "ACTIVE UPLINK" else "AIRSPACE SCANNING",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = if (activeConn != null) SecondaryContainerEmerald else TertiaryContainerAmber
                        )
                        Text(
                            text = " • ",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Text(
                            text = if (activeConn != null) "Connected" else "Standby",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = if (activeConn != null) "${activeConn.band.label} • ${activeConn.cleanSsid}" else "All 3 Spectrum Bands",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SecondaryContainerEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Primary AP",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun HeroConnectionCard(
    activeConn: ActiveConnectionInfo?,
    connectedAp: AccessPoint? = null,
    onQuickScan: () -> Unit,
    onViewDetails: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = activeConn != null, onClick = onViewDetails),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryContainerBlue.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = "Active Router",
                            tint = PrimaryContainerBlue,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeConn?.cleanSsid ?: "Discovered Networks",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            if (connectedAp != null && connectedAp.securityType != "Open") Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Secure",
                                tint = PrimaryContainerBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = activeConn?.let { "BSSID: ${it.bssid}" } ?: "Not connected",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = when {
                            activeConn == null -> "Scanning"
                            connectedAp != null -> connectedAp.securityType
                            else -> "Security unknown"
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = PrimaryContainerBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4-Cell Telemetry Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryGridCell(
                    label = "RF CHANNEL",
                    icon = Icons.Default.WifiChannel,
                    value = activeConn?.let { "${it.frequencyMhz} MHz" } ?: "—",
                    subtext = activeConn?.let { "Ch ${it.channel}" } ?: "Not connected",
                    modifier = Modifier.weight(1f)
                )
                TelemetryGridCell(
                    label = "RSSI SIGNAL",
                    icon = Icons.Default.Wifi,
                    value = activeConn?.let { "${it.rssi} dBm" } ?: "—",
                    subtext = activeConn?.let { signalQualityLabel(it.rssi) } ?: "Not connected",
                    valueColor = activeConn?.let { signalQualityColor(it.rssi) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val streams = connectedAp?.advertised?.maxSpatialStreams
                TelemetryGridCell(
                    label = "SPATIAL STREAMS",
                    icon = Icons.Default.Sensors,
                    value = streams?.let { "Up to $it" } ?: "—",
                    subtext = when {
                        streams != null -> "Advertised by the AP"
                        connectedAp == null -> "AP not in last scan"
                        else -> "Not advertised"
                    },
                    modifier = Modifier.weight(1f)
                )
                val resolvedWidth = connectedAp?.channelWidth ?: activeConn?.channelWidth ?: ChannelWidth.UNKNOWN

                val resolvedGen = activeConn?.standard?.generation
                    ?: connectedAp?.standard?.generation
                    ?: "Wi-Fi"

                TelemetryGridCell(
                    label = "BANDWIDTH",
                    icon = Icons.Default.Tune,
                    value = resolvedWidth.label,
                    subtext = "$resolvedGen Channel",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Signal labels shared with the signal colours in the theme (SignalExcellent etc.).
 */
fun signalQualityLabel(rssi: Int): String = when {
    rssi >= -50 -> "Excellent"
    rssi >= -65 -> "Good"
    rssi >= -75 -> "Fair"
    else -> "Weak"
}

fun signalQualityColor(rssi: Int): Color = when {
    rssi >= -50 -> SignalExcellent
    rssi >= -65 -> SignalGood
    rssi >= -75 -> SignalFair
    else -> SignalWeak
}

@Composable
fun TelemetryGridCell(
    label: String,
    icon: ImageVector,
    value: String,
    subtext: String,
    valueColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PrimaryContainerBlue,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = if (valueColor != Color.Unspecified) valueColor else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtext,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = PrimaryContainerBlue
            )
        }
    }
}

@Composable
fun PerformanceGaugeSection(
    score: Int,
    activeConn: ActiveConnectionInfo?,
    onNavigateToSpeedDiagnostic: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
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
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = PrimaryContainerBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Link & RF Performance",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PrimaryContainerBlue.copy(alpha = 0.15f),
                    modifier = Modifier.clickable { onNavigateToSpeedDiagnostic() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speed Test",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = PrimaryContainerBlue
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Gauge Ring & Primary Spec Row
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DarkSurfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RfQualityGauge(score = score)

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NEGOTIATED LINK RATE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SecondaryContainerEmerald,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${activeConn?.let { it.txLinkSpeedMbps.takeIf { tx -> tx > 0 } ?: it.linkSpeedMbps } ?: "—"} Mbps Tx",
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${activeConn?.rxLinkSpeedMbps?.takeIf { it > 0 } ?: "—"} Mbps Rx",
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Progress Bar
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            shape = CircleShape,
                            color = DarkSurfaceContainerHigh
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(score / 100f)
                                    .clip(CircleShape)
                                    .background(SecondaryContainerEmerald)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fine Grain Metrics Trio
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Android exposes no noise floor, so there is no SNR to show; these are all read from WifiInfo
                FineMetricCard(
                    label = "IP ADDRESS",
                    value = activeConn?.ipAddress?.ifBlank { null } ?: "—",
                    subtext = "IPv4 on this network",
                    subtextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                FineMetricCard(
                    label = "MAX LINK RATE",
                    value = activeConn?.maxSupportedTxLinkSpeedMbps?.takeIf { it > 0 }?.let { "$it Mbps" } ?: "—",
                    subtext = "Device and AP limit",
                    subtextColor = PrimaryContainerBlue,
                    modifier = Modifier.weight(1f)
                )
                FineMetricCard(
                    label = "STANDARD",
                    value = activeConn?.standard?.takeIf { it != WifiStandard.UNKNOWN }?.generation ?: "—",
                    subtext = activeConn?.standard?.takeIf { it != WifiStandard.UNKNOWN }?.label ?: "Unknown",
                    subtextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun FineMetricCard(
    label: String,
    value: String,
    subtext: String,
    subtextColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = DarkSurfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtext,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = subtextColor
            )
        }
    }
}

@Composable
fun SurroundingAirspaceTrio(
    homeCount: Int,
    neighborCount: Int,
    collisionCount: Int,
    onNavigateToRadar: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SURROUNDING AIRSPACE",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Text(
                text = "3 Bands Analyzed",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = PrimaryContainerBlue
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AirspaceStatCard(
                icon = Icons.Default.Home,
                label = "Your network",
                value = "$homeCount ${if (homeCount == 1) "radio" else "radios"}",
                iconTint = SecondaryContainerEmerald,
                onClick = onNavigateToRadar,
                modifier = Modifier.weight(1f)
            )
            AirspaceStatCard(
                icon = Icons.Default.Sensors,
                label = "Neighbors",
                value = "$neighborCount Found",
                iconTint = PrimaryContainerBlue,
                onClick = onNavigateToRadar,
                modifier = Modifier.weight(1f)
            )
            AirspaceStatCard(
                icon = Icons.Default.Warning,
                label = "Collisions",
                value = "$collisionCount Detected",
                iconTint = TertiaryContainerAmber,
                onClick = onNavigateToRadar,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun AirspaceStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceContainerHigh,
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ActionCenterSection(
    collisionCount: Int,
    onNavigateToRadar: () -> Unit,
    onNavigateToOptimizer: () -> Unit,
    onNavigateToSurvey: () -> Unit = {},
    onNavigateToSpeedDiagnostic: () -> Unit = {},
    onExportReport: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Primary Action: Start Area Scan
        Button(
            onClick = onNavigateToRadar,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryContainerBlue,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Start Radar Area Scan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }

        // Action: Speed & Ping Jitter Latency Diagnostic Engine
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToSpeedDiagnostic() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = SecondaryContainerEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Speed & Ping Jitter Diagnostic",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Bufferbloat, SQM grade, latency jitter & QoS assessment",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SecondaryContainerEmerald.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Test",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = SecondaryContainerEmerald,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Action: Site Survey & Coverage Heatmap
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToSurvey() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Site Survey & Coverage Heatmap",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "2D Floorplan mapping, dead zones & mesh recommendations",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = NeonCyan.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Survey",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = NeonCyan,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Tertiary Action: View Optimization Report
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToOptimizer() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = PrimaryContainerBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "View Optimization Report",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TertiaryContainerAmber.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(TertiaryContainerAmber)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$collisionCount collisions",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = TertiaryContainerAmber
                        )
                    }
                }
            }
        }

        // Quaternary Action: Export RF Diagnostic Audit Report (Sharesheet / Markdown)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExportReport() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = SecondaryContainerEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Export RF Audit Report",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Formatted Markdown summary via Android Sharesheet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SecondaryContainerEmerald.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Export",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = SecondaryContainerEmerald,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticTipCard(
    activeConn: ActiveConnectionInfo?,
    collisionCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = PrimaryContainerBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Channel Overlap Advisory",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        activeConn == null -> "Not connected to Wi-Fi. Connect to a network to see channel advice."
                        collisionCount > 0 -> "Detected $collisionCount neighboring networks sharing or overlapping Channel ${activeConn.channel}. Opening the Optimizer will calculate clear channel allocations."
                        else -> "Your current uplink on Channel ${activeConn.channel} (${activeConn.band.label}) is operating with minimal adjacent channel interference."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun rememberRfScore(conn: ActiveConnectionInfo?, collisions: Int): Int {
    if (conn == null) return 70
    var base = 100
    // Signal deduction: -50 dBm is ideal, each -5 dBm below deducts 4 points
    val rssiPenalty = (( -50 - conn.rssi ).coerceAtLeast(0) / 5) * 4
    base -= rssiPenalty
    // Collision deduction: each collision deducts 6 points
    base -= (collisions * 6)
    return base.coerceIn(20, 100)
}
