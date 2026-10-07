package com.wavebalance.app.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ChannelRating(val label: String, val colorHex: Long) {
    OPTIMAL("Optimal", 0xFF10B981),      // Emerald Green
    GOOD("Good", 0xFF38BDF8),            // Cyan
    FAIR("Fair", 0xFFF59E0B),            // Amber
    CONGESTED("Congested", 0xFFEF4444)   // Red
}

data class RouterStep(
    val stepNumber: Int,
    val title: String,
    val description: String,
    val parameterHighlight: String? = null
)

data class ChannelScore(
    val channel: Int,
    val frequencyMhz: Int,
    val band: FrequencyBand,
    val isDfs: Boolean,
    val score: Int, // 0 to 100
    val rating: ChannelRating,
    val coChannelCount: Int,
    val adjacentChannelCount: Int,
    val maxInterferingRssi: Int?,
    val conflictingSsids: List<String>,
    val recommendedWidth: ChannelWidth,
    val isCurrentChannel: Boolean = false,
    val isRecommended: Boolean = false,
    val centerFrequencyMhz: Int = frequencyMhz
)

data class OptimizerRecommendation(
    val band: FrequencyBand,
    val currentChannel: Int,
    val currentScore: Int,
    val recommendedChannel: Int,
    val recommendedScore: Int,
    val recommendedBandwidth: ChannelWidth,
    val scoreDelta: Int,
    val eliminatedCollisions: Int,
    val channelScores: List<ChannelScore>,
    // Radios in this band from the user's own router or mesh, left out of the scores
    val ownRadiosIgnored: Int,
    val stepByStepGuide: List<RouterStep>,
    val routerDirectivesText: String,
    val reasonSummary: String,
    val recommendedCenterFrequencyMhz: Int = FrequencyBand.channelToFrequency(recommendedChannel, band),
    val currentCenterFrequencyMhz: Int? = null,
    val currentBandwidth: ChannelWidth = recommendedBandwidth,
    val currentChannelEvaluated: Boolean = true
)

object ChannelOptimizerEngine {

    fun isDfsChannel(channel: Int, band: FrequencyBand): Boolean {
        return band == FrequencyBand.BAND_5_GHZ && channel in 52..144
    }

    /**
     * Evaluate all candidate channels for a given frequency band
     */
    fun evaluateBand(
        band: FrequencyBand,
        allAps: List<AccessPoint>,
        currentChannel: Int,
        targetWidth: ChannelWidth = ChannelWidth.defaultForBand(band),
        // Lowercase BSSIDs from NetworkGroups.ownNetwork(). The user's own radios move with
        // the channel change, so they never count against a candidate channel.
        ownNetworkBssids: Set<String> = emptySet()
    ): OptimizerRecommendation {
        val effectiveBand = if (band == FrequencyBand.UNKNOWN) FrequencyBand.BAND_5_GHZ else band
        val width = targetWidth.takeIf { it in ChannelWidth.supportedForBand(effectiveBand) }
            ?: ChannelWidth.defaultForBand(effectiveBand)
        val (ownAps, apsInBand) = allAps.filter { it.band == effectiveBand }
            .partition { it.bssid.lowercase() in ownNetworkBssids }
        val currentBlocks = ChannelPlan.blocksForPrimary(currentChannel, effectiveBand, width)
        val candidates = (ChannelPlan.candidates(effectiveBand, width) + currentBlocks).distinct()
        val scoredChannels = candidates.map { evaluateChannel(it, apsInBand, currentChannel) }
        val connectedAp = allAps.firstOrNull {
            it.isConnected && it.band == effectiveBand && it.channel == currentChannel
        }
        val currentWidth = connectedAp?.channelWidth?.takeIf { it in ChannelWidth.supportedForBand(effectiveBand) } ?: width
        val currentOptions = ChannelPlan.blocksForPrimary(currentChannel, effectiveBand, currentWidth)
            .map { evaluateChannel(it, apsInBand, currentChannel) }
        // Preserve the measured width and center; don't assume the cleanest unobserved placement.
        val currentScored = currentOptions.firstOrNull { it.centerFrequencyMhz == connectedAp?.centerFrequencyMhz }
            ?: currentOptions.minByOrNull { it.score }
            ?: ChannelPlan.blocksForPrimary(currentChannel, effectiveBand, ChannelWidth.WIDTH_20)
                .firstOrNull()?.let { evaluateChannel(it, apsInBand, currentChannel) }
        val bestCandidate = scoredChannels.sortedWith(
            compareByDescending<ChannelScore> { it.score }
                .thenBy { it.isDfs }
                .thenBy { if (it.channel == currentScored?.channel && it.centerFrequencyMhz == currentScored.centerFrequencyMhz) 0 else 1 }
        ).first()

        // Mark the recommended channel
        val finalChannelScores = scoredChannels.map { cs ->
            cs.copy(
                isRecommended = cs.channel == bestCandidate.channel && cs.centerFrequencyMhz == bestCandidate.centerFrequencyMhz,
                isCurrentChannel = cs.channel == currentScored?.channel && cs.centerFrequencyMhz == currentScored.centerFrequencyMhz && cs.recommendedWidth == currentScored.recommendedWidth
            )
        }

        val scoreDelta = currentScored?.let { max(0, bestCandidate.score - it.score) } ?: 0
        val eliminatedCollisions = currentScored?.let { max(0, it.coChannelCount + it.adjacentChannelCount - bestCandidate.coChannelCount - bestCandidate.adjacentChannelCount) } ?: 0

        val stepByStep = buildStepByStepGuide(
            band = effectiveBand,
            currentChannel = currentChannel,
            recommendedChannel = bestCandidate.channel,
            bandwidth = width,
            isDfs = bestCandidate.isDfs,
            centerFrequencyMhz = bestCandidate.centerFrequencyMhz
        )

        val directivesText = buildClipboardDirectives(
            band = effectiveBand,
            currentChannel = currentChannel,
            recommendedChannel = bestCandidate.channel,
            bandwidth = width,
            currentScore = currentScored?.score,
            recommendedScore = bestCandidate.score,
            isDfs = bestCandidate.isDfs,
            centerFrequencyMhz = bestCandidate.centerFrequencyMhz
        )

        val reason = when {
            currentScored == null -> "The current channel could not be evaluated. Compare the supported candidate blocks below."
            bestCandidate.channel == currentChannel && bestCandidate.centerFrequencyMhz == currentScored.centerFrequencyMhz && width == currentScored.recommendedWidth && currentScored.score >= 90 ->
                "Your current Channel $currentChannel is already optimal with minimal spectral congestion."
            scoreDelta >= 25 ->
                "Shifting to Channel ${bestCandidate.channel} avoids $eliminatedCollisions conflicting networks, boosting signal clarity by +$scoreDelta pts."
            scoreDelta > 0 ->
                "Channel ${bestCandidate.channel} provides cleaner RF headroom with less airtime contention."
            else ->
                "Airspace is balanced. Channel ${bestCandidate.channel} offers the cleanest signal propagation."
        }

        return OptimizerRecommendation(
            band = effectiveBand,
            currentChannel = currentChannel,
            currentScore = currentScored?.score ?: 0,
            recommendedChannel = bestCandidate.channel,
            recommendedScore = bestCandidate.score,
            recommendedBandwidth = width,
            scoreDelta = scoreDelta,
            eliminatedCollisions = eliminatedCollisions,
            channelScores = finalChannelScores,
            ownRadiosIgnored = ownAps.size,
            stepByStepGuide = stepByStep,
            routerDirectivesText = directivesText,
            reasonSummary = reason,
            recommendedCenterFrequencyMhz = bestCandidate.centerFrequencyMhz,
            currentCenterFrequencyMhz = currentScored?.centerFrequencyMhz,
            currentBandwidth = currentScored?.recommendedWidth ?: width,
            currentChannelEvaluated = currentScored != null
        )
    }

    /**
     * Compute RF congestion score for a specific channel
     */
    private fun evaluateChannel(
        block: ChannelBlock,
        apsInBand: List<AccessPoint>,
        currentChannel: Int
    ): ChannelScore {
        val channel = block.primaryChannel
        val band = block.band
        val targetWidth = block.width
        val freqMhz = block.primaryFrequencyMhz
        val candidateEnv = ApMetricsCalculator.getFrequencyEnvelope(block.centerFrequencyMhz, targetWidth)

        var penaltyTotal = 0.0
        var coChannelCount = 0
        var adjacentCount = 0
        val conflictingSsids = mutableListOf<String>()
        var maxRssi: Int? = null

        for (ap in apsInBand) {
            // Ignore AP if it's the current user device's connection (so the user doesn't penalize themselves)
            if (ap.isConnected && ap.channel == currentChannel) continue

            // Signal power factor above -90 dBm noise floor
            val powerFactor = max(0, ap.rssi - (-90)).toDouble()

            if (ap.channel == channel) {
                // Co-channel interference: directly shares channel airtime and CSMA/CA clear channel assessment
                coChannelCount++
                val penalty = powerFactor * 2.2
                penaltyTotal += penalty
                conflictingSsids.add(ap.ssid.ifBlank { "Hidden Network" })
                maxRssi = maxOf(maxRssi ?: -120, ap.rssi)
            } else {
                // Check envelope overlap for adjacent interference
                val apEnv = ApMetricsCalculator.getFrequencyEnvelope(ap.centerFrequencyMhz, ap.channelWidth)
                val overlapStart = max(candidateEnv.startMhz, apEnv.startMhz)
                val overlapEnd = min(candidateEnv.endMhz, apEnv.endMhz)

                if (overlapStart < overlapEnd) {
                    val overlapMhz = overlapEnd - overlapStart
                    adjacentCount++
                    // Adjacent channel bleed doesn't coordinate CSMA/CA frames, causing destructive CRC checksum corruptions
                    val overlapRatio = overlapMhz.toDouble() / targetWidth.mhz.toDouble()
                    val penalty = powerFactor * 1.6 * overlapRatio
                    penaltyTotal += penalty
                    conflictingSsids.add(ap.ssid.ifBlank { "Hidden Network" })
                    maxRssi = maxOf(maxRssi ?: -120, ap.rssi)
                }
            }
        }

        val isDfs = block.isDfs
        // Minor penalty for DFS channels if non-DFS is equally clean (DFS requires channel evacuation on radar detection)
        if (isDfs) {
            penaltyTotal += 4.0
        }

        val finalScore = max(5, min(100, (100.0 - penaltyTotal).roundToInt()))
        val rating = when {
            finalScore >= 85 -> ChannelRating.OPTIMAL
            finalScore >= 70 -> ChannelRating.GOOD
            finalScore >= 50 -> ChannelRating.FAIR
            else -> ChannelRating.CONGESTED
        }

        return ChannelScore(
            channel = channel,
            frequencyMhz = freqMhz,
            band = band,
            isDfs = isDfs,
            score = finalScore,
            rating = rating,
            coChannelCount = coChannelCount,
            adjacentChannelCount = adjacentCount,
            maxInterferingRssi = maxRssi,
            conflictingSsids = conflictingSsids.distinct().take(4),
            recommendedWidth = targetWidth,
            isCurrentChannel = channel == currentChannel,
            isRecommended = false,
            centerFrequencyMhz = block.centerFrequencyMhz
        )
    }

    private fun buildStepByStepGuide(
        band: FrequencyBand,
        currentChannel: Int,
        recommendedChannel: Int,
        bandwidth: ChannelWidth,
        isDfs: Boolean,
        centerFrequencyMhz: Int
    ): List<RouterStep> {
        val bandLabel = when (band) {
            FrequencyBand.BAND_2_4_GHZ -> "2.4 GHz"
            FrequencyBand.BAND_5_GHZ -> "5 GHz"
            FrequencyBand.BAND_6_GHZ -> "6 GHz"
            FrequencyBand.UNKNOWN -> "5 GHz"
        }

        val steps = mutableListOf(
            RouterStep(
                stepNumber = 1,
                title = "Access Router Admin Portal",
                description = "Open your web browser and navigate to your router's gateway IP (commonly http://192.168.1.1, http://192.168.0.1, or http://192.168.50.1) and log in with your admin credentials.",
                parameterHighlight = "http://192.168.1.1"
            ),
            RouterStep(
                stepNumber = 2,
                title = "Navigate to $bandLabel Radio Settings",
                description = "Select 'Wireless Settings' or 'Advanced Wi-Fi', then choose the '$bandLabel Band' configuration tab.",
                parameterHighlight = "$bandLabel Radio Settings"
            ),
            RouterStep(
                stepNumber = 3,
                title = "Change Control Channel",
                description = "Set 'Channel' or 'Control Channel' to Channel $recommendedChannel. Confirm the candidate is supported by your router and country before selecting it.",
                parameterHighlight = "Channel $recommendedChannel"
            ),
            RouterStep(
                stepNumber = 4,
                title = "Configure Channel Bandwidth",
                description = "Set bandwidth to ${bandwidth.label} and center frequency to $centerFrequencyMhz MHz (center channel ${FrequencyBand.frequencyToChannel(centerFrequencyMhz)}). ${if (bandwidth == ChannelWidth.WIDTH_40) "Select the secondary channel " + (if (centerFrequencyMhz > FrequencyBand.channelToFrequency(recommendedChannel, band)) "above" else "below") + " the primary. " else ""} Verify this combination is available on your router.",
                parameterHighlight = bandwidth.label
            )
        )

        if (isDfs) {
            steps.add(
                RouterStep(
                    stepNumber = 5,
                    title = "DFS Radar Scan Notice",
                    description = "This bonded block includes DFS spectrum. Your router may require a channel-availability check and must vacate it if radar is detected; timing depends on region and channel.",
                    parameterHighlight = "DFS Radar CAC"
                )
            )
        }

        steps.add(
            RouterStep(
                stepNumber = if (isDfs) 6 else 5,
                title = "Apply & Reboot",
                description = "Click 'Apply' or 'Save Settings'. Your router will restart the $bandLabel radio. Reconnect and scan again to verify the result; neighbor activity can change.",
                parameterHighlight = "Save & Apply"
            )
        )

        return steps
    }

    private fun buildClipboardDirectives(
        band: FrequencyBand,
        currentChannel: Int,
        recommendedChannel: Int,
        bandwidth: ChannelWidth,
        currentScore: Int?,
        recommendedScore: Int,
        isDfs: Boolean,
        centerFrequencyMhz: Int
    ): String {
        val bandLabel = when (band) {
            FrequencyBand.BAND_2_4_GHZ -> "2.4 GHz"
            FrequencyBand.BAND_5_GHZ -> "5 GHz"
            FrequencyBand.BAND_6_GHZ -> "6 GHz"
            FrequencyBand.UNKNOWN -> "5 GHz"
        }

        return """
            =========================================
            WAVEBALANCE WI-FI OPTIMIZATION DIRECTIVES
            =========================================
            Target Radio: $bandLabel Wireless Network
            Current Channel: ${if (currentScore != null) "Ch $currentChannel (RF Health Score: $currentScore/100)" else "Not evaluated"}
            RECOMMENDED CHANNEL: Ch $recommendedChannel (RF Health Score: $recommendedScore/100)
            Recommended Bandwidth: ${bandwidth.label}
            Center Frequency: $centerFrequencyMhz MHz (center channel ${FrequencyBand.frequencyToChannel(centerFrequencyMhz)})
            ${if (bandwidth == ChannelWidth.WIDTH_40) "Secondary Channel: " + if (centerFrequencyMhz > FrequencyBand.channelToFrequency(recommendedChannel, band)) "Above" else "Below" else ""}
            Availability: Verify this channel/width in your router and country.
            Spectrum Type: ${if (isDfs) "UNII-2 DFS (Radar Detection Active)" else "Non-DFS candidate (verify router and regional support)"}
            
            ROUTER ACTION STEPS:
            1. Open Router Admin: http://192.168.1.1 (or router gateway)
            2. Go to: Wireless / Wi-Fi Settings -> $bandLabel Radio
            3. Set Channel: $recommendedChannel
            4. Set Channel Width: ${bandwidth.label}
            5. Save & Reboot Router
            =========================================
        """.trimIndent()
    }
}
