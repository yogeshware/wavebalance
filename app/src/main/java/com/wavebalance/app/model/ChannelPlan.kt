package com.wavebalance.app.model

/** One contiguous channel configuration. Primary and bonded center are distinct. */
data class ChannelBlock(
    val primaryChannel: Int,
    val band: FrequencyBand,
    val width: ChannelWidth,
    val centerFrequencyMhz: Int
) {
    val primaryFrequencyMhz: Int get() = FrequencyBand.channelToFrequency(primaryChannel, band)
    val centerChannel: Int get() = FrequencyBand.frequencyToChannel(centerFrequencyMhz)
    val componentChannels: List<Int> get() =
        (centerFrequencyMhz - width.mhz / 2 + 10..centerFrequencyMhz + width.mhz / 2 - 10 step 20)
            .map(FrequencyBand::frequencyToChannel)
    val isDfs: Boolean get() = band == FrequencyBand.BAND_5_GHZ && componentChannels.any { it in 52..144 }
}

/**
 * Contiguous channel geometry, not a country's regulatory permission list.
 * Keeps the existing 5 GHz catalog; does not invent wider blocks across its gaps.
 * 6 GHz blocks start at 5945 MHz, with a 160 MHz step for overlapping 320 MHz blocks.
 * Reference: Linux cfg80211_valid_center_freq (net/wireless/chan.c).
 */
object ChannelPlan {
    private val channels5 = (36..64 step 4).toList() + (100..144 step 4) + (149..165 step 4)

    fun blocksForPrimary(channel: Int, band: FrequencyBand, width: ChannelWidth): List<ChannelBlock> {
        val primaries = when (band) {
            FrequencyBand.BAND_2_4_GHZ -> (1..11).toList()
            FrequencyBand.BAND_5_GHZ -> channels5
            FrequencyBand.BAND_6_GHZ -> (1..233 step 4).toList()
            FrequencyBand.UNKNOWN -> return emptyList()
        }
        if (channel !in primaries || width !in ChannelWidth.supportedForBand(band)) return emptyList()
        val frequency = FrequencyBand.channelToFrequency(channel, band)
        val centers = if (width == ChannelWidth.WIDTH_20) listOf(frequency) else when (band) {
            FrequencyBand.BAND_2_4_GHZ -> listOf(frequency - 10, frequency + 10)
            FrequencyBand.BAND_5_GHZ -> when (width) {
                ChannelWidth.WIDTH_40 -> listOf(38, 46, 54, 62, 102, 110, 118, 126, 134, 142, 151, 159)
                ChannelWidth.WIDTH_80 -> listOf(42, 58, 106, 122, 138, 155)
                ChannelWidth.WIDTH_160 -> listOf(50, 114)
                else -> emptyList()
            }.map { FrequencyBand.channelToFrequency(it, band) }
            FrequencyBand.BAND_6_GHZ -> {
                val step = if (width == ChannelWidth.WIDTH_320) 160 else width.mhz
                (5945 + width.mhz / 2..7125 - width.mhz / 2 step step).toList()
            }
            FrequencyBand.UNKNOWN -> emptyList()
        }
        return centers.map { ChannelBlock(channel, band, width, it) }.filter {
            channel in it.componentChannels && it.componentChannels.all(primaries::contains)
        }
    }

    fun candidates(band: FrequencyBand, width: ChannelWidth): List<ChannelBlock> {
        val primaries = when (band) {
            FrequencyBand.BAND_2_4_GHZ -> listOf(1, 6, 11)
            FrequencyBand.BAND_5_GHZ -> channels5
            // Preferred scanning channels. Non-PSC current channels are evaluated separately.
            FrequencyBand.BAND_6_GHZ -> (5..229 step 16).toList()
            FrequencyBand.UNKNOWN -> emptyList()
        }
        return primaries.flatMap { blocksForPrimary(it, band, width) }
    }
}
