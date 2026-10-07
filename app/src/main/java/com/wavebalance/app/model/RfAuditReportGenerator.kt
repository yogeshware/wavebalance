package com.wavebalance.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RfAuditReportGenerator {

    /**
     * Generates a comprehensive, formatted Markdown RF Airspace Audit Report
     */
    fun generateMarkdownReport(
        activeConnection: ActiveConnectionInfo?,
        allAps: List<AccessPoint>,
        recommendation: OptimizerRecommendation?
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val timestamp = dateFormat.format(Date())

        val totalAps = allAps.size
        val aps24 = allAps.filter { it.band == FrequencyBand.BAND_2_4_GHZ }
        val aps5 = allAps.filter { it.band == FrequencyBand.BAND_5_GHZ }
        val aps6 = allAps.filter { it.band == FrequencyBand.BAND_6_GHZ }

        val activeBssid = activeConnection?.bssid ?: "N/A"
        val activeSsid = activeConnection?.cleanSsid ?: "Disconnected"
        val activeRssi = activeConnection?.rssi?.let { "$it dBm" } ?: "N/A"
        val activeChannel = activeConnection?.channel?.let { "Ch $it" } ?: "N/A"
        val activeBand = activeConnection?.band?.label ?: "N/A"
        val activeStandard = activeConnection?.standard?.let { "${it.generation} (${it.label})" } ?: "Unknown"
        val activeSpeed = activeConnection?.linkSpeedMbps?.let { "$it Mbps" } ?: "N/A"
        val activeVendor = if (activeConnection != null) WifiVendorLookup.getVendor(activeConnection.bssid) else "N/A"

        val sb = StringBuilder()
        sb.appendLine("# 📡 WaveBalance RF Airspace Audit Report")
        sb.appendLine()
        sb.appendLine("**Generated**: $timestamp  ")
        sb.appendLine("**Audit Engine**: WaveBalance v1.0.0 (Android Adaptive Utility)")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // Section 1: Active Connection Status
        sb.appendLine("## 1. Active Wireless Link Telemetry")
        sb.appendLine()
        sb.appendLine("| Metric | Value | Diagnostic Assessment |")
        sb.appendLine("|:---|:---|:---|")
        sb.appendLine("| **SSID** | `$activeSsid` | Primary connected network |")
        sb.appendLine("| **BSSID (MAC)** | `$activeBssid` | Access Point hardware identity |")
        sb.appendLine("| **Hardware Vendor** | $activeVendor | IEEE OUI classification |")
        sb.appendLine("| **Signal Power (RSSI)** | $activeRssi | ${assessRssi(activeConnection?.rssi)} |")
        sb.appendLine("| **Operating Frequency** | $activeBand ($activeChannel) | RF carrier wave allocation |")
        sb.appendLine("| **Protocol Standard** | $activeStandard | 802.11 physical layer framing |")
        sb.appendLine("| **Current Link Speed** | $activeSpeed | Negotiated PHY transmission ceiling |")
        sb.appendLine()

        // Section 2: Environment Airspace Density
        sb.appendLine("## 2. Spectrum Density & Congestion Overview")
        sb.appendLine()
        sb.appendLine("A total of **$totalAps Access Points** were detected across all frequency spectrums:")
        sb.appendLine()
        sb.appendLine("- **2.4 GHz Band**: ${aps24.size} APs (High range, prone to Bluetooth & legacy interference)")
        sb.appendLine("- **5 GHz Band**: ${aps5.size} APs (High throughput, wide 80/160 MHz channels)")
        sb.appendLine("- **6 GHz Band**: ${aps6.size} APs (Pristine spectrum, Wi-Fi 6E/7 PSC channels)")
        sb.appendLine()

        // Section 3: Channel Optimization & Directives
        sb.appendLine("## 3. Channel Optimizer & Headroom Analysis")
        sb.appendLine()
        if (recommendation != null) {
            sb.appendLine("- **Target Band**: ${recommendation.band.label}")
            sb.appendLine("- **Current Channel**: ${if (recommendation.currentChannelEvaluated) "Channel ${recommendation.currentChannel} (Health Score: ${recommendation.currentScore}/100)" else "Not evaluated"}")
            sb.appendLine("- **Calculated Optimal Channel**: Channel ${recommendation.recommendedChannel} (Health Score: ${recommendation.recommendedScore}/100)")
            sb.appendLine("- **Clarity Headroom Gain**: +${recommendation.scoreDelta} pts")
            sb.appendLine("- **Bandwidth Configuration**: ${recommendation.recommendedBandwidth.label}")
            sb.appendLine("- **Bonded Center**: ${recommendation.recommendedCenterFrequencyMhz} MHz (channel ${FrequencyBand.frequencyToChannel(recommendation.recommendedCenterFrequencyMhz)})")
            sb.appendLine("- **Diagnosis**: ${recommendation.reasonSummary}")
            sb.appendLine()
            sb.appendLine("### Actionable Router Directives:")
            sb.appendLine("```text")
            sb.appendLine(recommendation.routerDirectivesText)
            sb.appendLine("```")
        } else {
            sb.appendLine("No optimization recommendation available. Run an airspace scan to calculate optimal frequency allocation.")
        }
        sb.appendLine()

        // Section 4: Detected Access Point Inventory
        sb.appendLine("## 4. Detected Access Point Inventory")
        sb.appendLine()
        sb.appendLine("| SSID | Channel | Band | RSSI | Vendor | Security | Standard |")
        sb.appendLine("|:---|:---:|:---:|:---:|:---|:---|:---:|")
        allAps.sortedByDescending { it.rssi }.forEach { ap ->
            val vendor = WifiVendorLookup.getVendor(ap.bssid)
            val currentBadge = if (ap.isConnected) " *(Connected)*" else ""
            sb.appendLine("| `${ap.displayName}`$currentBadge | ${ap.channel} | ${ap.band.label} | ${ap.rssi} dBm | $vendor | ${ap.securityType} | ${ap.standard.generation} (${ap.standard.label}) |")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("*Generated automatically by WaveBalance Network Analyzer.*")

        return sb.toString()
    }

    private fun assessRssi(rssi: Int?): String {
        return when {
            rssi == null -> "No active connection"
            rssi >= -50 -> "Excellent (-50 dBm or higher, minimal path loss)"
            rssi >= -65 -> "Good (Optimal for 4K streaming, VoIP & Gaming)"
            rssi >= -75 -> "Fair (Usable, but susceptible to packet jitter)"
            rssi >= -85 -> "Poor (High frame retransmissions, sticky client risk)"
            else -> "Degraded (Link near disconnection threshold)"
        }
    }
}
