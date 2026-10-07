package com.wavebalance.app

import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ActiveConnectionInfo
import com.wavebalance.app.model.ChannelWidth
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.RoamingMonitorEngine
import com.wavebalance.app.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamingMonitorEngineTest {

    private fun connection(ssid: String = "Office_Mesh") = ActiveConnectionInfo(
        ssid = ssid,
        bssid = "00:1A:2B:3C:4D:01",
        rssi = -80,
        frequencyMhz = 5180,
        linkSpeedMbps = 50
    )

    @Test
    fun stickyClient_doesNotRecommendAnSsidWithDifferentCase() {
        val neighbor = AccessPoint(
            bssid = "C4:41:1E:00:11:22",
            ssid = "office_mesh",
            rssi = -45,
            frequencyMhz = 5745
        )
        assertNull(RoamingMonitorEngine.evaluateStickyClient(connection(), listOf(neighbor)))
    }

    @Test
    fun networkChanges_areNotRoamingEvents() {
        val previous = connection()
        for (ssid in listOf("Neighbor", "office_mesh", "", "<unknown ssid>")) {
            val current = previous.copy(ssid = ssid, bssid = "C4:41:1E:00:11:22")
            assertNull(RoamingMonitorEngine.detectRoamingTransition(previous, current))
        }
    }

    @Test
    fun unknownNetworkNames_doNotEstablishRoamingIdentity() {
        for (ssid in listOf("", "<unknown ssid>")) {
            val previous = connection(ssid)
            assertNull(RoamingMonitorEngine.detectRoamingTransition(
                previous, previous.copy(bssid = "C4:41:1E:00:11:22")
            ))
        }
    }

    @Test
    fun quotedSsidAndBssidCase_areNormalizedForRoaming() {
        val previous = connection("\"Office_Mesh\"")
        assertNotNull(RoamingMonitorEngine.detectRoamingTransition(
            previous, previous.copy(ssid = "Office_Mesh", bssid = "00:1a:2b:3c:4d:02")
        ))
        assertNull(RoamingMonitorEngine.detectRoamingTransition(
            previous, previous.copy(ssid = "Office_Mesh", bssid = previous.bssid.lowercase())
        ))
    }

    @Test
    fun testStickyClientDetection_Triggered() {
        val activeConn = ActiveConnectionInfo(
            ssid = "\"Office_Mesh\"",
            bssid = "00:1A:2B:3C:4D:01",
            rssi = -79, // Degraded signal
            frequencyMhz = 5180,
            linkSpeedMbps = 150,
            standard = WifiStandard.WIFI_6,
            channel = 36,
            band = FrequencyBand.BAND_5_GHZ
        )

        val aps = listOf(
            AccessPoint(
                bssid = "00:1A:2B:3C:4D:01",
                ssid = "Office_Mesh",
                rssi = -79,
                frequencyMhz = 5180,
                channel = 36,
                band = FrequencyBand.BAND_5_GHZ,
                standard = WifiStandard.WIFI_6,
                channelWidth = ChannelWidth.WIDTH_80,
                capabilities = "[WPA3-SAE-CCMP]",
                isConnected = true
            ),
            AccessPoint(
                bssid = "00:1A:2B:3C:4D:02",
                ssid = "Office_Mesh",
                rssi = -52, // Strong candidate: +27 dB improvement!
                frequencyMhz = 5745,
                channel = 149,
                band = FrequencyBand.BAND_5_GHZ,
                standard = WifiStandard.WIFI_6,
                channelWidth = ChannelWidth.WIDTH_80,
                capabilities = "[WPA3-SAE-CCMP]",
                isConnected = false
            )
        )

        val alert = RoamingMonitorEngine.evaluateStickyClient(activeConn, aps)
        assertNotNull(alert)
        assertTrue(alert!!.isSticky)
        assertEquals("00:1A:2B:3C:4D:01", alert.currentBssid)
        assertEquals(-79, alert.currentRssi)
        assertEquals("00:1A:2B:3C:4D:02", alert.candidateBssid)
        assertEquals(-52, alert.candidateRssi)
        assertEquals(27, alert.deltaRssiDb)
        assertEquals(149, alert.candidateChannel)
        assertTrue(alert.recommendationText.contains("+27 dB headroom"))
    }

    @Test
    fun testStickyClientDetection_NotTriggeredWhenSignalGood() {
        val activeConn = ActiveConnectionInfo(
            ssid = "\"Office_Mesh\"",
            bssid = "00:1A:2B:3C:4D:01",
            rssi = -62, // Strong signal, not sticky
            frequencyMhz = 5180,
            linkSpeedMbps = 866,
            standard = WifiStandard.WIFI_6,
            channel = 36,
            band = FrequencyBand.BAND_5_GHZ
        )

        val aps = listOf(
            AccessPoint(
                bssid = "00:1A:2B:3C:4D:02",
                ssid = "Office_Mesh",
                rssi = -50,
                frequencyMhz = 5745,
                channel = 149,
                band = FrequencyBand.BAND_5_GHZ,
                standard = WifiStandard.WIFI_6,
                channelWidth = ChannelWidth.WIDTH_80,
                capabilities = "[WPA3-SAE-CCMP]",
                isConnected = false
            )
        )

        val alert = RoamingMonitorEngine.evaluateStickyClient(activeConn, aps)
        assertNull(alert)
    }

    @Test
    fun testStickyClientDetection_NotTriggeredForDifferentSsid() {
        val activeConn = ActiveConnectionInfo(
            ssid = "\"Office_Mesh\"",
            bssid = "00:1A:2B:3C:4D:01",
            rssi = -80,
            frequencyMhz = 5180,
            linkSpeedMbps = 50,
            standard = WifiStandard.WIFI_6,
            channel = 36,
            band = FrequencyBand.BAND_5_GHZ
        )

        // Strong candidate, but belongs to Neighbor_Network
        val aps = listOf(
            AccessPoint(
                bssid = "C4:41:1E:00:11:22",
                ssid = "Neighbor_Network",
                rssi = -45,
                frequencyMhz = 5745,
                channel = 149,
                band = FrequencyBand.BAND_5_GHZ,
                standard = WifiStandard.WIFI_6,
                channelWidth = ChannelWidth.WIDTH_80,
                capabilities = "[WPA2-PSK-CCMP]",
                isConnected = false
            )
        )

        val alert = RoamingMonitorEngine.evaluateStickyClient(activeConn, aps)
        assertNull(alert)
    }

    @Test
    fun testRoamingTransitionDetection() {
        val prevConn = ActiveConnectionInfo(
            ssid = "WaveBalance_HQ",
            bssid = "00:1A:2B:3C:4D:01",
            rssi = -76,
            frequencyMhz = 5180,
            linkSpeedMbps = 150,
            standard = WifiStandard.WIFI_6,
            channel = 36,
            band = FrequencyBand.BAND_5_GHZ
        )

        val currentConn = ActiveConnectionInfo(
            ssid = "WaveBalance_HQ",
            bssid = "00:1A:2B:3C:4D:02",
            rssi = -50,
            frequencyMhz = 5745,
            linkSpeedMbps = 1200,
            standard = WifiStandard.WIFI_6,
            channel = 149,
            band = FrequencyBand.BAND_5_GHZ
        )

        val event = RoamingMonitorEngine.detectRoamingTransition(prevConn, currentConn)
        assertNotNull(event)
        assertEquals("00:1A:2B:3C:4D:01", event!!.fromBssid)
        assertEquals("00:1A:2B:3C:4D:02", event.toBssid)
        assertEquals(36, event.fromChannel)
        assertEquals(149, event.toChannel)
        assertEquals(-76, event.fromRssi)
        assertEquals(-50, event.toRssi)
        assertEquals(26, event.deltaRssiDb)

        // No transition if BSSID is identical
        val sameEvent = RoamingMonitorEngine.detectRoamingTransition(currentConn, currentConn)
        assertNull(sameEvent)
    }
}
