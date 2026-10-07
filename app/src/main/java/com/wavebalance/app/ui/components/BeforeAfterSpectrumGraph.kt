package com.wavebalance.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ChannelWidth
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.WifiStandard
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber

enum class SpectrumViewState {
    BEFORE_CURRENT,
    AFTER_OPTIMIZED
}

@Composable
fun BeforeAfterSpectrumGraph(
    band: FrequencyBand,
    currentChannel: Int,
    recommendedChannel: Int,
    currentScore: Int,
    recommendedScore: Int,
    activeAp: AccessPoint?,
    allAps: List<AccessPoint>,
    targetWidth: ChannelWidth = ChannelWidth.WIDTH_80,
    currentWidth: ChannelWidth = targetWidth,
    currentCenterFrequencyMhz: Int? = null,
    recommendedCenterFrequencyMhz: Int = FrequencyBand.channelToFrequency(recommendedChannel, band),
    modifier: Modifier = Modifier
) {
    var viewState by rememberSaveable { mutableStateOf(SpectrumViewState.BEFORE_CURRENT) }

    val channelConfig = remember(band) {
        when (band) {
            FrequencyBand.BAND_2_4_GHZ -> BandGraphConfig(
                startChannel = 1,
                endChannel = 14,
                channelMarkers = listOf(1, 3, 6, 9, 11, 14),
                bandLabel = "2.4 GHz Spectrum"
            )
            FrequencyBand.BAND_5_GHZ -> BandGraphConfig(
                startChannel = 34,
                endChannel = 166,
                channelMarkers = listOf(36, 44, 52, 60, 100, 116, 132, 149, 157, 165),
                bandLabel = "5 GHz Spectrum"
            )
            FrequencyBand.BAND_6_GHZ -> BandGraphConfig(
                startChannel = -1,
                endChannel = 235,
                channelMarkers = listOf(1, 37, 69, 101, 133, 165, 197, 221),
                bandLabel = "6 GHz Spectrum"
            )
            FrequencyBand.UNKNOWN -> BandGraphConfig(
                startChannel = 34,
                endChannel = 166,
                channelMarkers = listOf(36, 44, 52, 60, 100, 116, 132, 149, 157, 165),
                bandLabel = "5 GHz Spectrum"
            )
        }
    }

    val apsInBand = remember(allAps, band) {
        allAps.filter { it.band == band }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with Comparison Toggle Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "Spectrum Balancing",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = channelConfig.bandLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

                // Interactive Mode Selector Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurfaceContainerHigh
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        ModeChip(
                            label = "Before",
                            isSelected = viewState == SpectrumViewState.BEFORE_CURRENT,
                            activeColor = if (currentScore < 70) TertiaryContainerAmber else PrimaryContainerBlue,
                            onClick = { viewState = SpectrumViewState.BEFORE_CURRENT }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        ModeChip(
                            label = "After",
                            isSelected = viewState == SpectrumViewState.AFTER_OPTIMIZED,
                            activeColor = SecondaryContainerEmerald,
                            onClick = { viewState = SpectrumViewState.AFTER_OPTIMIZED }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // State Summary Banner
            AnimatedContent(
                targetState = viewState,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "summary_banner"
            ) { state ->
                when (state) {
                    SpectrumViewState.BEFORE_CURRENT -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (currentScore < 70) TertiaryContainerAmber.copy(alpha = 0.12f) else PrimaryContainerBlue.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (currentScore < 70) Icons.Default.WarningAmber else Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    tint = if (currentScore < 70) TertiaryContainerAmber else PrimaryContainerBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Current State: Channel $currentChannel (Health: $currentScore/100)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (currentScore < 70) "Contending with neighboring APs; elevated airtime delay" else "Standard channel allocation with mild co-presence",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    SpectrumViewState.AFTER_OPTIMIZED -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = SecondaryContainerEmerald.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    tint = SecondaryContainerEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Optimized Allocation: Channel $recommendedChannel (Health: $recommendedScore/100)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SecondaryContainerEmerald
                                    )
                                    Text(
                                        text = "Migrated to pristine frequency; zero co-channel packet contention",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Canvas Before / After Spectrum Chart
            val currentCenter = currentCenterFrequencyMhz ?: FrequencyBand.channelToFrequency(currentChannel, band)
            val displayCenter = if (viewState == SpectrumViewState.BEFORE_CURRENT) currentCenter else recommendedCenterFrequencyMhz
            val displayWidth = if (viewState == SpectrumViewState.BEFORE_CURRENT) currentWidth else targetWidth
            val animatedChannel by animateFloatAsState(
                targetValue = FrequencyBand.frequencyToChannelPosition(displayCenter, band),
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                label = "channel_transition"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F141C))
            ) {
                Canvas(modifier = Modifier.matchParentSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val w = size.width
                    val h = size.height
                    val graphHeight = h - 28.dp.toPx()

                    fun chToX(channel: Float): Float {
                        val range = (channelConfig.endChannel - channelConfig.startChannel).toFloat()
                        val clamped = channel.coerceIn(channelConfig.startChannel.toFloat(), channelConfig.endChannel.toFloat())
                        return ((clamped - channelConfig.startChannel) / range) * w
                    }

                    // One channel number is 5 MHz
                    val pxPerChannel = w / (channelConfig.endChannel - channelConfig.startChannel).toFloat()

                    fun rssiToY(rssi: Int): Float {
                        val minRssi = -100f
                        val maxRssi = -25f
                        val clamped = rssi.coerceIn(-100, -25).toFloat()
                        val norm = (clamped - minRssi) / (maxRssi - minRssi)
                        return graphHeight - (norm * (graphHeight - 20.dp.toPx()))
                    }

                    // 1. Horizontal dBm grid lines
                    val dbmLevels = listOf(-30, -50, -70, -90)
                    for (dbm in dbmLevels) {
                        val y = rssiToY(dbm)
                        drawLine(
                            color = Color(0xFF1E293B),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            "$dbm",
                            6f,
                            y - 4f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.argb(120, 148, 163, 184)
                                textSize = 9.sp.toPx()
                                isAntiAlias = true
                            }
                        )
                    }

                    // 2. Frequency Baseline
                    drawLine(
                        color = Color(0xFF334155),
                        start = Offset(0f, graphHeight),
                        end = Offset(w, graphHeight),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // 3. Draw Neighbor APs (Static or Muted in After Mode)
                    for (ap in apsInBand) {
                        // Skip if it's the active connection (we draw active connection separately with animation)
                        if (ap.isConnected) continue

                        // To scale: centred on the channel's real centre, as wide as the channel
                        val apX = chToX(ap.centerChannel)
                        val peakY = rssiToY(ap.rssi)
                        val halfWidthPx = (ap.channelWidth.mhz / 10f) * pxPerChannel

                        val isCollidingWithCurrent =
                            ap.centerFrequencyMhz - ap.channelWidth.mhz / 2 < currentCenter + currentWidth.mhz / 2 &&
                            ap.centerFrequencyMhz + ap.channelWidth.mhz / 2 > currentCenter - currentWidth.mhz / 2

                        val neighborColor = if (viewState == SpectrumViewState.BEFORE_CURRENT && isCollidingWithCurrent) {
                            TertiaryContainerAmber.copy(alpha = 0.85f)
                        } else {
                            Color(0xFF64748B).copy(alpha = 0.5f)
                        }

                        val path = Path().apply {
                            moveTo(apX - halfWidthPx, graphHeight)
                            // Control point twice as high so the apex lands on the RSSI
                            quadraticTo(apX, 2 * peakY - graphHeight, apX + halfWidthPx, graphHeight)
                            close()
                        }

                        drawPath(
                            path = path,
                            brush = Brush.verticalGradient(
                                colors = listOf(neighborColor.copy(alpha = 0.25f), Color.Transparent),
                                startY = peakY,
                                endY = graphHeight
                            )
                        )
                        drawPath(
                            path = path,
                            color = neighborColor,
                            style = Stroke(
                                width = if (isCollidingWithCurrent && viewState == SpectrumViewState.BEFORE_CURRENT) 2.dp.toPx() else 1.2.dp.toPx(),
                                pathEffect = if (isCollidingWithCurrent && viewState == SpectrumViewState.BEFORE_CURRENT) {
                                    PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                } else null
                            )
                        )

                        // Peak point & name
                        drawCircle(color = neighborColor, radius = 2.5.dp.toPx(), center = Offset(apX, peakY))
                        val truncatedName = ap.ssid.take(10)
                        drawContext.canvas.nativeCanvas.drawText(
                            "$truncatedName (${ap.rssi})",
                            (apX - 25.dp.toPx()).coerceIn(0f, w - 80.dp.toPx()),
                            peakY - 6f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.argb(160, 203, 213, 225)
                                textSize = 8.5.sp.toPx()
                                isAntiAlias = true
                            }
                        )
                    }

                    // 4. Draw Active User AP at animatedChannel
                    val activeX = chToX(animatedChannel)
                    val activeRssi = activeAp?.rssi ?: -52
                    val activePeakY = rssiToY(activeRssi)
                    val activeHalfWidthPx = (displayWidth.mhz / 10f) * pxPerChannel

                    val activeThemeColor = if (viewState == SpectrumViewState.BEFORE_CURRENT) {
                        PrimaryContainerBlue
                    } else {
                        SecondaryContainerEmerald
                    }

                    val activePath = Path().apply {
                        moveTo(activeX - activeHalfWidthPx, graphHeight)
                        quadraticTo(activeX, 2 * activePeakY - graphHeight, activeX + activeHalfWidthPx, graphHeight)
                        close()
                    }

                    // Gradient fill
                    drawPath(
                        path = activePath,
                        brush = Brush.verticalGradient(
                            colors = listOf(activeThemeColor.copy(alpha = 0.55f), Color.Transparent),
                            startY = activePeakY,
                            endY = graphHeight
                        )
                    )
                    // Solid outline
                    drawPath(
                        path = activePath,
                        color = activeThemeColor,
                        style = Stroke(width = 2.5.dp.toPx())
                    )

                    // Glowing apex beacon
                    drawCircle(color = activeThemeColor.copy(alpha = 0.35f), radius = 7.dp.toPx(), center = Offset(activeX, activePeakY))
                    drawCircle(color = activeThemeColor, radius = 4.dp.toPx(), center = Offset(activeX, activePeakY))

                    // Crest label
                    val activeSsid = activeAp?.ssid?.ifBlank { "My Network" } ?: "My Network"
                    val channelBadgeText = if (viewState == SpectrumViewState.BEFORE_CURRENT) {
                        "★ $activeSsid [Ch $currentChannel]"
                    } else {
                        "✓ $activeSsid [Ch $recommendedChannel OPTIMAL]"
                    }

                    drawContext.canvas.nativeCanvas.drawText(
                        channelBadgeText,
                        (activeX - 45.dp.toPx()).coerceIn(10f, w - 140.dp.toPx()),
                        activePeakY - 10f,
                        android.graphics.Paint().apply {
                            color = if (viewState == SpectrumViewState.BEFORE_CURRENT) {
                                android.graphics.Color.WHITE
                            } else {
                                android.graphics.Color.parseColor("#10B981")
                            }
                            textSize = 9.5.sp.toPx()
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                    )

                    // 5. Channel Numbers Axis
                    for (ch in channelConfig.channelMarkers) {
                        val chX = chToX(ch.toFloat())
                        drawLine(
                            color = Color(0xFF475569),
                            start = Offset(chX, graphHeight),
                            end = Offset(chX, graphHeight + 5.dp.toPx()),
                            strokeWidth = 1.dp.toPx()
                        )
                        val isHighlighted = ch == currentChannel || ch == recommendedChannel
                        drawContext.canvas.nativeCanvas.drawText(
                            "Ch $ch",
                            chX - 12.dp.toPx(),
                            graphHeight + 18.dp.toPx(),
                            android.graphics.Paint().apply {
                                color = if (isHighlighted) {
                                    android.graphics.Color.WHITE
                                } else {
                                    android.graphics.Color.argb(140, 148, 163, 184)
                                }
                                textSize = 8.5.sp.toPx()
                                isFakeBoldText = isHighlighted
                                isAntiAlias = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer note with dynamic legend
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
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (viewState == SpectrumViewState.BEFORE_CURRENT) PrimaryContainerBlue else SecondaryContainerEmerald
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (viewState == SpectrumViewState.BEFORE_CURRENT) "Target AP (Current Ch $currentChannel)" else "Balanced AP (Recommended Ch $recommendedChannel)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "${displayWidth.label} · center ${FrequencyBand.frequencyToChannel(displayCenter)}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) activeColor else Color.Transparent,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
