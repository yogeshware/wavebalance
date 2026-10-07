package com.wavebalance.app.model

/** Refresh connection badges independently of Android's throttled scan results. */
internal fun List<AccessPoint>.withConnectedBssid(activeBssid: String?): List<AccessPoint> = map { ap ->
    ap.copy(isConnected = !activeBssid.isNullOrBlank() && ap.bssid.equals(activeBssid, ignoreCase = true))
}

data class AccessPoint(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int = FrequencyBand.frequencyToChannel(frequencyMhz),
    val band: FrequencyBand = FrequencyBand.fromFrequency(frequencyMhz),
    val standard: WifiStandard = WifiStandard.UNKNOWN,
    val channelWidth: ChannelWidth = ChannelWidth.WIDTH_20,
    // Centre of the whole (possibly bonded) channel, from ScanResult.centerFreq0.
    // For 40/80/160 MHz channels this differs from the primary channel frequency.
    val centerFrequencyMhz: Int = frequencyMhz,
    val capabilities: String = "",
    val securityType: String = parseSecurity(capabilities),
    // Parsed from the AP's information elements; null before Android 11
    val advertised: ApCapabilities? = null,
    val isConnected: Boolean = false,
    val isUserTaggedHome: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = if (ssid.isBlank() || ssid == "<unknown ssid>") "Hidden Network ($bssid)" else ssid

    /**
     * Normalized 0..100% signal rating
     */
    val signalPercent: Int
        get() {
            // Typical Wi-Fi RSSI bounds: -100 dBm (0%) to -50 dBm (100%)
            return when {
                rssi >= -50 -> 100
                rssi <= -100 -> 0
                else -> 2 * (rssi + 100)
            }.coerceIn(0, 100)
        }

    /**
     * Channel number at the centre of the occupied spectrum, fractional for
     * bonded channels (e.g. 159.0 for a 40 MHz channel on 157 + 161).
     */
    val centerChannel: Float
        get() = FrequencyBand.frequencyToChannelPosition(centerFrequencyMhz, band)

    companion object {
        /**
         * Maps Android's capability string (e.g. "[RSN-PSK+SAE-CCMP][ESS]") to a security label.
         */
        fun parseSecurity(caps: String): String {
            val hasSae = caps.contains("SAE")
            val hasPsk = caps.contains("PSK")
            return when {
                caps.contains("EAP") -> if (caps.contains("SUITE_B")) "WPA3 Enterprise" else "WPA2 Enterprise"
                hasSae && hasPsk -> "WPA2/WPA3 Personal"
                hasSae -> "WPA3 Personal"
                hasPsk && caps.contains("[WPA-") && (caps.contains("WPA2") || caps.contains("RSN")) -> "WPA/WPA2 Personal"
                hasPsk && (caps.contains("WPA2") || caps.contains("RSN")) -> "WPA2 Personal"
                hasPsk -> "WPA Personal"
                caps.contains("OWE") -> "OWE (Enhanced Open)"
                caps.contains("WEP") -> "WEP"
                else -> "Open"
            }
        }
    }
}
