package com.wavebalance.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ApCapabilities
import com.wavebalance.app.model.ApMetricsCalculator
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.InterferenceReport
import com.wavebalance.app.model.InterferenceSeverity
import com.wavebalance.app.model.WifiStandard
import com.wavebalance.app.model.WifiVendorLookup
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.components.AccessPointItemCard
import com.wavebalance.app.ui.components.RssiSparklineChart
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber

@Composable
fun ApDetailScreen(
    viewModel: ScanViewModel,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val allAps by viewModel.allAccessPoints.collectAsState()
    val activeConn by viewModel.activeConnection.collectAsState()
    val selectedApState by viewModel.selectedAp.collectAsState()
    val rssiHistoryMap by viewModel.rssiHistory.collectAsState()

    // Determine target AP: explicitly selected, or active connection, or strongest AP in airspace
    val targetAp = remember(selectedApState, allAps, activeConn) {
        val ap = selectedApState
            ?: allAps.find { it.isConnected }
            ?: allAps.firstOrNull()
        // A scan's RSSI for the connected AP lags the live connection reading, which the
        // signal graph uses, so show the live value to keep the header and graph in agreement
        val conn = activeConn
        if (ap != null && conn != null && ap.bssid.equals(conn.bssid, ignoreCase = true)) ap.copy(rssi = conn.rssi) else ap
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideLayout = maxWidth >= 650.dp

        if (isWideLayout) {
            // Adaptive Two-Pane Layout for Foldables / Tablets / Desktop Emulator
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Pane: AP List & Selector
                // Wide enough for an SSID, the Active badge and the signal chip side by side
                Card(
                    modifier = Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = null,
                                tint = PrimaryContainerBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Discovered APs (${allAps.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(allAps, key = { it.bssid }) { ap ->
                                val isSelected = ap.bssid == targetAp?.bssid
                                AccessPointItemCard(
                                    ap = ap,
                                    onToggleHomeTag = { viewModel.toggleHomeTag(ap.bssid) },
                                    onClick = { viewModel.selectAccessPoint(ap) },
                                    modifier = Modifier.then(
                                        if (isSelected) Modifier.background(
                                            PrimaryContainerBlue.copy(alpha = 0.12f),
                                            RoundedCornerShape(16.dp)
                                        ) else Modifier
                                    )
                                )
                            }
                        }
                    }
                }

                // Right Pane: AP Deep Dive Detail
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (targetAp != null) {
                        ApDetailContent(
                            ap = targetAp,
                            allAps = allAps,
                            samples = rssiHistoryMap[targetAp.bssid] ?: emptyList(),
                            onToggleHomeTag = { viewModel.toggleHomeTag(targetAp.bssid) },
                            onSelectOtherAp = { viewModel.selectAccessPoint(it) },
                            onNavigateBack = onNavigateBack,
                            showBackArrow = false
                        )
                    } else {
                        EmptyApDetailPlaceholder()
                    }
                }
            }
        } else {
            // Compact Layout for Phones (e.g. Pixel 10 Pro XL portrait)
            if (targetAp != null) {
                ApDetailContent(
                    ap = targetAp,
                    allAps = allAps,
                    samples = rssiHistoryMap[targetAp.bssid] ?: emptyList(),
                    onToggleHomeTag = { viewModel.toggleHomeTag(targetAp.bssid) },
                    onSelectOtherAp = { viewModel.selectAccessPoint(it) },
                    onNavigateBack = onNavigateBack,
                    showBackArrow = true
                )
            } else {
                EmptyApDetailPlaceholder(onGoToRadar = onNavigateBack)
            }
        }
    }
}

@Composable
fun ApDetailContent(
    ap: AccessPoint,
    allAps: List<AccessPoint>,
    samples: List<com.wavebalance.app.model.RssiSample>,
    onToggleHomeTag: () -> Unit,
    onSelectOtherAp: (AccessPoint) -> Unit,
    onNavigateBack: () -> Unit,
    showBackArrow: Boolean,
    modifier: Modifier = Modifier
) {
    val interferenceReport = remember(ap, allAps) {
        ApMetricsCalculator.analyzeInterference(ap, allAps)
    }

    val vendorName = remember(ap.bssid) {
        WifiVendorLookup.getVendor(ap.bssid)
    }

    val envelope = remember(ap.centerFrequencyMhz, ap.channelWidth) {
        ApMetricsCalculator.getFrequencyEnvelope(ap.centerFrequencyMhz, ap.channelWidth)
    }

    val advertisedStreams = ap.advertised?.maxSpatialStreams
    val maxPhySpeed = remember(ap.standard, ap.channelWidth, advertisedStreams) {
        ApMetricsCalculator.calculateTheoreticalMaxPhy(ap.standard, ap.channelWidth, advertisedStreams ?: 2)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Navigation & AP Quick Selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBackArrow) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Airspace",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ap.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Access Point Deep Dive & Telemetry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onToggleHomeTag) {
                        Icon(
                            imageVector = if (ap.isUserTaggedHome) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Tag Home Network",
                            tint = if (ap.isUserTaggedHome) TertiaryContainerAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Discovered AP Quick Carousel Selector
                if (allAps.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(allAps, key = { "selector_${it.bssid}" }) { other ->
                            val isSelected = other.bssid == ap.bssid
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectOtherAp(other) },
                                label = {
                                    Text(
                                        text = "${other.displayName} (${other.rssi} dBm)",
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrimaryContainerBlue.copy(alpha = 0.25f),
                                    selectedLabelColor = PrimaryContainerBlue
                                )
                            )
                        }
                    }
                }
            }
        }

        // 1. Hero Identity & Signal Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
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
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (ap.isConnected) SecondaryContainerEmerald.copy(alpha = 0.18f)
                                        else PrimaryContainerBlue.copy(alpha = 0.18f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (ap.isConnected) Icons.Default.CheckCircle else Icons.Default.Router,
                                    contentDescription = null,
                                    tint = if (ap.isConnected) SecondaryContainerEmerald else PrimaryContainerBlue,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = vendorName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = ap.bssid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Signal Badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                ap.rssi >= -60 -> SecondaryContainerEmerald.copy(alpha = 0.15f)
                                ap.rssi >= -75 -> PrimaryContainerBlue.copy(alpha = 0.15f)
                                else -> TertiaryContainerAmber.copy(alpha = 0.15f)
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "${ap.rssi} dBm",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = when {
                                        ap.rssi >= -60 -> SecondaryContainerEmerald
                                        ap.rssi >= -75 -> PrimaryContainerBlue
                                        else -> TertiaryContainerAmber
                                    }
                                )
                                Text(
                                    text = "${ap.signalPercent}% Quality",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Signal Quality Progress Bar
                    LinearProgressIndicator(
                        progress = { ap.signalPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when {
                            ap.rssi >= -60 -> SecondaryContainerEmerald
                            ap.rssi >= -75 -> PrimaryContainerBlue
                            else -> TertiaryContainerAmber
                        },
                        trackColor = DarkSurfaceContainerHigh
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Status Chips Row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ap.isConnected) {
                            Surface(
                                shape = CircleShape,
                                color = SecondaryContainerEmerald.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "ACTIVE CONNECTION",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = SecondaryContainerEmerald,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (ap.isUserTaggedHome) {
                            Surface(
                                shape = CircleShape,
                                color = TertiaryContainerAmber.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "HOME AP",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = TertiaryContainerAmber,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Rolling 60-Second RSSI Sparkline
        item {
            RssiSparklineChart(
                samples = samples,
                currentRssi = ap.rssi,
                subtitle = if (ap.isConnected) "Live connection signal, sampled every 3 s"
                else "One sample per Wi-Fi scan (Android allows a few per minute)"
            )
        }

        // 3. Collision & Interference Hazard Banner
        item {
            CollisionAlertCard(report = interferenceReport, targetAp = ap)
        }

        // 4. Radio & Spectrum Specifications Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = null,
                            tint = PrimaryContainerBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Radio & Spectrum Parameters",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    DetailMetricRow(
                        label = "Wi-Fi Generation",
                        value = "${ap.standard.label} (${ap.standard.generation})"
                    )
                    DetailMetricRow(
                        label = "Frequency Band",
                        value = "${ap.band.label} (${ap.frequencyMhz} MHz)"
                    )
                    DetailMetricRow(
                        label = "Primary Channel",
                        value = "Channel ${ap.channel}"
                    )
                    DetailMetricRow(
                        label = "Channel Bandwidth",
                        value = "${ap.channelWidth.mhz} MHz (${ap.channelWidth.label})"
                    )
                    DetailMetricRow(
                        label = "Spectrum Envelope",
                        value = "${envelope.startMhz} – ${envelope.endMhz} MHz (${envelope.channelSpan})"
                    )
                    DetailMetricRow(
                        label = "Center Frequency (fc0)",
                        value = "${ap.centerFrequencyMhz} MHz"
                    )
                    DetailMetricRow(
                        label = "Spatial Streams (MIMO)",
                        value = advertisedValue(ap) { it.maxSpatialStreams?.let { n -> "Up to $n (advertised)" } }
                    )
                    DetailMetricRow(
                        label = "Theoretical Max PHY Speed",
                        value = if (advertisedStreams != null) "$maxPhySpeed Mbps" else "$maxPhySpeed Mbps (assumes 2 streams)",
                        valueColor = PrimaryContainerBlue
                    )
                }
            }
        }

        // 5. Security & Protocol Capabilities Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = SecondaryContainerEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Security & Protocol Capabilities",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    DetailMetricRow(
                        label = "Security Protocol",
                        value = ap.securityType,
                        valueColor = if (ap.securityType.contains("WPA3")) SecondaryContainerEmerald else MaterialTheme.colorScheme.onSurface
                    )
                    DetailMetricRow(
                        label = "Protected Mgmt Frames (PMF)",
                        value = advertisedValue(ap) { it.pmf?.label }
                    )
                    DetailMetricRow(
                        label = "Fast Roaming (802.11k/v/r)",
                        value = advertisedValue(ap) { caps ->
                            listOfNotNull(
                                "k".takeIf { caps.radioMeasurement },
                                "v".takeIf { caps.bssTransition },
                                "r".takeIf { caps.fastTransition }
                            ).joinToString(" / ") { "802.11$it" }.ifEmpty { "None advertised" }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Advertised Capabilities Flags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val flags = remember(ap.capabilities) {
                            ap.capabilities.replace("[", "").split("]").filter { it.isNotBlank() }
                                .ifEmpty { listOf("None advertised") }
                        }
                        flags.forEach { flag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceContainerHigh
                            ) {
                                Text(
                                    text = flag.trim(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CollisionAlertCard(
    report: InterferenceReport,
    targetAp: AccessPoint,
    modifier: Modifier = Modifier
) {
    val containerBg = when (report.severity) {
        InterferenceSeverity.CLEAN -> SecondaryContainerEmerald.copy(alpha = 0.12f)
        InterferenceSeverity.LOW -> PrimaryContainerBlue.copy(alpha = 0.12f)
        InterferenceSeverity.MODERATE -> TertiaryContainerAmber.copy(alpha = 0.12f)
        InterferenceSeverity.HIGH -> Color(0xFFF97316).copy(alpha = 0.15f)
        InterferenceSeverity.SEVERE -> Color(0xFFEF4444).copy(alpha = 0.15f)
    }

    val badgeColor = Color(report.severity.colorHex)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg)
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
                        imageVector = if (report.severity == InterferenceSeverity.CLEAN) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Collision & Contention Radar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = report.severity.label.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = report.summaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )

            // Conflicting APs breakdown
            if (report.coChannelAps.isNotEmpty() || report.adjacentAps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.coChannelAps.forEach { ap ->
                        InterferingApRow(ap = ap, type = "Co-Channel (Ch ${ap.channel})", color = Color(0xFFEF4444))
                    }
                    report.adjacentAps.forEach { ap ->
                        InterferingApRow(ap = ap, type = "Adjacent Overlap (Ch ${ap.channel})", color = TertiaryContainerAmber)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceContainer.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = PrimaryContainerBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = report.recommendation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InterferingApRow(
    ap: AccessPoint,
    type: String,
    color: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceContainerHigh.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = ap.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = type,
                    fontSize = 10.sp,
                    color = color
                )
            }

            Text(
                text = "${ap.rssi} dBm",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DetailMetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
fun EmptyApDetailPlaceholder(
    onGoToRadar: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.NetworkCheck,
                    contentDescription = null,
                    tint = PrimaryContainerBlue,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "No Access Point Selected",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select any detected network from the Spectrum Radar or Dashboard to inspect real-time channel width, signal jitter, and collision metrics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onGoToRadar,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryContainerBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(text = "Open Spectrum Radar", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Formats a value from the AP's advertised information elements, explaining why it
 * is missing: Android only exposes them from Android 11.
 */
private fun advertisedValue(ap: AccessPoint, value: (ApCapabilities) -> String?): String {
    val caps = ap.advertised ?: return "Needs Android 11+"
    return value(caps) ?: "Not advertised"
}
