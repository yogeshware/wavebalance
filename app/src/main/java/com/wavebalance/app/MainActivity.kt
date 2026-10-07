package com.wavebalance.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wavebalance.app.model.ScanStatus
import com.wavebalance.app.model.SurveyPoint
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.adaptive.LocalWindowLayout
import com.wavebalance.app.ui.adaptive.WindowLayout
import com.wavebalance.app.ui.components.PermissionRationaleModal
import com.wavebalance.app.ui.navigation.AppBottomBar
import com.wavebalance.app.ui.navigation.AppDestination
import com.wavebalance.app.ui.navigation.AppLogo
import com.wavebalance.app.ui.navigation.AppNavigationRail
import com.wavebalance.app.ui.navigation.NavigationPanel
import com.wavebalance.app.ui.screens.NetworksScreen
import com.wavebalance.app.ui.screens.DashboardScreen
import com.wavebalance.app.ui.screens.OptimizerScreen
import com.wavebalance.app.ui.screens.SiteSurveyScreen
import com.wavebalance.app.ui.screens.SpeedDiagnosticScreen
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber
import com.wavebalance.app.ui.theme.WaveBalanceTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WaveBalanceTheme {
                WaveBalanceAdaptiveApp()
            }
        }
    }
}

@Composable
fun WaveBalanceAdaptiveApp(
    viewModel: ScanViewModel = viewModel()
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
    // Where the speed test returns to when it is closed with Back or T
    var previousDestination by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
    var showPermissionModal by rememberSaveable { mutableStateOf(false) }
    var isEditingText by remember { mutableStateOf(false) }
    val destinationState = rememberSaveableStateHolder()

    val navigate: (AppDestination) -> Unit = { destination ->
        if (destination != currentDestination) {
            previousDestination = currentDestination
            currentDestination = destination
        }
    }

    BackHandler(enabled = currentDestination == AppDestination.SPEED) {
        currentDestination = previousDestination
    }

    val allAps by viewModel.allAccessPoints.collectAsState()
    val activeConn by viewModel.activeConnection.collectAsState()
    val isMockMode by viewModel.isMockMode.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    val collisionCount by viewModel.collisionCount.collectAsState()

    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    val requiredPermissions = remember {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissions.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.triggerScan()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (!viewModel.hasPermissions()) {
            showPermissionModal = true
        } else {
            viewModel.triggerScan()
        }
    }

    val toggleSpeedTest = {
        if (currentDestination == AppDestination.SPEED) {
            currentDestination = previousDestination
        } else {
            navigate(AppDestination.SPEED)
        }
    }

    val content: @Composable () -> Unit = {
        destinationState.SaveableStateProvider(currentDestination.name) {
            DestinationContent(
                destination = currentDestination,
                viewModel = viewModel,
                onNavigate = navigate,
                onTextInputFocusChanged = { isEditingText = it },
                onRequestPermissions = {
                    showPermissionModal = false
                    permissionLauncher.launch(requiredPermissions)
                },
                onOpenDetailsForActive = {
                    val activeBssid = activeConn?.bssid
                    val target = allAps.find { it.bssid.equals(activeBssid, ignoreCase = true) } ?: allAps.firstOrNull()
                    viewModel.selectAccessPoint(target)
                    navigate(AppDestination.NETWORKS)
                }
            )
        }
    }

    val latestContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { latestContent() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .onKeyEvent { keyEvent ->
                if (isEditingText || keyEvent.type != KeyEventType.KeyUp ||
                    keyEvent.isCtrlPressed || keyEvent.isAltPressed || keyEvent.isMetaPressed || keyEvent.isShiftPressed
                ) return@onKeyEvent false
                when (keyEvent.key) {
                    // Numbers follow the navigation panel; R and A were Radar and AP Details
                    Key.One, Key.D -> { navigate(AppDestination.DASHBOARD); true }
                    Key.Two, Key.N, Key.R, Key.A -> { navigate(AppDestination.NETWORKS); true }
                    Key.Three, Key.O -> { navigate(AppDestination.OPTIMIZER); true }
                    Key.Four, Key.H -> { navigate(AppDestination.SURVEY); true }
                    Key.Five -> { navigate(AppDestination.SPEED); true }
                    Key.T -> { toggleSpeedTest(); true }
                    Key.S -> { viewModel.toggleMockMode(!isMockMode); true }
                    Key.E -> { viewModel.shareAuditReport(context); true }
                    Key.Spacebar -> { viewModel.triggerScan(); true }
                    // Simulation shortcuts only act on simulated data
                    Key.W -> { if (isMockMode) viewModel.simulateWalkDegradation(); isMockMode }
                    Key.M -> { if (isMockMode) viewModel.simulateRoamToCandidate(); isMockMode }
                    Key.P -> {
                        val conn = activeConn
                        if (conn != null) {
                            viewModel.addSurveyPoint(
                                SurveyPoint(
                                    x = 0.50f,
                                    y = 0.50f,
                                    roomName = "Survey Pin",
                                    bssid = conn.bssid,
                                    ssid = conn.cleanSsid,
                                    rssi = conn.rssi,
                                    frequencyMhz = conn.frequencyMhz,
                                    channel = conn.channel,
                                    band = conn.band
                                )
                            )
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        val windowLayout = WindowLayout.fromWidth(maxWidth)

        CompositionLocalProvider(LocalWindowLayout provides windowLayout) {
            when (windowLayout) {
                WindowLayout.EXPANDED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    ) {
                        NavigationPanel(
                            current = currentDestination,
                            collisionCount = collisionCount,
                            activeConnection = activeConn,
                            isMockMode = isMockMode,
                            onNavigate = navigate,
                            onMockModeChange = { viewModel.toggleMockMode(it) },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.statusBars)
                        ) {
                            DesktopTopBar(
                                destination = currentDestination,
                                isMockMode = isMockMode,
                                scanStatus = scanStatus,
                                onExport = { viewModel.shareAuditReport(context) },
                                onScan = { viewModel.triggerScan() }
                            )
                            Box(modifier = Modifier.weight(1f)) { movableContent() }
                        }
                    }
                }
                WindowLayout.MEDIUM -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        AppNavigationRail(
                            current = currentDestination,
                            collisionCount = collisionCount,
                            onNavigate = navigate
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                        ) {
                            CompactTopBar(
                                destination = currentDestination,
                                isMockMode = isMockMode,
                                showSpeedAction = false,
                                onMockModeChange = { viewModel.toggleMockMode(it) },
                                onToggleSpeedTest = toggleSpeedTest,
                                onExport = { viewModel.shareAuditReport(context) },
                                onScan = { viewModel.triggerScan() }
                            )
                            Box(modifier = Modifier.weight(1f)) { movableContent() }
                        }
                    }
                }
                WindowLayout.COMPACT -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CompactTopBar(
                            destination = currentDestination,
                            isMockMode = isMockMode,
                            showSpeedAction = false,
                            onMockModeChange = { viewModel.toggleMockMode(it) },
                            onToggleSpeedTest = toggleSpeedTest,
                            onExport = { viewModel.shareAuditReport(context) },
                            onScan = { viewModel.triggerScan() }
                        )
                        Box(modifier = Modifier.weight(1f)) { movableContent() }
                        AppBottomBar(
                            current = currentDestination,
                            collisionCount = collisionCount,
                            onNavigate = navigate
                        )
                    }
                }
            }
        }

        if (showPermissionModal) {
            PermissionRationaleModal(
                onGrantClicked = {
                    showPermissionModal = false
                    permissionLauncher.launch(requiredPermissions)
                },
                onDismiss = {
                    showPermissionModal = false
                    viewModel.toggleMockMode(true)
                }
            )
        }
    }
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
    viewModel: ScanViewModel,
    onNavigate: (AppDestination) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDetailsForActive: () -> Unit,
    onTextInputFocusChanged: (Boolean) -> Unit
) {
    when (destination) {
        AppDestination.DASHBOARD -> DashboardScreen(
            viewModel = viewModel,
            onNavigateToRadar = { onNavigate(AppDestination.NETWORKS) },
            onNavigateToOptimizer = { onNavigate(AppDestination.OPTIMIZER) },
            onNavigateToSurvey = { onNavigate(AppDestination.SURVEY) },
            onNavigateToSpeedDiagnostic = { onNavigate(AppDestination.SPEED) },
            onNavigateToDetails = onOpenDetailsForActive
        )
        AppDestination.NETWORKS -> NetworksScreen(
            viewModel = viewModel,
            onRequestPermissions = onRequestPermissions,
            onTextInputFocusChanged = onTextInputFocusChanged
        )
        AppDestination.SURVEY -> SiteSurveyScreen(viewModel = viewModel)
        AppDestination.OPTIMIZER -> OptimizerScreen(
            viewModel = viewModel,
            onNavigateToRadar = { onNavigate(AppDestination.NETWORKS) }
        )
        AppDestination.SPEED -> SpeedDiagnosticScreen(viewModel = viewModel)
    }
}

/**
 * Top bar for laptop and desktop windows. Navigation and the simulation switch
 * live in the left panel, so this carries the page title and the scan actions.
 */
@Composable
private fun DesktopTopBar(
    destination: AppDestination,
    isMockMode: Boolean,
    scanStatus: ScanStatus,
    onExport: () -> Unit,
    onScan: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(start = 28.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isMockMode) {
                    Spacer(modifier = Modifier.width(10.dp))
                    SimulatedChip()
                }
            }
            Text(
                text = destination.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = scanStatusLabel(scanStatus),
            style = MaterialTheme.typography.labelMedium,
            color = when (scanStatus) {
                is ScanStatus.Error -> MaterialTheme.colorScheme.error
                is ScanStatus.Throttled -> TertiaryContainerAmber
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 16.dp)
        )
        OutlinedButton(onClick = onExport, shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export report")
        }
        Spacer(modifier = Modifier.width(10.dp))
        Button(
            onClick = onScan,
            enabled = scanStatus !is ScanStatus.Scanning,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan")
        }
    }
}

/**
 * Top bar for phones and medium windows, where navigation sits in the
 * bottom bar or rail and the simulation switch stays in reach here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactTopBar(
    destination: AppDestination,
    isMockMode: Boolean,
    showSpeedAction: Boolean,
    onMockModeChange: (Boolean) -> Unit,
    onToggleSpeedTest: () -> Unit,
    onExport: () -> Unit,
    onScan: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo()
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "WaveBalance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isMockMode) {
                            Spacer(modifier = Modifier.width(6.dp))
                            SimulatedChip()
                        }
                    }
                    Text(
                        text = destination.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Sim",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMockMode) TertiaryContainerAmber else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isMockMode,
                    onCheckedChange = onMockModeChange,
                    modifier = Modifier.height(28.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TertiaryContainerAmber,
                        checkedTrackColor = TertiaryContainerAmber.copy(alpha = 0.3f)
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (showSpeedAction) {
                    IconButton(onClick = onToggleSpeedTest) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Speed & Latency Diagnostic",
                            tint = if (destination == AppDestination.SPEED) SecondaryContainerEmerald else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onExport) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Export Audit Report",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onScan) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Scan",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun SimulatedChip() {
    Surface(
        shape = CircleShape,
        color = TertiaryContainerAmber.copy(alpha = 0.2f)
    ) {
        Text(
            text = "SIMULATED",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = TertiaryContainerAmber,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun scanStatusLabel(status: ScanStatus): String = when (status) {
    ScanStatus.Idle -> "Not scanned yet"
    ScanStatus.Scanning -> "Scanning…"
    is ScanStatus.Success -> {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.timestamp))
        "${status.count} networks · updated $time"
    }
    is ScanStatus.Cached -> "${status.count} cached networks · " +
        (status.lastMeasuredAt?.let { "last measured " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "measurement time unknown")
    is ScanStatus.Throttled -> "Cached results · Android limits scans · retry in ${status.secondsCooldown}s"
    is ScanStatus.Error -> status.message
}
