package com.wavebalance.app

import com.wavebalance.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ChannelPlanTest {
    @Test fun allPrimariesInAn80MhzBlockShareItsActualCenter() {
        for (primary in listOf(36, 40, 44, 48)) {
            val block = ChannelPlan.blocksForPrimary(primary, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_80).single()
            assertEquals(5210, block.centerFrequencyMhz)
            assertEquals(listOf(36, 40, 44, 48), block.componentChannels)
        }
    }

    @Test fun blocksCannotCross5GhzGapsOrExtendPastTheCatalog() {
        assertTrue(ChannelPlan.blocksForPrimary(165, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_80).isEmpty())
        assertTrue(ChannelPlan.blocksForPrimary(132, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_160).isEmpty())
        assertTrue(ChannelPlan.blocksForPrimary(149, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_160).isEmpty())
        assertEquals(5825, ChannelPlan.blocksForPrimary(165, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_20).single().centerFrequencyMhz)
    }

    @Test fun fortyMhzIn24GhzModelsBothSecondaryDirections() {
        val band = FrequencyBand.BAND_2_4_GHZ
        assertEquals(listOf(2427, 2447), ChannelPlan.blocksForPrimary(6, band, ChannelWidth.WIDTH_40).map { it.centerFrequencyMhz })
        assertEquals(listOf(1, 5), ChannelPlan.blocksForPrimary(1, band, ChannelWidth.WIDTH_40).single().componentChannels)
        assertEquals(listOf(7, 11), ChannelPlan.blocksForPrimary(11, band, ChannelWidth.WIDTH_40).single().componentChannels)
    }

    @Test fun sixGhz320MhzIncludesBothOverlappingPlacements() {
        val blocks = ChannelPlan.blocksForPrimary(37, FrequencyBand.BAND_6_GHZ, ChannelWidth.WIDTH_320)
        assertEquals(listOf(6105, 6265), blocks.map { it.centerFrequencyMhz })
        assertEquals(setOf(31, 63, 95, 127, 159, 191), ChannelPlan.candidates(FrequencyBand.BAND_6_GHZ, ChannelWidth.WIDTH_320).map { it.centerChannel }.toSet())
    }

    @Test fun allSixGhzBlocksStayInsideTheBandWithAlignedPrimaries() {
        for (width in ChannelWidth.supportedForBand(FrequencyBand.BAND_6_GHZ)) {
            for (block in ChannelPlan.candidates(FrequencyBand.BAND_6_GHZ, width)) {
                assertTrue(block.centerFrequencyMhz - width.mhz / 2 >= 5945)
                assertTrue(block.centerFrequencyMhz + width.mhz / 2 <= 7125)
                assertTrue(block.primaryChannel in block.componentChannels)
                assertTrue(block.componentChannels.all { it in 1..233 && (it - 1) % 4 == 0 })
            }
        }
    }

    @Test fun dfsIsDeterminedByTheWholeBondedBlock() {
        assertTrue(ChannelPlan.blocksForPrimary(36, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_160).single().isDfs)
        assertFalse(ChannelPlan.blocksForPrimary(36, FrequencyBand.BAND_5_GHZ, ChannelWidth.WIDTH_80).single().isDfs)
    }

    @Test fun optimizerSeesAChannel48NeighborInsideTheChannel36Block() {
        val neighbor = AccessPoint("00:11:22:33:44:55", "Neighbor", -50, 5240)
        val result = ChannelOptimizerEngine.evaluateBand(FrequencyBand.BAND_5_GHZ, listOf(neighbor), 36, ChannelWidth.WIDTH_80)
        val score = result.channelScores.single { it.channel == 36 }
        assertEquals(1, score.adjacentChannelCount)
        assertTrue(score.score < 100)
        assertEquals(5210, score.centerFrequencyMhz)
    }

    @Test fun neighboring80MhzBlocksDoNotOverlapAtTheirSharedEdge() {
        val neighbor = AccessPoint("00:11:22:33:44:55", "Neighbor", -50, 5260,
            channelWidth = ChannelWidth.WIDTH_80, centerFrequencyMhz = 5290)
        val result = ChannelOptimizerEngine.evaluateBand(FrequencyBand.BAND_5_GHZ, listOf(neighbor), 36, ChannelWidth.WIDTH_80)
        assertEquals(0, result.channelScores.single { it.channel == 48 }.adjacentChannelCount)
    }

    @Test fun optimizerIdentifiesTheObserved320MhzBlockAndOnlyOneRecommendation() {
        val active = AccessPoint("00:11:22:33:44:55", "Home", -50, 6135,
            channelWidth = ChannelWidth.WIDTH_320, centerFrequencyMhz = 6265, isConnected = true)
        val result = ChannelOptimizerEngine.evaluateBand(FrequencyBand.BAND_6_GHZ, listOf(active), 37, ChannelWidth.WIDTH_320)
        assertEquals(6265, result.currentCenterFrequencyMhz)
        assertEquals(1, result.channelScores.count { it.isCurrentChannel })
        assertEquals(1, result.channelScores.count { it.isRecommended })
        assertTrue(result.routerDirectivesText.contains("Center Frequency: ${result.recommendedCenterFrequencyMhz} MHz"))
    }

    @Test fun candidate165IsExcludedFor80MhzButCanBeEvaluatedAt20Mhz() {
        val result = ChannelOptimizerEngine.evaluateBand(FrequencyBand.BAND_5_GHZ, emptyList(), 165, ChannelWidth.WIDTH_80)
        assertTrue(result.channelScores.none { it.channel == 165 })
        assertEquals(ChannelWidth.WIDTH_20, result.currentBandwidth)
        assertEquals(5825, result.currentCenterFrequencyMhz)
    }
    @Test fun selectingANarrowerTargetDoesNotRewriteTheMeasuredCurrentBlock() {
        val active = AccessPoint("00:11:22:33:44:55", "Home", -45, 5180,
            channelWidth = ChannelWidth.WIDTH_80, centerFrequencyMhz = 5210, isConnected = true)
        val neighbor = AccessPoint("00:11:22:33:44:66", "Neighbor", -50, 5240)
        val result = ChannelOptimizerEngine.evaluateBand(FrequencyBand.BAND_5_GHZ, listOf(active, neighbor), 36, ChannelWidth.WIDTH_40)
        assertEquals(ChannelWidth.WIDTH_80, result.currentBandwidth)
        assertEquals(5210, result.currentCenterFrequencyMhz)
        assertTrue(result.currentScore < 100)
        assertTrue(result.channelScores.none { it.isCurrentChannel })
        assertEquals(ChannelWidth.WIDTH_40, result.recommendedBandwidth)
    }

    @Test fun anUnsupportedCurrentChannelIsNotReportedAsARealZeroScore() {
        val result = ChannelOptimizerEngine.evaluateBand(FrequencyBand.BAND_5_GHZ, emptyList(), 999)
        assertFalse(result.currentChannelEvaluated)
        assertEquals(0, result.scoreDelta)
        assertTrue(result.routerDirectivesText.contains("Not evaluated"))
    }

}
