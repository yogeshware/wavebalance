package com.wavebalance.app

import com.wavebalance.app.model.BufferbloatGrade
import com.wavebalance.app.model.DiagnosticPhase
import com.wavebalance.app.model.DiagnosticState
import com.wavebalance.app.model.SpeedDiagnosticEngine
import com.wavebalance.app.model.SpeedDiagnosticEngine.ByteSample
import com.wavebalance.app.model.SpeedTestConfig
import com.wavebalance.app.model.SpeedTestServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SpeedTestRunTest {

    /**
     * Sends 64 KB every [chunkDelayMs] per stream (about 105 Mbps at 5 ms) until
     * [streamDurationMs] or the client stops it, like an ndt7 server ending its test.
     * Answers latency probes with [loadedLatencyMs] while any transfer runs.
     */
    private class FakeServer(
        val idleLatencyMs: Double? = 10.0,
        val loadedLatencyMs: Double? = 10.0,
        val chunkDelayMs: Long = 5,
        val streamDurationMs: Long = Long.MAX_VALUE,
        val downloadFails: Boolean = false
    ) : SpeedTestServer {
        private val activeTransfers = AtomicInteger()

        override suspend fun prepare(): String = "Test server"

        override fun probeLatencyMs(timeoutMs: Int): Double? =
            if (activeTransfers.get() > 0) loadedLatencyMs else idleLatencyMs

        override fun download(onBytes: (Int) -> Boolean) {
            if (downloadFails) throw IOException("connection reset")
            transfer(onBytes)
        }

        override fun upload(onBytes: (Int) -> Boolean) = transfer(onBytes)

        private fun transfer(onBytes: (Int) -> Boolean) {
            activeTransfers.incrementAndGet()
            try {
                val start = System.nanoTime()
                while ((System.nanoTime() - start) / 1_000_000 < streamDurationMs) {
                    Thread.sleep(chunkDelayMs)
                    if (!onBytes(CHUNK)) return
                }
            } finally {
                activeTransfers.decrementAndGet()
            }
        }

        companion object {
            const val CHUNK = 64 * 1024
        }
    }

    // Short phases so the suite stays fast
    private val config = SpeedTestConfig(
        pingCount = 5,
        pingIntervalMs = 1,
        pingTimeoutMs = 300,
        streams = 2,
        phaseDurationMs = 800,
        warmupMs = 200,
        sampleIntervalMs = 50,
        loadedPingIntervalMs = 20
    )

    private fun Flow<DiagnosticState>.run(): List<DiagnosticState> = runBlocking { toList() }

    @Test
    fun fullTest_reportsMeasuredThroughputInPhaseOrder() {
        val states = SpeedDiagnosticEngine.runSpeedTest(FakeServer(), config).run()

        val phases = states.map { it.phase }.fold(listOf<DiagnosticPhase>()) { acc, p -> if (acc.lastOrNull() == p) acc else acc + p }
        assertEquals(listOf(DiagnosticPhase.PING_JITTER, DiagnosticPhase.DOWNLOAD, DiagnosticPhase.UPLOAD, DiagnosticPhase.COMPLETED), phases)
        assertTrue("progress never goes backwards", states.zipWithNext().all { (a, b) -> b.progress >= a.progress })

        val result = states.last().result!!
        val throughput = result.throughput!!
        // Two streams at up to ~105 Mbps each; sleeps overrun, so allow a wide margin below
        assertTrue("download ${throughput.downloadSpeedMbps}", throughput.downloadSpeedMbps in 60.0..215.0)
        assertTrue("upload ${throughput.uploadSpeedMbps}", throughput.uploadSpeedMbps in 60.0..215.0)
        assertTrue(throughput.peakDownloadMbps >= throughput.downloadSpeedMbps)
        assertTrue(throughput.dataUsedBytes > 0)
        assertEquals(10.0, result.unloadedPingMs, 0.001)
        assertEquals(0.0, result.jitterMs, 0.001)
        assertEquals("Test server", result.serverName)
    }

    @Test
    fun bufferbloat_isLatencyUnderLoadMinusIdleLatency() {
        val states = SpeedDiagnosticEngine.runSpeedTest(FakeServer(idleLatencyMs = 10.0, loadedLatencyMs = 60.0), config).run()

        val throughput = states.last().result!!.throughput!!
        assertEquals(60.0, throughput.loadedDownloadPingMs, 0.001)
        assertEquals(60.0, throughput.loadedUploadPingMs, 0.001)
        assertEquals(50.0, throughput.bufferbloatDeltaMs, 0.001)
        assertEquals(BufferbloatGrade.C, throughput.bufferbloatGrade)
    }

    @Test
    fun unansweredProbesUnderLoad_countAsTheTimeout() {
        val states = SpeedDiagnosticEngine.runSpeedTest(FakeServer(loadedLatencyMs = null), config).run()

        val throughput = states.last().result!!.throughput!!
        assertEquals(config.pingTimeoutMs.toDouble(), throughput.loadedDownloadPingMs, 0.001)
        assertEquals(BufferbloatGrade.F, throughput.bufferbloatGrade)
    }

    @Test
    fun unreachableServer_failsWithAReason() {
        val states = SpeedDiagnosticEngine.runSpeedTest(FakeServer(idleLatencyMs = null), config).run()

        val last = states.last()
        assertEquals(DiagnosticPhase.FAILED, last.phase)
        assertTrue(last.error!!.contains("didn't answer"))
        assertTrue(states.none { it.phase == DiagnosticPhase.DOWNLOAD })
    }

    @Test
    fun failedDownload_failsInsteadOfReportingASpeed() {
        val states = SpeedDiagnosticEngine.runSpeedTest(FakeServer(downloadFails = true), config).run()

        val last = states.last()
        assertEquals(DiagnosticPhase.FAILED, last.phase)
        assertNull(last.result)
        assertTrue(last.error!!.contains("download"))
        assertTrue(last.error!!.contains("connection reset"))
    }

    @Test
    fun pingTest_hasNoThroughput() {
        val states = SpeedDiagnosticEngine.runPingTest(FakeServer(idleLatencyMs = 12.0), config).run()

        val result = states.last().result!!
        assertEquals(DiagnosticPhase.COMPLETED, states.last().phase)
        assertEquals(12.0, result.unloadedPingMs, 0.001)
        assertNull(result.throughput)
        assertTrue(states.none { it.phase == DiagnosticPhase.DOWNLOAD || it.phase == DiagnosticPhase.UPLOAD })
    }

    @Test
    fun serverEndingTheStreams_endsThePhaseEarlyWithoutLoweringTheRate() {
        val started = System.nanoTime()
        val states = SpeedDiagnosticEngine.runSpeedTest(FakeServer(streamDurationMs = 500), config.copy(phaseDurationMs = 5_000)).run()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // Two phases ending at ~0.5 s each, not at the 5 s limit
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
        val throughput = states.last().result!!.throughput!!
        assertTrue("download ${throughput.downloadSpeedMbps}", throughput.downloadSpeedMbps in 60.0..215.0)
    }

    @Test
    fun transferEndingDuringWarmup_doesNotPublishACompletedResult() {
        val states = SpeedDiagnosticEngine.runSpeedTest(
            FakeServer(streamDurationMs = 50),
            config.copy(phaseDurationMs = 10_000, warmupMs = 2_000)
        ).run()

        assertEquals(DiagnosticPhase.FAILED, states.last().phase)
        assertNull(states.last().result)
        assertTrue(states.last().error!!.contains("warm-up"))
        assertTrue(states.none { it.phase == DiagnosticPhase.UPLOAD })
    }

    @Test
    fun rateMath() {
        // 1, 2 and 3 MB in successive seconds: 8, 16 and 24 Mbps
        val samples = listOf(
            ByteSample(0, 0),
            ByteSample(1_000, 1_000_000),
            ByteSample(2_000, 3_000_000),
            ByteSample(3_000, 6_000_000)
        )

        assertEquals(8.0, SpeedDiagnosticEngine.rateMbps(samples[0], samples[1]), 0.001)
        // Leaving out the first second: 5 MB over 2 s
        assertEquals(20.0, SpeedDiagnosticEngine.steadyRateMbps(samples, warmupMs = 1_000), 0.001)
        assertEquals(24.0, SpeedDiagnosticEngine.peakRateMbps(samples, windowMs = 1_000), 0.001)
        assertEquals(0.0, SpeedDiagnosticEngine.steadyRateMbps(samples.take(1), warmupMs = 0), 0.001)
    }

    @Test
    fun median() {
        assertEquals(3.0, SpeedDiagnosticEngine.median(listOf(5.0, 1.0, 3.0)), 0.001)
        assertEquals(2.5, SpeedDiagnosticEngine.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.001)
    }
}
