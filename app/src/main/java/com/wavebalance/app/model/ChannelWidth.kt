package com.wavebalance.app.model

enum class ChannelWidth(val mhz: Int, val label: String) {
    WIDTH_20(20, "20 MHz"),
    WIDTH_40(40, "40 MHz"),
    WIDTH_80(80, "80 MHz"),
    WIDTH_160(160, "160 MHz"),
    WIDTH_320(320, "320 MHz"),
    // mhz stays 20 so drawing code has a sensible minimum width
    UNKNOWN(20, "Unknown");

    companion object {
        fun supportedForBand(band: FrequencyBand): List<ChannelWidth> = when (band) {
            FrequencyBand.BAND_2_4_GHZ -> listOf(WIDTH_20, WIDTH_40)
            FrequencyBand.BAND_5_GHZ -> listOf(WIDTH_20, WIDTH_40, WIDTH_80, WIDTH_160)
            FrequencyBand.BAND_6_GHZ -> listOf(WIDTH_20, WIDTH_40, WIDTH_80, WIDTH_160, WIDTH_320)
            FrequencyBand.UNKNOWN -> listOf(WIDTH_20, WIDTH_40, WIDTH_80)
        }

        fun defaultForBand(band: FrequencyBand): ChannelWidth =
            if (band == FrequencyBand.BAND_2_4_GHZ) WIDTH_20 else WIDTH_80

        fun fromScanResult(widthInt: Int): ChannelWidth {
            return when (widthInt) {
                0 -> WIDTH_20
                1 -> WIDTH_40
                2 -> WIDTH_80
                3 -> WIDTH_160
                4 -> WIDTH_80 // 80+80
                5 -> WIDTH_320
                else -> UNKNOWN
            }
        }
    }
}
