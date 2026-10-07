package com.wavebalance.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.NetworkGroups
import com.wavebalance.app.ui.theme.DarkSurfaceContainer
import com.wavebalance.app.ui.theme.DarkSurfaceContainerHigh
import com.wavebalance.app.ui.theme.PrimaryContainerBlue
import com.wavebalance.app.ui.theme.SecondaryContainerEmerald
import com.wavebalance.app.ui.theme.TertiaryContainerAmber

@Composable
fun ParabolicRadarGraph(
    accessPoints: List<AccessPoint>,
    selectedBand: FrequencyBand,
    selectedAp: AccessPoint? = null,
    onSelectAp: (AccessPoint) -> Unit = {},
    modifier: Modifier = Modifier,
    chartHeight: Dp = 230.dp
) {
    val apsInBand = remember(accessPoints, selectedBand) {
        accessPoints.filter { it.band == selectedBand }
    }

    val channelConfig = remember(selectedBand) {
        when (selectedBand) {
            FrequencyBand.BAND_2_4_GHZ -> BandGraphConfig(
                startChannel = 1,
                endChannel = 14,
                channelMarkers = listOf(1, 3, 6, 9, 11, 14),
                bandLabel = "2.4 GHz (Ch 1–14)"
            )
            FrequencyBand.BAND_5_GHZ -> BandGraphConfig(
                startChannel = 34,
                endChannel = 166,
                channelMarkers = listOf(36, 44, 52, 60, 100, 116, 132, 149, 157, 165),
                bandLabel = "5 GHz (Ch 36–165)"
            )
            FrequencyBand.BAND_6_GHZ -> BandGraphConfig(
                startChannel = 1,
                endChannel = 225,
                channelMarkers = listOf(1, 37, 69, 101, 133, 165, 197, 221),
                bandLabel = "6 GHz (Ch 1–221)"
            )
            FrequencyBand.UNKNOWN -> BandGraphConfig(
                startChannel = 1,
                endChannel = 14,
                channelMarkers = listOf(1, 6, 11),
                bandLabel = "Spectrum"
            )
        }
    }

    // Channels shared by 2+ different networks. One router's or mesh's own radios
    // sharing a channel (guest SSID, mesh nodes) isn't a collision.
    val collisionChannels = remember(accessPoints, apsInBand) {
        // Group across all bands: a band-to-band sibling can be what links two SSIDs
        val networks = NetworkGroups.group(accessPoints)
        apsInBand.groupBy { it.channel }
            .filter { (_, aps) -> aps.map { networks[it.bssid.lowercase()] }.distinct().size >= 2 }
            .keys.toSet()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header / HUD
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
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (selectedBand) {
                                    FrequencyBand.BAND_6_GHZ -> Color(0xFFC084FC)
                                    FrequencyBand.BAND_5_GHZ -> PrimaryContainerBlue
                                    else -> TertiaryContainerAmber
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = channelConfig.bandLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Legend
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LegendItem(color = PrimaryContainerBlue, label = "Home")
                    LegendItem(color = SecondaryContainerEmerald, label = "Active")
                    LegendItem(color = Color(0xFF87929A), label = "Neighbor")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Canvas Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF060E20))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .pointerInput(apsInBand) {
                            detectTapGestures { offset ->
                                // Hit test closest dome peak
                                val graphPaddingLeft = 45.dp.toPx()
                                val graphPaddingRight = 20.dp.toPx()
                                val graphWidth = size.width - graphPaddingLeft - graphPaddingRight

                                val closest = apsInBand.minByOrNull { ap ->
                                    val chFrac = (ap.centerChannel - channelConfig.startChannel) /
                                            (channelConfig.endChannel - channelConfig.startChannel).coerceAtLeast(1)
                                    val apX = graphPaddingLeft + (chFrac * graphWidth)
                                    val apFrac = ((ap.rssi - (-95)) / 65f).coerceIn(0.05f, 1f)
                                    val apY = (size.height - 35.dp.toPx()) - (apFrac * (size.height - 55.dp.toPx()))

                                    val dx = offset.x - apX
                                    val dy = offset.y - apY
                                    dx * dx + dy * dy
                                }
                                if (closest != null) {
                                    onSelectAp(closest)
                                }
                            }
                        }
                ) {
                    val paddingLeft = 45.dp.toPx()
                    val paddingRight = 20.dp.toPx()
                    val paddingTop = 15.dp.toPx()
                    val paddingBottom = 32.dp.toPx()

                    val chartWidth = size.width - paddingLeft - paddingRight
                    val chartHeight = size.height - paddingTop - paddingBottom
                    val baselineY = size.height - paddingBottom

                    // 1. Draw dBm Grid Lines (-30 to -90)
                    val dbmLevels = listOf(-30, -45, -60, -70, -80, -90)
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

                    dbmLevels.forEach { dbm ->
                        val norm = ((dbm - (-95)) / 65f).coerceIn(0f, 1f)
                        val y = baselineY - (norm * chartHeight)

                        // Grid line
                        drawLine(
                            color = Color(0xFF222A3D),
                            start = Offset(paddingLeft, y),
                            end = Offset(size.width - paddingRight, y),
                            strokeWidth = if (dbm == -90) 1.5f else 1f,
                            pathEffect = if (dbm == -90) null else dashEffect
                        )

                        // Label
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#87929A")
                                textSize = 9.sp.toPx()
                                typeface = android.graphics.Typeface.MONOSPACE
                                textAlign = android.graphics.Paint.Align.RIGHT
                            }
                            drawText("$dbm", paddingLeft - 8f, y + 4.sp.toPx(), paint)
                        }
                    }

                    // 2. Draw Collision Hazard Highlight Zones
                    collisionChannels.forEach { ch ->
                        val chFrac = (ch - channelConfig.startChannel).toFloat() /
                                (channelConfig.endChannel - channelConfig.startChannel).coerceAtLeast(1)
                        val zoneX = paddingLeft + (chFrac * chartWidth)
                        val zoneWidth = 36.dp.toPx()

                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    TertiaryContainerAmber.copy(alpha = 0.25f),
                                    TertiaryContainerAmber.copy(alpha = 0.02f)
                                )
                            ),
                            topLeft = Offset(zoneX - zoneWidth / 2, paddingTop),
                            size = androidx.compose.ui.geometry.Size(zoneWidth, chartHeight)
                        )
                    }

                    // 3. Draw Parabolic Domes for each AP
                    // Draw neighbors first, user home APs and active connection on top
                    val sortedAps = apsInBand.sortedWith(
                        compareBy<AccessPoint> { it.isUserTaggedHome || it.isConnected }
                            .thenBy { it.rssi }
                    )

                    sortedAps.forEach { ap ->
                        // Drawn to scale: centred on the whole channel's centre frequency, and as wide
                        // as the channel (one channel number = 5 MHz)
                        val channelSpan = (channelConfig.endChannel - channelConfig.startChannel).coerceAtLeast(1).toFloat()
                        val pxPerChannel = chartWidth / channelSpan
                        val centerX = paddingLeft + (ap.centerChannel - channelConfig.startChannel) * pxPerChannel

                        val signalFraction = ((ap.rssi - (-95)) / 65f).coerceIn(0.08f, 1f)
                        val peakY = baselineY - (signalFraction * chartHeight)

                        val halfWidthPx = (ap.channelWidth.mhz / 10f) * pxPerChannel

                        val startX = (centerX - halfWidthPx).coerceAtLeast(paddingLeft)
                        val endX = (centerX + halfWidthPx).coerceAtMost(size.width - paddingRight)

                        val isHighlighted = ap.bssid == selectedAp?.bssid
                        val domeColor = when {
                            ap.isConnected -> SecondaryContainerEmerald
                            ap.isUserTaggedHome -> PrimaryContainerBlue
                            else -> Color(0xFF87929A)
                        }

                        // A quadratic curve only rises halfway to its control point, so the control
                        // point sits twice as high for the apex to land on the AP's RSSI
                        val controlY = 2 * peakY - baselineY

                        // Parabola Fill Path
                        val fillPath = Path().apply {
                            moveTo(startX, baselineY)
                            quadraticTo(centerX, controlY, endX, baselineY)
                            close()
                        }

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    domeColor.copy(alpha = if (isHighlighted) 0.65f else 0.35f),
                                    domeColor.copy(alpha = 0.02f)
                                )
                            )
                        )

                        // Parabola Outline Stroke Path
                        val strokePath = Path().apply {
                            moveTo(startX, baselineY)
                            quadraticTo(centerX, controlY, endX, baselineY)
                        }

                        drawPath(
                            path = strokePath,
                            color = if (isHighlighted) Color.White else domeColor,
                            style = Stroke(
                                width = if (ap.isUserTaggedHome || ap.isConnected || isHighlighted) 3.dp.toPx() else 1.5.dp.toPx(),
                                pathEffect = if (!ap.isUserTaggedHome && !ap.isConnected) PathEffect.dashPathEffect(floatArrayOf(10f, 5f)) else null
                            )
                        )

                        // Peak Indicator Dot
                        if (ap.isUserTaggedHome || ap.isConnected || isHighlighted) {
                            drawCircle(
                                color = domeColor,
                                radius = 4.dp.toPx(),
                                center = Offset(centerX, peakY)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.dp.toPx(),
                                center = Offset(centerX, peakY)
                            )

                            // Peak Label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    color = if (ap.isConnected) android.graphics.Color.parseColor("#4EDEA3")
                                    else android.graphics.Color.parseColor("#38BDF8")
                                    textSize = 9.sp.toPx()
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                val shortName = if (ap.ssid.length > 12) ap.ssid.take(11) + "…" else ap.ssid
                                drawText("$shortName (${ap.rssi})", centerX, peakY - 8.dp.toPx(), paint)
                            }
                        }
                    }

                    // 4. Draw X-Axis Channel Markers. Every marker gets a tick; a label that
                    // would run into the previous one is left out (narrow charts, Ch 149/157/165).
                    var lastLabelRight = Float.NEGATIVE_INFINITY
                    channelConfig.channelMarkers.forEach { ch ->
                        val chFrac = (ch - channelConfig.startChannel).toFloat() /
                                (channelConfig.endChannel - channelConfig.startChannel).coerceAtLeast(1)
                        val markerX = paddingLeft + (chFrac * chartWidth)

                        // Tick mark
                        drawLine(
                            color = Color(0xFF3E484F),
                            start = Offset(markerX, baselineY),
                            end = Offset(markerX, baselineY + 4.dp.toPx()),
                            strokeWidth = 1.5f
                        )

                        // Label
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = if (ch in collisionChannels) android.graphics.Color.parseColor("#F59E0B")
                                else android.graphics.Color.parseColor("#87929A")
                                textSize = 9.sp.toPx()
                                typeface = android.graphics.Typeface.MONOSPACE
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                            val label = "Ch $ch"
                            val halfWidth = paint.measureText(label) / 2
                            if (markerX - halfWidth >= lastLabelRight + 4.dp.toPx()) {
                                drawText(label, markerX, baselineY + 16.dp.toPx(), paint)
                                lastLabelRight = markerX + halfWidth
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer HUD: Overlap info & Active Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (collisionChannels.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TertiaryContainerAmber.copy(alpha = 0.15f)
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
                                text = "Collisions on Ch ${collisionChannels.joinToString(", ")}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TertiaryContainerAmber
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Airspace clear of co-channel collisions",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SecondaryContainerEmerald
                    )
                }

                Text(
                    text = "${apsInBand.size} APs in band",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

data class BandGraphConfig(
    val startChannel: Int,
    val endChannel: Int,
    val channelMarkers: List<Int>,
    val bandLabel: String
)
