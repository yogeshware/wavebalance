package com.wavebalance.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ChannelRating
import com.wavebalance.app.model.ChannelScore
import com.wavebalance.app.model.ChannelWidth
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.OptimizerRecommendation
import com.wavebalance.app.model.RouterStep
import com.wavebalance.app.ui.ScanViewModel
import com.wavebalance.app.ui.adaptive.LocalWindowLayout
import com.wavebalance.app.ui.adaptive.TwoColumnPage
import com.wavebalance.app.ui.components.BeforeAfterSpectrumGraph
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptimizerScreen(
    viewModel: ScanViewModel,
    onNavigateToRadar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeConn by viewModel.activeConnection.collectAsState()
    val allAps by viewModel.allAccessPoints.collectAsState()
    val isMockMode by viewModel.isMockMode.collectAsState()
    val ownNetworkBssids by viewModel.ownNetworkBssids.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Active band or selected band
    var selectedBand by rememberSaveable {
        mutableStateOf(activeConn?.band ?: FrequencyBand.BAND_5_GHZ)
    }

    var selectedWidth by rememberSaveable {
        mutableStateOf(ChannelWidth.defaultForBand(selectedBand))
    }

    val currentChannel = activeConn?.takeIf { it.band == selectedBand }?.channel ?: 0

    val activeAp = remember(allAps, selectedBand, currentChannel) {
        allAps.find { it.isConnected && it.band == selectedBand }
    }

    val recommendation = remember(selectedBand, allAps, currentChannel, selectedWidth, ownNetworkBssids) {
        com.wavebalance.app.model.ChannelOptimizerEngine.evaluateBand(
            band = selectedBand,
            allAps = allAps,
            currentChannel = currentChannel,
            targetWidth = selectedWidth,
            ownNetworkBssids = ownNetworkBssids
        )
    }

    var migrationApplied by rememberSaveable(isMockMode, selectedBand, selectedWidth) { mutableStateOf(false) }

    val rankedScores = remember(recommendation) {
        recommendation.channelScores.sortedByDescending { it.score }
    }

    val bandControls: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Spectrum Band & Channel Bandwidth",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Band selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        FrequencyBand.BAND_2_4_GHZ to "2.4 GHz",
                        FrequencyBand.BAND_5_GHZ to "5 GHz",
                        FrequencyBand.BAND_6_GHZ to "6 GHz"
                    ).forEach { (b, label) ->
                        FilterChip(
                            selected = selectedBand == b,
                            onClick = {
                                selectedBand = b
                                // Adjust bandwidth sensible default
                                if (b == FrequencyBand.BAND_2_4_GHZ) selectedWidth = ChannelWidth.WIDTH_20
                                else if (selectedWidth !in ChannelWidth.supportedForBand(b)) selectedWidth = ChannelWidth.defaultForBand(b)
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryContainerBlue,
                                selectedLabelColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bandwidth selector chips
                val widths = ChannelWidth.supportedForBand(selectedBand)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    widths.forEach { w ->
                        FilterChip(
                            selected = selectedWidth == w,
                            onClick = { selectedWidth = w },
                            label = { Text(w.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SecondaryContainerEmerald,
                                selectedLabelColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }
    }
    val impactHero: @Composable () -> Unit = {
        OptimizationImpactHeroCard(
            recommendation = recommendation,
            migrationApplied = migrationApplied
        )
    }
    val spectrumGraph: @Composable () -> Unit = {
        if (recommendation.currentChannelEvaluated) BeforeAfterSpectrumGraph(
            band = selectedBand,
            currentChannel = recommendation.currentChannel,
            recommendedChannel = recommendation.recommendedChannel,
            currentScore = recommendation.currentScore,
            recommendedScore = recommendation.recommendedScore,
            activeAp = activeAp,
            allAps = allAps,
            targetWidth = recommendation.recommendedBandwidth,
            currentWidth = recommendation.currentBandwidth,
            currentCenterFrequencyMhz = recommendation.currentCenterFrequencyMhz,
            recommendedCenterFrequencyMhz = recommendation.recommendedCenterFrequencyMhz
        ) else {
            Text(
                text = "No supported current block is available for comparison in ${selectedBand.label}. Candidate scores are shown below; verify support in your router.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val routerDirectives: @Composable () -> Unit = {
        RouterDirectivesCard(
            recommendation = recommendation,
            migrationApplied = migrationApplied,
            onCopy = {
                clipboardManager.setText(AnnotatedString(recommendation.routerDirectivesText))
                Toast.makeText(context, "Router optimization directives copied to clipboard!", Toast.LENGTH_SHORT).show()
            },
            onSimulateMigration = {
                viewModel.simulateChannelMigration(
                    newChannel = recommendation.recommendedChannel,
                    newWidth = recommendation.recommendedBandwidth,
                    band = recommendation.band,
                    centerFrequencyMhz = recommendation.recommendedCenterFrequencyMhz
                )
                migrationApplied = true
                Toast.makeText(context, "Applied migration to Channel ${recommendation.recommendedChannel} in simulation!", Toast.LENGTH_SHORT).show()
            }.takeIf { isMockMode }
        )
    }
    val matrixHeader: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Channel Congestion Matrix",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Ranked candidates scored for ${selectedBand.label} (${selectedWidth.label})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceContainerHigh
            ) {
                Text(
                    text = "${recommendation.channelScores.size} Channels",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    if (LocalWindowLayout.current.isExpanded) {
        // Desktop: settings, result and router steps on the left, the ranked channels alongside
        TwoColumnPage(
            modifier = modifier,
            primaryWeight = 1.3f,
            secondaryWeight = 1f,
            primary = {
                bandControls()
                impactHero()
                spectrumGraph()
                routerDirectives()
            },
            secondary = {
                matrixHeader()
                rankedScores.forEach { channelScore ->
                    key("${channelScore.band}_${channelScore.channel}_${channelScore.centerFrequencyMhz}") {
                        ChannelScoreCard(channelScore = channelScore)
                    }
                }
            }
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Band & Bandwidth Controls Row
        item { bandControls() }

        // Optimization Impact Hero Card
        item { impactHero() }

        // Before vs. After Spectrum Balancing Canvas
        item { spectrumGraph() }

        // Router Directives & Action Card
        item { routerDirectives() }

        // Ranked Channel Matrix Header
        item { matrixHeader() }

        // Channel Score Cards
        items(
            items = rankedScores,
            key = { "${it.band}_${it.channel}_${it.centerFrequencyMhz}" }
        ) { channelScore ->
            ChannelScoreCard(channelScore = channelScore)
        }
    }
}

@Composable
private fun OptimizationImpactHeroCard(
    recommendation: OptimizerRecommendation,
    migrationApplied: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header
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
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SecondaryContainerEmerald.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = SecondaryContainerEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Channel Balancing Impact",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (migrationApplied) "Migration Active (Simulated)" else "Calculated Optimization Target",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (migrationApplied) SecondaryContainerEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (recommendation.scoreDelta > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SecondaryContainerEmerald.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "+${recommendation.scoreDelta} pts Clarity",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SecondaryContainerEmerald,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Score Transition Comparison Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Current Score Box
                ScoreBox(
                    label = "CURRENT",
                    channel = if (recommendation.currentChannelEvaluated) "Ch ${recommendation.currentChannel}" else "Unknown",
                    score = recommendation.currentScore,
                    color = if (recommendation.currentScore >= 75) PrimaryContainerBlue else TertiaryContainerAmber,
                    evaluated = recommendation.currentChannelEvaluated
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )

                // Recommended Score Box
                ScoreBox(
                    label = "RECOMMENDED",
                    channel = "Ch ${recommendation.recommendedChannel}",
                    score = recommendation.recommendedScore,
                    color = SecondaryContainerEmerald
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Explanation Pill
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = recommendation.reasonSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp
                    )
                    val ownRadios = recommendation.ownRadiosIgnored
                    if (ownRadios > 0) {
                        Text(
                            text = "Not counting $ownRadios ${if (ownRadios == 1) "radio" else "radios"} from your own network: " +
                                "they change channel along with your router.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreBox(
    label: String,
    channel: String,
    score: Int,
    color: Color,
    evaluated: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceContainerHigh,
        modifier = Modifier.width(130.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = channel,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (evaluated) "$score/100" else "N/A",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = color
            )
        }
    }
}

@Composable
private fun RouterDirectivesCard(
    recommendation: OptimizerRecommendation,
    migrationApplied: Boolean,
    onCopy: () -> Unit,
    // Null outside simulated data: migrating would overwrite real readings with made-up ones
    onSimulateMigration: (() -> Unit)?
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

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
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryContainerBlue.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = PrimaryContainerBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Router Configuration Directives",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Recommended router wireless settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButtonWithBackground(
                    icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    onClick = { expanded = !expanded }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryContainerBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Setup", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (onSimulateMigration != null) OutlinedButton(
                    onClick = onSimulateMigration,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SecondaryContainerEmerald
                    )
                ) {
                    Icon(
                        imageVector = if (migrationApplied) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = SecondaryContainerEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (migrationApplied) "Migrated" else "Simulate",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Expandable Step-by-Step Directives
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    recommendation.stepByStepGuide.forEach { step ->
                        StepItem(step = step)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(step: RouterStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(PrimaryContainerBlue.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${step.stepNumber}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryContainerBlue
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ChannelScoreCard(channelScore: ChannelScore) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val scoreColor = when (channelScore.rating) {
        ChannelRating.OPTIMAL -> SecondaryContainerEmerald
        ChannelRating.GOOD -> PrimaryContainerBlue
        ChannelRating.FAIR -> TertiaryContainerAmber
        ChannelRating.CONGESTED -> Color(0xFFEF4444)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (channelScore.isRecommended) DarkSurfaceContainerHigh else DarkSurfaceContainer
        )
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
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Ch ${channelScore.channel} · center ${FrequencyBand.frequencyToChannel(channelScore.centerFrequencyMhz)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (channelScore.isRecommended) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SecondaryContainerEmerald.copy(alpha = 0.2f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = SecondaryContainerEmerald,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "RECOMMENDED",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SecondaryContainerEmerald
                                        )
                                    }
                                }
                            }
                            if (channelScore.isCurrentChannel) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PrimaryContainerBlue.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "CURRENT",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryContainerBlue,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (channelScore.isDfs) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFC084FC).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "DFS",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFC084FC),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "${channelScore.frequencyMhz} MHz",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Score pill & rating
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${channelScore.score}/100",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = scoreColor
                    )
                    Text(
                        text = channelScore.rating.label,
                        fontSize = 11.sp,
                        color = scoreColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Score progress bar
            LinearProgressIndicator(
                progress = { channelScore.score / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = scoreColor,
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Overlap summary footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val totalCollisions = channelScore.coChannelCount + channelScore.adjacentChannelCount
                val conflictText = if (totalCollisions == 0) {
                    "Pristine (0 collisions)"
                } else {
                    "${channelScore.coChannelCount} Co-Channel, ${channelScore.adjacentChannelCount} Adjacent"
                }

                Text(
                    text = conflictText,
                    fontSize = 11.sp,
                    color = if (totalCollisions == 0) SecondaryContainerEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (channelScore.maxInterferingRssi != null) {
                    Text(
                        text = "Max Interference: ${channelScore.maxInterferingRssi} dBm",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded view with conflicting SSIDs
            if (expanded && channelScore.conflictingSsids.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Compromising Networks: " + channelScore.conflictingSsids.joinToString(", "),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun IconButtonWithBackground(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = DarkSurfaceContainerHigh,
        modifier = Modifier.clickable { onClick() }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(6.dp)
                .size(18.dp)
        )
    }
}
