package com.wavebalance.app.model

data class RoamingEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val fromBssid: String,
    val toBssid: String,
    val fromChannel: Int,
    val toChannel: Int,
    val fromBand: FrequencyBand,
    val toBand: FrequencyBand,
    val fromRssi: Int,
    val toRssi: Int,
    val deltaRssiDb: Int = toRssi - fromRssi
)

data class StickyClientAlert(
    val isSticky: Boolean,
    val currentBssid: String,
    val currentRssi: Int,
    val candidateBssid: String,
    val candidateRssi: Int,
    val candidateChannel: Int,
    val candidateBand: FrequencyBand,
    val deltaRssiDb: Int,
    val recommendationText: String
)

object RoamingMonitorEngine {

    /**
     * Evaluates whether the device is suffering from Sticky Client Syndrome
     * (connected to a distant weak AP when a much stronger mesh node on the same SSID is in range)
     */
    fun evaluateStickyClient(
        activeConnection: ActiveConnectionInfo?,
        allAps: List<AccessPoint>
    ): StickyClientAlert? {
        if (activeConnection == null) return null

        val currentRssi = activeConnection.rssi
        // Only evaluate if current signal is degraded or fair (<= -74 dBm)
        if (currentRssi > -74) return null

        val cleanSsid = activeConnection.cleanSsid
        if (cleanSsid.isBlank() || cleanSsid == "<unknown ssid>") return null

        // Find candidate APs on the EXACT same SSID but different BSSID
        val candidate = allAps
            .filter {
                it.ssid == cleanSsid &&
                        !it.bssid.equals(activeConnection.bssid, ignoreCase = true)
            }
            .maxByOrNull { it.rssi } ?: return null

        val delta = candidate.rssi - currentRssi

        // If candidate is at least 10 dB stronger and >= -68 dBm, flag sticky client
        if (delta >= 10 && candidate.rssi >= -68) {
            val vendor = WifiVendorLookup.getVendor(candidate.bssid)
            return StickyClientAlert(
                isSticky = true,
                currentBssid = activeConnection.bssid,
                currentRssi = currentRssi,
                candidateBssid = candidate.bssid,
                candidateRssi = candidate.rssi,
                candidateChannel = candidate.channel,
                candidateBand = candidate.band,
                deltaRssiDb = delta,
                recommendationText = "Phone is holding onto distant node (${activeConnection.bssid.takeLast(8)}, $currentRssi dBm). Stronger mesh node (${candidate.bssid.takeLast(8)}, ${candidate.rssi} dBm on Ch ${candidate.channel}) is available with +$delta dB headroom. Consider toggling Wi-Fi to force 802.11k/v roaming."
            )
        }

        return null
    }

    /**
     * Creates a RoamingEvent if active BSSID has changed compared to last known connection
     */
    fun detectRoamingTransition(
        previousConn: ActiveConnectionInfo?,
        currentConn: ActiveConnectionInfo?
    ): RoamingEvent? {
        if (previousConn == null || currentConn == null) return null
        if (previousConn.bssid.isBlank() || currentConn.bssid.isBlank()) return null
        val ssid = previousConn.cleanSsid
        if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid != currentConn.cleanSsid) return null

        // Only a BSSID change within the same, case-sensitive SSID is a roam.
        if (!previousConn.bssid.equals(currentConn.bssid, ignoreCase = true)) {
            return RoamingEvent(
                fromBssid = previousConn.bssid,
                toBssid = currentConn.bssid,
                fromChannel = previousConn.channel,
                toChannel = currentConn.channel,
                fromBand = previousConn.band,
                toBand = currentConn.band,
                fromRssi = previousConn.rssi,
                toRssi = currentConn.rssi
            )
        }
        return null
    }
}
