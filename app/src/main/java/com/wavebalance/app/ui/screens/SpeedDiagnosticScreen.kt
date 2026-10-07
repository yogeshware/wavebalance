package com.wavebalance.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.data.MLabNdt7Server
import com.wavebalance.app.model.ApplicationRating
import com.wavebalance.app.model.BufferbloatGrade
import com.wavebalance.app.model.DiagnosticPhase
import com.wavebalance.app.model.DiagnosticState
import com.wavebalance.app.model.SpeedDiagnosticEngine
import com.wavebalance.app.model.SpeedDiagnosticResult
import com.wavebalance.app.model.ThroughputResult
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.adaptive.LocalWindowLayout
import com.wavebalance.app.ui.adaptive.TwoColumnPage
import com.wavebalance.app.ui.components.SpeedLatencyGraph
import com.wavebalance.app.ui.components.SpeedometerCanvas
import com.wavebalance.app.ui.theme.CardSurfaceSlate
import com.wavebalance.app.ui.theme.DarkBackground
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerLowest
import com.wavebalance.app.ui.theme.ErrorRed
import com.wavebalance.app.ui.theme.NeonCyan
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber
import java.util.Locale

/**
 * Dispatches the diagnostic report via Android system sharesheet.
 */
private fun exportSpeedReport(
    context: Context,
    result: SpeedDiagnosticResult,
    ssid: String,
    bssid: String,
    linkSpeedMbps: Int?
) {
    val report = SpeedDiagnosticEngine.generateSpeedReportMarkdown(
        result = result,
        activeSsid = ssid,
        bssid = bssid,
        linkSpeedMbps = linkSpeedMbps
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "WaveBalance Speed & Latency Diagnostic - $ssid")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    val chooser = Intent.createChooser(sendIntent, "Export Speed Diagnostic Audit")
    context.startActivity(chooser)
}

/**
 * Complete Real-Time Speed, Latency, and Bufferbloat Diagnostic Screen.
 */
@Composable
fun SpeedDiagnosticScreen(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier
) {
    val activeConn by viewModel.activeConnection.collectAsState()
    val diagnosticState by viewModel.diagnosticState.collectAsState()
    val isRunning by viewModel.isDiagnosticRunning.collectAsState()
    val context = LocalContext.current

    val result = diagnosticState.result
    val mlabConsentGiven by viewModel.mlabConsentGiven.collectAsState()
    var showConsentDialog by rememberSaveable { mutableStateOf(false) }

    if (showConsentDialog) {
        MLabConsentDialog(
            onAccept = {
                showConsentDialog = false
                viewModel.giveMlabConsent()
                viewModel.startFullDiagnostic()
            },
            onDismiss = { showConsentDialog = false }
        )
    }
    val theoreticalMbps = activeConn?.linkSpeedMbps ?: 433

    val linkHeader: @Composable () -> Unit = {
        ActiveLinkHeaderCard(
            ssid = activeConn?.cleanSsid ?: "Discovered Wi-Fi",
            bssid = activeConn?.bssid ?: "00:00:00:00:00:00",
            band = activeConn?.band?.label ?: "5 GHz",
            rssi = activeConn?.rssi ?: -65,
            theoreticalMbps = theoreticalMbps
        )
    }
    val gaugeCard: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SpeedometerCanvas(
                    currentSpeedMbps = diagnosticState.currentSpeedMbps,
                    currentPingMs = diagnosticState.currentPingMs,
                    phase = diagnosticState.phase,
                    progress = diagnosticState.progress,
                    showLatency = diagnosticState.phase == DiagnosticPhase.PING_JITTER ||
                        (result != null && result.throughput == null)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Progress Indicator
                LinearProgressIndicator(
                    progress = { diagnosticState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = NeonCyan,
                    trackColor = CardSurfaceSlate
                )
            }
        }
    }
    val actionControls: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isRunning) {
                Button(
                    onClick = {
                        if (mlabConsentGiven) viewModel.startFullDiagnostic() else showConsentDialog = true
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Run",
                        tint = DarkBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (result == null) "Run Diagnostic" else "Retest",
                        fontWeight = FontWeight.Bold,
                        color = DarkBackground
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.startQuickPing() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkPing,
                        contentDescription = "Ping Only",
                        tint = PrimaryContainerBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ping Only", fontSize = 12.sp, color = PrimaryContainerBlue)
                }

                if (result != null) {
                    Button(
                        onClick = {
                            exportSpeedReport(
                                context = context,
                                result = result,
                                ssid = activeConn?.cleanSsid ?: "Not connected",
                                bssid = activeConn?.bssid ?: "Not connected",
                                linkSpeedMbps = activeConn?.linkSpeedMbps
                            )
                        },
                        modifier = Modifier.weight(0.9f),
                        colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceSlate),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.cancelDiagnostic() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Cancel",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel Diagnostic", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
    val metricGrid: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricMiniCard(
                    title = "PING LATENCY",
                    value = if (result != null) "${String.format(Locale.US, "%.1f", result.unloadedPingMs)} ms" else "--",
                    subtext = "Unloaded RTT",
                    valueColor = SecondaryContainerEmerald,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCard(
                    title = "JITTER",
                    value = if (result != null) "±${String.format(Locale.US, "%.1f", result.jitterMs)} ms" else "--",
                    subtext = "Stability (σ)",
                    valueColor = if (result != null && result.jitterMs > 15.0) TertiaryContainerAmber else NeonCyan,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val throughput = result?.throughput
                MetricMiniCard(
                    title = "DOWNLOAD",
                    value = if (throughput != null) "${String.format(Locale.US, "%.1f", throughput.downloadSpeedMbps)} Mbps" else "--",
                    subtext = if (throughput != null) "Peak ${String.format(Locale.US, "%.1f", throughput.peakDownloadMbps)}" else "Goodput",
                    valueColor = NeonCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniCard(
                    title = "UPLOAD",
                    value = if (throughput != null) "${String.format(Locale.US, "%.1f", throughput.uploadSpeedMbps)} Mbps" else "--",
                    subtext = "Uplink rate",
                    valueColor = PrimaryContainerBlue,
                    modifier = Modifier.weight(1f)
                )
            }
            TestSourceNote(state = diagnosticState)
        }
    }
    val telemetryGraph: @Composable () -> Unit = {
        SpeedLatencyGraph(samples = diagnosticState.latestSamples)
    }

    if (LocalWindowLayout.current.isExpanded) {
        // Desktop: run the test on the left, read the results on the right
        TwoColumnPage(
            modifier = modifier.background(DarkBackground),
            primaryWeight = 1f,
            secondaryWeight = 1.2f,
            primary = {
                linkHeader()
                gaugeCard()
                actionControls()
            },
            secondary = {
                metricGrid()
                telemetryGraph()
                result?.throughput?.let { throughput ->
                    BufferbloatAssessmentCard(result = result, throughput = throughput)
                    QosApplicationMatrixCard(qos = throughput.qosAssessment)
                }
            }
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // 1. Active Link Telemetry Card
        item { linkHeader() }

        // 2. Analog Speedometer Tachometer
        item { gaugeCard() }

        // 3. Action Controls
        item { actionControls() }

        // 4. Executive 4-Metric Grid (Ping, Jitter, Download, Upload)
        item { metricGrid() }

        // 5. Bufferbloat & Loaded Latency Card
        result?.throughput?.let { throughput ->
            item {
                BufferbloatAssessmentCard(result = result, throughput = throughput)
            }

            // 6. Quality of Service (QoS) Application Suitability Matrix
            item {
                QosApplicationMatrixCard(qos = throughput.qosAssessment)
            }
        }

        // 7. Real-Time Telemetry Graph
        item { telemetryGraph() }
    }
}

/**
 * Header card presenting active Wi-Fi link parameters.
 */
@Composable
private fun ActiveLinkHeaderCard(
    ssid: String,
    bssid: String,
    band: String,
    rssi: Int,
    theoreticalMbps: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ssid,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryContainerBlue.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = band,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryContainerBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "BSSID: $bssid • $rssi dBm",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$theoreticalMbps Mbps",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryContainerEmerald
                )
                Text(
                    text = "PHY Ceiling",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Metric card component.
 */
@Composable
private fun MetricMiniCard(
    title: String,
    value: String,
    subtext: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtext,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Card explaining Bufferbloat grade and SQM recommendation.
 */
@Composable
private fun BufferbloatAssessmentCard(result: SpeedDiagnosticResult, throughput: ThroughputResult) {
    val grade = throughput.bufferbloatGrade
    val gradeColor = when (grade) {
        BufferbloatGrade.A_PLUS -> NeonCyan
        BufferbloatGrade.A -> SecondaryContainerEmerald
        BufferbloatGrade.B -> SecondaryContainerEmerald
        BufferbloatGrade.C -> TertiaryContainerAmber
        BufferbloatGrade.D -> TertiaryContainerAmber
        BufferbloatGrade.F -> ErrorRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "BUFFERBLOAT ANALYSIS",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "+${String.format(Locale.US, "%.1f", throughput.bufferbloatDeltaMs)} ms under load",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = gradeColor
                    )
                    Text(
                        text = "Idle ${String.format(Locale.US, "%.0f", result.unloadedPingMs)} ms · " +
                            "downloading ${String.format(Locale.US, "%.0f", throughput.loadedDownloadPingMs)} ms · " +
                            "uploading ${String.format(Locale.US, "%.0f", throughput.loadedUploadPingMs)} ms",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Big Grade Badge
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(gradeColor.copy(alpha = 0.15f))
                        .border(1.5.dp, gradeColor, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = grade.grade,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = gradeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = grade.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CardSurfaceSlate
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (grade >= BufferbloatGrade.C) TertiaryContainerAmber else SecondaryContainerEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (grade >= BufferbloatGrade.C)
                            "Router queue bloat detected. Enable SQM (Smart Queue Management) or CAKE/FQ-CoDel on your router to protect real-time gaming & VoIP packets."
                        else
                            "Minimal buffer latency detected. Your router's active queue management is operating optimally.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * Quality of Service (QoS) Application Suitability Matrix.
 */
@Composable
private fun QosApplicationMatrixCard(qos: com.wavebalance.app.model.QosAssessment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "APPLICATION SUITABILITY (QoS)",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            QosRow(
                icon = Icons.Default.Gamepad,
                title = "Competitive Gaming",
                rating = qos.gamingRating,
                detail = qos.gamingDetail
            )
            Spacer(modifier = Modifier.height(8.dp))
            QosRow(
                icon = Icons.Default.VideoCall,
                title = "Video Conferencing (Meet / Zoom)",
                rating = qos.videoCallRating,
                detail = qos.videoCallDetail
            )
            Spacer(modifier = Modifier.height(8.dp))
            QosRow(
                icon = Icons.Default.Tv,
                title = "4K / 8K HDR Streaming",
                rating = qos.streamingRating,
                detail = qos.streamingDetail
            )
            Spacer(modifier = Modifier.height(8.dp))
            QosRow(
                icon = Icons.Default.CloudUpload,
                title = "Cloud Sync & Backups",
                rating = qos.cloudTransferRating,
                detail = qos.cloudTransferDetail
            )
        }
    }
}

@Composable
private fun QosRow(
    icon: ImageVector,
    title: String,
    rating: ApplicationRating,
    detail: String
) {
    val ratingColor = when (rating) {
        ApplicationRating.EXCELLENT -> SecondaryContainerEmerald
        ApplicationRating.GOOD -> NeonCyan
        ApplicationRating.FAIR -> TertiaryContainerAmber
        ApplicationRating.POOR -> ErrorRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardSurfaceSlate.copy(alpha = 0.5f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ratingColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = rating.title,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = ratingColor
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )
        }
    }
}

/**
 * Where the numbers came from, or why the test failed, with a link to M-Lab's privacy policy.
 */
@Composable
private fun TestSourceNote(state: DiagnosticState) {
    val result = state.result
    val throughput = result?.throughput
    val failed = state.phase == DiagnosticPhase.FAILED
    val text = when {
        failed -> state.error ?: "The test failed."
        result == null -> "Tests run on Measurement Lab (M-Lab) servers. M-Lab publishes each full test's " +
            "results, including your IP address, as open data. Ping Only doesn't run a test, so nothing is published."
        throughput == null -> "Ping only, to ${result.serverName ?: "the test server"}. Download, upload and bufferbloat weren't measured."
        else -> "Measured on ${result.serverName ?: "an M-Lab server"} · ${throughput.dataUsedBytes / 1_000_000} MB used · " +
            "published by M-Lab as open data.\nOne connection each way, as M-Lab measures it. Tests that use several " +
            "connections at once, like Speedtest.net, usually show more."
    }
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (failed) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "M-Lab privacy policy",
            fontSize = 11.sp,
            color = PrimaryContainerBlue,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable { uriHandler.openUri(MLabNdt7Server.PRIVACY_POLICY_URL) }
        )
    }
}

/**
 * M-Lab requires informed consent before a client's first test, because results
 * (including the IP address) are published.
 */
@Composable
private fun MLabConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speed tests use M-Lab") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The download and upload test runs on Measurement Lab (M-Lab), an open platform " +
                        "for internet research that Google's speed test also uses."
                )
                Text(
                    "M-Lab publishes every test result as open data, including your IP address and " +
                        "the date and time of the test. Ping Only doesn't run a test, so nothing is published."
                )
                Text(
                    text = "Read M-Lab's privacy policy",
                    color = PrimaryContainerBlue,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { uriHandler.openUri(MLabNdt7Server.PRIVACY_POLICY_URL) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Agree and run test") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
