package com.wavebalance.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.WifiVendorLookup
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.WifiScanScreen
import com.wavebalance.app.ui.components.AccessPointItemCard
import com.wavebalance.app.ui.components.ParabolicRadarGraph
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.OutlineBorderVariant
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.SignalExcellent
import com.wavebalance.app.ui.theme.SignalFair
import com.wavebalance.app.ui.theme.SignalGood
import com.wavebalance.app.ui.theme.SignalWeak
import com.wavebalance.app.ui.theme.TertiaryContainerAmber

/** How the network list on the left of the wide layout is shown. */
enum class NetworksView { LIST, TABLE }

/**
 * A column of the networks table. Columns are listed in the order they're dropped when the
 * table runs out of width: the last one goes first.
 */
enum class NetworkColumn(val title: String, val width: Dp?, val numeric: Boolean = false) {
    NETWORK("Network", null),
    SIGNAL("Signal", 108.dp, numeric = true),
    CHANNEL("Ch", 44.dp, numeric = true),
    BAND("Band", 66.dp),
    WIDTH("Width", 66.dp, numeric = true),
    SECURITY("Security", 108.dp),
    STANDARD("Wi-Fi", 64.dp),
    VENDOR("Vendor", 112.dp),
    BSSID("BSSID", 140.dp);

    fun comparator(): Comparator<AccessPoint> = when (this) {
        NETWORK -> compareBy { it.displayName.lowercase() }
        SIGNAL -> compareBy { it.rssi }
        CHANNEL -> compareBy { it.channel }
        BAND -> compareBy { it.band.ordinal }
        WIDTH -> compareBy { it.channelWidth.mhz }
        SECURITY -> compareBy { it.securityType }
        STANDARD -> compareBy { it.standard.ordinal }
        VENDOR -> compareBy { WifiVendorLookup.getVendor(it.bssid).lowercase() }
        BSSID -> compareBy { it.bssid.lowercase() }
    }

    companion object {
        // The star and the network name need this much room before any other column fits
        val NETWORK_MIN_WIDTH = 170.dp
        val STAR_WIDTH = 36.dp

        /** The columns that fit in [available] width, dropping from the end of the list. */
        fun fitting(available: Dp): List<NetworkColumn> {
            val columns = entries.toMutableList()
            fun needed() = STAR_WIDTH + NETWORK_MIN_WIDTH + columns.mapNotNull { it.width }.fold(0.dp) { a, b -> a + b }
            while (columns.size > 1 && needed() > available) columns.removeAt(columns.lastIndex)
            return columns
        }
    }
}

/**
 * Networks: every access point in range, on the channel radar and in a list or table,
 * with the selected one's details beside them when the window is wide enough. Narrow
 * windows get the radar and list, and the details open in place of them.
 */
@Composable
fun NetworksScreen(
    viewModel: ScanViewModel,
    onRequestPermissions: () -> Unit,
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= WIDE_MIN_WIDTH) {
            WideNetworks(viewModel, onTextInputFocusChanged)
        } else {
            NarrowNetworks(viewModel, onRequestPermissions, onTextInputFocusChanged)
        }
    }
}

// Room for the list or table beside a detail pane
private val WIDE_MIN_WIDTH = 900.dp
private val DETAIL_PANE_WIDTH = 400.dp

@Composable
private fun NarrowNetworks(
    viewModel: ScanViewModel,
    onRequestPermissions: () -> Unit,
    onTextInputFocusChanged: (Boolean) -> Unit
) {
    // Details analyse the whole scan; Radar's filters only change its list
    val allAps by viewModel.allAccessPoints.collectAsState()
    val selected by viewModel.selectedAp.collectAsState()
    val rssiHistory by viewModel.rssiHistory.collectAsState()
    var showingDetails by rememberSaveable { mutableStateOf(false) }
    val target = selected?.let { s -> allAps.find { it.bssid == s.bssid } ?: s }

    if (showingDetails && target != null) {
        ApDetailContent(
            ap = target,
            allAps = allAps,
            samples = rssiHistory[target.bssid] ?: emptyList(),
            onToggleHomeTag = { viewModel.toggleHomeTag(target.bssid) },
            onSelectOtherAp = { viewModel.selectAccessPoint(it) },
            onNavigateBack = { showingDetails = false },
            showBackArrow = true
        )
    } else {
        WifiScanScreen(
            viewModel = viewModel,
            onRequestPermissions = onRequestPermissions,
            onTextInputFocusChanged = onTextInputFocusChanged,
            onNavigateToDetails = { ap ->
                viewModel.selectAccessPoint(ap)
                showingDetails = true
            }
        )
    }
}

@Composable
private fun WideNetworks(viewModel: ScanViewModel, onTextInputFocusChanged: (Boolean) -> Unit) {
    // The list shows the filtered networks; the details pane analyses the whole scan
    val allAps by viewModel.filteredAccessPoints.collectAsState()
    val fullScan by viewModel.allAccessPoints.collectAsState()
    val activeConn by viewModel.activeConnection.collectAsState()
    val selectedState by viewModel.selectedAp.collectAsState()
    val rssiHistory by viewModel.rssiHistory.collectAsState()
    val selectedBand by viewModel.selectedBandFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val ownBssids by viewModel.ownNetworkBssids.collectAsState()

    var view by rememberSaveable { mutableStateOf(NetworksView.TABLE) }
    var sortColumn by rememberSaveable { mutableStateOf(NetworkColumn.SIGNAL) }
    var sortDescending by rememberSaveable { mutableStateOf(true) }
    var onlyHome by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { onTextInputFocusChanged(false) }
    }

    val shown = remember(allAps, sortColumn, sortDescending, onlyHome) {
        val list = if (onlyHome) allAps.filter { it.isUserTaggedHome } else allAps
        val comparator = sortColumn.comparator().let { if (sortDescending) it.reversed() else it }
        list.sortedWith(comparator.thenBy { it.bssid })
    }
    // The selection follows the latest scan; without one, show the connection or the strongest
    val target = remember(selectedState, allAps, fullScan, activeConn) {
        val ap = selectedState?.let { s -> fullScan.find { it.bssid == s.bssid } ?: s }
            ?: allAps.find { it.isConnected }
            ?: allAps.maxByOrNull { it.rssi }
        val conn = activeConn
        if (ap != null && conn != null && ap.bssid.equals(conn.bssid, ignoreCase = true)) ap.copy(rssi = conn.rssi) else ap
    }
    val graphBand = selectedBand ?: target?.band ?: activeConn?.band ?: FrequencyBand.BAND_5_GHZ

    Row(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NetworksToolbar(
                selectedBand = selectedBand,
                onBand = viewModel::setBandFilter,
                onlyHome = onlyHome,
                onOnlyHome = { onlyHome = it }
            )
            ParabolicRadarGraph(
                accessPoints = shown,
                selectedBand = graphBand,
                selectedAp = target,
                onSelectAp = viewModel::selectAccessPoint,
                chartHeight = 150.dp
            )
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
            ) {
                ListHeader(
                    count = shown.size,
                    query = searchQuery,
                    onQuery = viewModel::setSearchQuery,
                    onFocusChanged = onTextInputFocusChanged,
                    view = view,
                    onView = { view = it }
                )
                if (shown.isEmpty()) {
                    EmptyNetworks(filtered = allAps.isNotEmpty() || searchQuery.isNotBlank() || onlyHome)
                } else when (view) {
                    NetworksView.TABLE -> NetworksTable(
                        aps = shown,
                        selectedBssid = target?.bssid,
                        ownBssids = ownBssids,
                        sortColumn = sortColumn,
                        sortDescending = sortDescending,
                        onSort = { column ->
                            if (column == sortColumn) sortDescending = !sortDescending
                            else {
                                sortColumn = column
                                // Strongest signal and widest channel first; names A to Z
                                sortDescending = column == NetworkColumn.SIGNAL || column == NetworkColumn.WIDTH
                            }
                        },
                        onSelect = viewModel::selectAccessPoint,
                        onToggleHome = { viewModel.toggleHomeTag(it.bssid) }
                    )
                    NetworksView.LIST -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.bssid }) { ap ->
                            AccessPointItemCard(
                                ap = ap,
                                onToggleHomeTag = { viewModel.toggleHomeTag(ap.bssid) },
                                onClick = { viewModel.selectAccessPoint(ap) },
                                modifier = if (ap.bssid == target?.bssid) Modifier.background(
                                    PrimaryContainerBlue.copy(alpha = 0.12f),
                                    RoundedCornerShape(16.dp)
                                ) else Modifier
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Box(modifier = Modifier.width(DETAIL_PANE_WIDTH).fillMaxHeight()) {
            if (target != null) {
                ApDetailContent(
                    ap = target,
                    allAps = fullScan,
                    samples = rssiHistory[target.bssid] ?: emptyList(),
                    onToggleHomeTag = { viewModel.toggleHomeTag(target.bssid) },
                    onSelectOtherAp = { viewModel.selectAccessPoint(it) },
                    onNavigateBack = {},
                    showBackArrow = false
                )
            } else {
                EmptyApDetailPlaceholder()
            }
        }
    }
}

@Composable
private fun NetworksToolbar(
    selectedBand: FrequencyBand?,
    onBand: (FrequencyBand?) -> Unit,
    onlyHome: Boolean,
    onOnlyHome: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val bands = listOf(null, FrequencyBand.BAND_2_4_GHZ, FrequencyBand.BAND_5_GHZ, FrequencyBand.BAND_6_GHZ)
        bands.forEach { band ->
            FilterChip(
                selected = selectedBand == band,
                onClick = { onBand(band) },
                label = { Text(band?.label ?: "All bands", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryContainerBlue.copy(alpha = 0.2f),
                    selectedLabelColor = PrimaryContainerBlue
                )
            )
        }
        FilterChip(
            selected = onlyHome,
            onClick = { onOnlyHome(!onlyHome) },
            label = { Text("Starred", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = TertiaryContainerAmber.copy(alpha = 0.2f),
                selectedLabelColor = TertiaryContainerAmber,
                selectedLeadingIconColor = TertiaryContainerAmber
            )
        )
    }
}

/** The list's own header: how many networks are shown, search, and list or table. */
@Composable
private fun ListHeader(
    count: Int,
    query: String,
    onQuery: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    view: NetworksView,
    onView: (NetworksView) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (count == 1) "1 network" else "$count networks",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        SearchField(query = query, onQuery = onQuery, onFocusChanged = onFocusChanged, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            NetworksView.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = view == option,
                    onClick = { onView(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, NetworksView.entries.size),
                    icon = {},
                    label = {
                        Icon(
                            imageVector = if (option == NetworksView.TABLE) Icons.Default.TableRows else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = if (option == NetworksView.TABLE) "Table" else "List",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .background(DarkSurfaceContainerHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = PrimaryContainerBlue, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search name, BSSID or vendor", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(PrimaryContainerBlue),
                // App shortcuts pause while typing here
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) }
            )
        }
    }
}

@Composable
private fun NetworksTable(
    aps: List<AccessPoint>,
    selectedBssid: String?,
    ownBssids: Set<String>,
    sortColumn: NetworkColumn,
    sortDescending: Boolean,
    onSort: (NetworkColumn) -> Unit,
    onSelect: (AccessPoint) -> Unit,
    onToggleHome: (AccessPoint) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = NetworkColumn.fitting(maxWidth - 16.dp)
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(NetworkColumn.STAR_WIDTH))
                columns.forEach { column ->
                    HeaderCell(
                        column = column,
                        sorted = column == sortColumn,
                        descending = sortDescending,
                        onClick = { onSort(column) },
                        modifier = column.width?.let { Modifier.width(it) } ?: Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(color = OutlineBorderVariant)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(aps, key = { it.bssid }) { ap ->
                    NetworkRow(
                        ap = ap,
                        columns = columns,
                        selected = ap.bssid == selectedBssid,
                        own = ap.bssid in ownBssids,
                        onClick = { onSelect(ap) },
                        onToggleHome = { onToggleHome(ap) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(
    column: NetworkColumn,
    sorted: Boolean,
    descending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Row(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (column.numeric) Arrangement.End else Arrangement.Start
    ) {
        Text(
            text = column.title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = if (sorted) PrimaryContainerBlue else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (sorted) {
            Icon(
                imageVector = if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = if (descending) "Sorted descending" else "Sorted ascending",
                tint = PrimaryContainerBlue,
                modifier = Modifier.padding(start = 2.dp).size(14.dp)
            )
        }
    }
}

@Composable
private fun NetworkRow(
    ap: AccessPoint,
    columns: List<NetworkColumn>,
    selected: Boolean,
    own: Boolean,
    onClick: () -> Unit,
    onToggleHome: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(if (selected) PrimaryContainerBlue.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(NetworkColumn.STAR_WIDTH).fillMaxHeight().clickable(onClick = onToggleHome),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (ap.isUserTaggedHome) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = if (ap.isUserTaggedHome) "Unstar" else "Star",
                tint = if (ap.isUserTaggedHome) TertiaryContainerAmber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
        columns.forEach { column ->
            val cell = column.width?.let { Modifier.width(it) } ?: Modifier.weight(1f)
            when (column) {
                NetworkColumn.NETWORK -> Row(
                    modifier = cell.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ap.displayName,
                        fontSize = 13.sp,
                        fontWeight = if (ap.isConnected) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (ap.isConnected) Tag("Connected", SecondaryContainerEmerald)
                    else if (own) Tag("Yours", PrimaryContainerBlue, icon = true)
                }
                NetworkColumn.SIGNAL -> SignalCell(ap.rssi, cell)
                else -> Text(
                    text = cellText(ap, column),
                    fontSize = 12.sp,
                    fontFamily = if (column == NetworkColumn.BSSID || column.numeric) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (column.numeric) androidx.compose.ui.text.style.TextAlign.End else null,
                    modifier = cell.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

private fun cellText(ap: AccessPoint, column: NetworkColumn): String = when (column) {
    NetworkColumn.CHANNEL -> ap.channel.toString()
    NetworkColumn.BAND -> ap.band.label
    NetworkColumn.WIDTH -> "${ap.channelWidth.mhz} MHz"
    NetworkColumn.SECURITY -> ap.securityType
    NetworkColumn.STANDARD -> ap.standard.generation
    NetworkColumn.VENDOR -> WifiVendorLookup.getVendor(ap.bssid)
    NetworkColumn.BSSID -> ap.bssid.uppercase()
    NetworkColumn.NETWORK, NetworkColumn.SIGNAL -> ""
}

@Composable
private fun Tag(text: String, color: Color, icon: Boolean = false) {
    Row(
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon) {
            Icon(Icons.Default.Home, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(text, fontSize = 10.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun SignalCell(rssi: Int, modifier: Modifier) {
    val color = when {
        rssi > -50 -> SignalExcellent
        rssi > -65 -> SignalGood
        rssi > -75 -> SignalFair
        else -> SignalWeak
    }
    // -100 dBm empty, -30 dBm full
    val fraction = ((rssi + 100) / 70f).coerceIn(0.05f, 1f)
    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(6.dp)
                .background(DarkSurfaceContainerHigh, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
        Text(
            text = "$rssi dBm",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun EmptyNetworks(filtered: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (filtered) "No networks match the filters." else "No networks found yet. Scanning…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}
