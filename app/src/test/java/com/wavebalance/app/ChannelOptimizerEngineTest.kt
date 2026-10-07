package com.wavebalance.app

import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ChannelOptimizerEngine
import com.wavebalance.app.model.ChannelRating
import com.wavebalance.app.model.ChannelWidth
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.NetworkGroups
import com.wavebalance.app.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelOptimizerEngineTest {

    @Test
    fun defaultWidthOn24Ghz_is20MhzInScoresAndRouterInstructions() {
        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_2_4_GHZ,
            allAps = emptyList(),
            currentChannel = 6
        )

        assertEquals(ChannelWidth.WIDTH_20, recommendation.recommendedBandwidth)
        assertTrue(recommendation.channelScores.all { it.recommendedWidth == ChannelWidth.WIDTH_20 })
        assertTrue(recommendation.routerDirectivesText.contains("Recommended Bandwidth: 20 MHz"))
        assertTrue(recommendation.stepByStepGuide.any { it.parameterHighlight == "20 MHz" })
    }

    @Test
    fun unsupportedWidths_fallBackToABandAppropriateWidth() {
        val cases = listOf(
            Triple(FrequencyBand.BAND_2_4_GHZ, ChannelWidth.WIDTH_160, ChannelWidth.WIDTH_20),
            Triple(FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_320, ChannelWidth.WIDTH_80),
            Triple(FrequencyBand.BAND_6_GHZ, ChannelWidth.UNKNOWN, ChannelWidth.WIDTH_80)
        )
        for ((band, requested, expected) in cases) {
            val recommendation = ChannelOptimizerEngine.evaluateBand(band, emptyList(), 1, requested)
            assertEquals(expected, recommendation.recommendedBandwidth)
            assertTrue(recommendation.channelScores.all { it.recommendedWidth == expected })
        }
    }

    @Test
    fun supportedWidths_arePreserved() {
        for ((band, width) in listOf(
            FrequencyBand.BAND_2_4_GHZ to ChannelWidth.WIDTH_40,
            FrequencyBand.BAND_5_GHZ to ChannelWidth.WIDTH_160,
            FrequencyBand.BAND_6_GHZ to ChannelWidth.WIDTH_320
        )) {
            val recommendation = ChannelOptimizerEngine.evaluateBand(band, emptyList(), 1, width)
            assertEquals(width, recommendation.recommendedBandwidth)
        }
    }

    private val testApCurrent = AccessPoint(
        bssid = "aa:bb:cc:dd:ee:01",
        ssid = "MyHome_5G",
        rssi = -50,
        frequencyMhz = 5240, // Ch 48
        channel = 48,
        channelWidth = ChannelWidth.WIDTH_80,
        band = FrequencyBand.BAND_5_GHZ,
        standard = WifiStandard.WIFI_6,
        capabilities = "[WPA2-PSK-CCMP][RSN-PSK-CCMP]",
        isConnected = true
    )

    @Test
    fun testEmptyAirspace_returnsOptimalScore() {
        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_5_GHZ,
            allAps = listOf(testApCurrent),
            currentChannel = 48,
            targetWidth = ChannelWidth.WIDTH_80
        )

        assertNotNull(recommendation)
        assertTrue(recommendation.recommendedScore >= 90)
    }

    @Test
    fun testCoChannelInterference_recommendsAlternativePristineChannel() {
        // Congest Channel 48 with strong competing neighbor
        val interferingAp = AccessPoint(
            bssid = "aa:bb:cc:dd:ee:02",
            ssid = "Neighbor_Strong",
            rssi = -55,
            frequencyMhz = 5240, // Co-channel Ch 48
            channel = 48,
            channelWidth = ChannelWidth.WIDTH_80,
            band = FrequencyBand.BAND_5_GHZ,
            standard = WifiStandard.WIFI_6,
            capabilities = "[WPA3-SAE]",
            isConnected = false
        )

        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_5_GHZ,
            allAps = listOf(testApCurrent, interferingAp),
            currentChannel = 48,
            targetWidth = ChannelWidth.WIDTH_80
        )

        // Recommended channel should move away from Ch 48 to clean UNII-3 or UNII-1
        assertTrue("Recommended channel should not be 48", recommendation.recommendedChannel != 48)
        assertTrue("Recommended score should be higher than current score", recommendation.recommendedScore > recommendation.currentScore)
        assertTrue("Headroom gain should be positive", recommendation.scoreDelta > 0)
    }

    @Test
    fun testRouterDirectives_containsRecommendedChannel() {
        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_5_GHZ,
            allAps = listOf(testApCurrent),
            currentChannel = 48,
            targetWidth = ChannelWidth.WIDTH_80
        )

        val directives = recommendation.routerDirectivesText
        assertTrue(directives.contains("WAVEBALANCE WI-FI OPTIMIZATION DIRECTIVES"))
        assertTrue(directives.contains("RECOMMENDED CHANNEL: Ch ${recommendation.recommendedChannel}"))
        assertTrue(directives.contains("Target Radio: 5 GHz"))
    }

    @Test
    fun testBandwidth24Ghz_evaluatesStandardChannels() {
        val ap24 = AccessPoint(
            bssid = "aa:bb:cc:dd:ee:03",
            ssid = "Home_2.4G",
            rssi = -60,
            frequencyMhz = 2437, // Ch 6
            channel = 6,
            channelWidth = ChannelWidth.WIDTH_20,
            band = FrequencyBand.BAND_2_4_GHZ,
            standard = WifiStandard.WIFI_4,
            capabilities = "[WPA2-PSK]",
            isConnected = true
        )

        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_2_4_GHZ,
            allAps = listOf(ap24),
            currentChannel = 6,
            targetWidth = ChannelWidth.WIDTH_20
        )

        assertTrue(listOf(1, 6, 11).contains(recommendation.recommendedChannel))
    }

    @Test
    fun testOwnMeshRadios_areNotCountedAsInterference() {
        val own = NetworkGroups.ownNetwork(TestMesh.all, TestMesh.connected.bssid, "Home_5G")

        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_5_GHZ,
            allAps = TestMesh.all,
            currentChannel = 149,
            targetWidth = ChannelWidth.WIDTH_40,
            ownNetworkBssids = own
        )
        val ch149 = recommendation.channelScores.first { it.channel == 149 }

        // Only the same-vendor neighbour is left on channel 149
        assertEquals(1, ch149.coChannelCount)
        assertEquals(listOf("Neighbor"), ch149.conflictingSsids)
        assertEquals(TestMesh.home.count { it.band == FrequencyBand.BAND_5_GHZ }, recommendation.ownRadiosIgnored)
    }

    @Test
    fun testWithoutOwnNetwork_meshRadiosLookLikeInterference() {
        val recommendation = ChannelOptimizerEngine.evaluateBand(
            band = FrequencyBand.BAND_5_GHZ,
            allAps = TestMesh.all,
            currentChannel = 149,
            targetWidth = ChannelWidth.WIDTH_40
        )
        val ch149 = recommendation.channelScores.first { it.channel == 149 }

        // The guest SSID and the far node show up as co-channel networks
        assertTrue(ch149.coChannelCount >= 3)
        assertEquals(0, recommendation.ownRadiosIgnored)
    }
}
