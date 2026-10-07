package com.wavebalance.app

import com.wavebalance.app.model.ScanFreshness
import com.wavebalance.app.model.ScanStatus
import org.junit.Assert.*
import org.junit.Test

class ScanFreshnessTest {
    @Test fun cachedReadPreservesThrottleAndDoesNotInventAnUpdateTime() {
        val state = ScanFreshness()
        val throttle = ScanStatus.Throttled(20)
        assertEquals(throttle, state.onResults(throttle, 3, false, 10_000_000, 20_000_000, 100_000))
        assertEquals(90_000L, state.lastMeasuredAt)
    }

    @Test fun cachedReadCannotEraseAnError() {
        val state = ScanFreshness()
        val error = ScanStatus.Error("Scan rejected")
        assertEquals(error, state.onResults(error, 3, false, null, 20_000_000, 100_000))
    }

    @Test fun failedScanBecomesCachedInsteadOfSuccess() {
        val state = ScanFreshness()
        val result = state.onResults(ScanStatus.Scanning, 3, false, 10_000_000, 20_000_000, 100_000)
        assertEquals(ScanStatus.Cached(3, 90_000L), result)
    }

    @Test fun repeatedReadingDoesNotRefreshOldResults() {
        val state = ScanFreshness()
        val first = state.onResults(ScanStatus.Scanning, 3, true, 10_000_000, 20_000_000, 100_000)
        assertEquals(ScanStatus.Success(3, 90_000L), first)
        assertEquals(ScanStatus.Cached(3, 90_000L), state.onResults(first, 3, false, 10_000_000, 30_000_000, 110_000))
        assertEquals(ScanStatus.Cached(3, 90_000L), state.onResults(first, 3, true, 10_000_000, 30_000_000, 110_000))
    }

    @Test fun aGenuinelyNewBroadcastCanReplaceAThrottle() {
        val state = ScanFreshness()
        assertEquals(ScanStatus.Success(3, 99_000L), state.onResults(ScanStatus.Throttled(20), 3, true, 19_000_000, 20_000_000, 100_000))
    }

    @Test fun missingInvalidAndFutureTimestampsStayUnknown() {
        for (timestamp in listOf(null, 0L, -1L, 21_000_000L)) {
            val state = ScanFreshness()
            assertEquals(ScanStatus.Cached(3, null), state.onResults(ScanStatus.Idle, 3, false, timestamp, 20_000_000, 100_000))
        }
    }

    @Test fun successfulEmptyScanIsAnObservationButCachedEmptyScanIsNot() {
        val state = ScanFreshness()
        assertEquals(ScanStatus.Cached(0, null), state.onResults(ScanStatus.Idle, 0, false, null, 20_000_000, 100_000))
        assertEquals(ScanStatus.Success(0, 100_000), state.onResults(ScanStatus.Scanning, 0, true, null, 20_000_000, 100_000))
    }

    @Test fun modeChangeClearsMeasurementProvenance() {
        val state = ScanFreshness()
        state.onResults(ScanStatus.Scanning, 3, true, 10_000_000, 20_000_000, 100_000)
        state.reset()
        assertNull(state.lastMeasuredAt)
        assertEquals(ScanStatus.Cached(3, null), state.onResults(ScanStatus.Idle, 3, false, null, 20_000_000, 100_000))
    }
}
