package com.wavebalance.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.ScanStatus
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.ui.components.AccessPointItemCard
import com.wavebalance.app.ui.components.ParabolicRadarGraph
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber

enum class ApSortOption(val label: String) {
    SIGNAL("Signal"),
    CHANNEL("Channel"),
    SSID("Name")
}

@Composable
fun WifiScanScreen(
    viewModel: ScanViewModel,
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    onRequestPermissions: () -> Unit,
    onNavigateToDetails: (AccessPoint) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rawAps by viewModel.filteredAccessPoints.collectAsState()
    val totalCount by viewModel.totalApCount.collectAsState()
    val activeConn by viewModel.activeConnection.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    val isMockMode by viewModel.isMockMode.collectAsState()
    val selectedBand by viewModel.selectedBandFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val hasPerms = viewModel.hasPermissions()

    DisposableEffect(Unit) {
        onDispose { onTextInputFocusChanged(false) }
    }

    var sortOption by rememberSaveable { mutableStateOf(ApSortOption.SIGNAL) }
    var selectedApForGraph by remember { mutableStateOf<AccessPoint?>(null) }
    var showOnlyHomeAps by rememberSaveable { mutableStateOf(false) }

    // Band for parabolic graph: if user has selected a specific band, use it; otherwise default to active band or 5 GHz
    val graphBand = selectedBand ?: (activeConn?.band ?: FrequencyBand.BAND_5_GHZ)

    // Apply sorting & home filter
    val displayedAps = remember(rawAps, sortOption, showOnlyHomeAps) {
        var list = if (showOnlyHomeAps) rawAps.filter { it.isUserTaggedHome } else rawAps
        when (sortOption) {
            ApSortOption.SIGNAL -> list.sortedByDescending { it.rssi }
            ApSortOption.CHANNEL -> list.sortedBy { it.channel }
            ApSortOption.SSID -> list.sortedBy { it.displayName.lowercase() }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Simulation / Mock Mode Bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Simulation Mode",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isMockMode) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = TertiaryContainerAmber.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "MOCK",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TertiaryContainerAmber,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (isMockMode) "Simulating multi-band APs & interference" else "Live hardware RF scans",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = isMockMode,
                        onCheckedChange = { viewModel.toggleMockMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = PrimaryContainerBlue
                        )
                    )
                }
            }
        }

        // 2. Permission Banner (if not granted and not in mock mode)
        if (!hasPerms && !isMockMode) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TertiaryContainerAmber.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = TertiaryContainerAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Permissions Required",
                                style = MaterialTheme.typography.titleMedium,
                                color = TertiaryContainerAmber
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Android requires Location & Nearby Wi-Fi permissions to scan BSSID radio beacons on your Pixel 10 Pro XL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRequestPermissions,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TertiaryContainerAmber,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(text = "Grant System Permissions", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // 3. Parabolic RF Channel Overlap Graph (Custom Bezier Canvas)
        item {
            ParabolicRadarGraph(
                accessPoints = rawAps,
                selectedBand = graphBand,
                selectedAp = selectedApForGraph,
                onSelectAp = { ap ->
                    selectedApForGraph = ap
                }
            )
        }

        // 4. Band Filter Chips (2.4 GHz, 5 GHz, 6 GHz)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedBand == null,
                            onClick = { viewModel.setBandFilter(null) },
                            label = { Text("All Bands ($totalCount)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryContainerBlue.copy(alpha = 0.2f),
                                selectedLabelColor = PrimaryContainerBlue
                            )
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedBand == FrequencyBand.BAND_2_4_GHZ,
                            onClick = { viewModel.setBandFilter(FrequencyBand.BAND_2_4_GHZ) },
                            label = { Text("2.4 GHz (Ch 1–11)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TertiaryContainerAmber.copy(alpha = 0.2f),
                                selectedLabelColor = TertiaryContainerAmber
                            )
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedBand == FrequencyBand.BAND_5_GHZ,
                            onClick = { viewModel.setBandFilter(FrequencyBand.BAND_5_GHZ) },
                            label = { Text("5 GHz (Ch 36–165)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryContainerBlue.copy(alpha = 0.2f),
                                selectedLabelColor = PrimaryContainerBlue
                            )
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedBand == FrequencyBand.BAND_6_GHZ,
                            onClick = { viewModel.setBandFilter(FrequencyBand.BAND_6_GHZ) },
                            label = { Text("6 GHz (Wi-Fi 6E/7)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFC084FC).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFFC084FC)
                            )
                        )
                    }
                }

                // Secondary Action Row: Sort by & Home AP Toggle & Scan Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Sort Button
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurfaceContainerHigh,
                            modifier = Modifier.height(34.dp)
                        ) {
                            Button(
                                onClick = {
                                    sortOption = when (sortOption) {
                                        ApSortOption.SIGNAL -> ApSortOption.CHANNEL
                                        ApSortOption.CHANNEL -> ApSortOption.SSID
                                        ApSortOption.SSID -> ApSortOption.SIGNAL
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Sort: ${sortOption.label}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Home Only Filter
                        FilterChip(
                            selected = showOnlyHomeAps,
                            onClick = { showOnlyHomeAps = !showOnlyHomeAps },
                            label = {
                                Text(
                                    text = if (showOnlyHomeAps) "Home Only" else "All APs",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryContainerBlue.copy(alpha = 0.2f),
                                selectedLabelColor = PrimaryContainerBlue
                            ),
                            modifier = Modifier.height(34.dp)
                        )
                    }

                    // Scan Now Button
                    Button(
                        onClick = { viewModel.triggerScan() },
                        enabled = scanStatus !is ScanStatus.Scanning,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryContainerBlue,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        if (scanStatus is ScanStatus.Scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Scanning", fontSize = 11.sp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Scan Now", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 5. Throttling / Status Notices
        when (val status = scanStatus) {
            is ScanStatus.Throttled -> {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = TertiaryContainerAmber.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = TertiaryContainerAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Android scan throttle active (cooldown: ${status.secondsCooldown}s). Showing latest cached results.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TertiaryContainerAmber
                            )
                        }
                    }
                }
            }
            is ScanStatus.Cached -> item {
                Text(
                    text = "Showing cached results. " + (status.lastMeasuredAt?.let {
                        "Last measured " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
                    } ?: "Measurement time is unknown."),
                    style = MaterialTheme.typography.labelSmall,
                    color = TertiaryContainerAmber
                )
            }
            is ScanStatus.Error -> item {
                Text(status.message, color = MaterialTheme.colorScheme.error)
            }
            else -> {}
        }

        // 6. Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { onTextInputFocusChanged(it.isFocused) },
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text(
                        text = "Filter by SSID or BSSID...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = DarkSurfaceContainer,
                    unfocusedContainerColor = DarkSurfaceContainer
                ),
                singleLine = true
            )
        }

        // 7. Access Points List
        if (displayedAps.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No Matching Access Points",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (showOnlyHomeAps) "No access points are currently tagged as Home. Tap the star icon on any card to tag it."
                            else "Ensure device Wi-Fi is enabled and tap 'Scan Now' to broadcast probes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = displayedAps,
                key = { it.bssid }
            ) { ap ->
                AccessPointItemCard(
                    ap = ap,
                    onToggleHomeTag = { viewModel.toggleHomeTag(ap.bssid) },
                    onClick = {
                        selectedApForGraph = ap
                        onNavigateToDetails(ap)
                    }
                )
            }
        }
    }
}
