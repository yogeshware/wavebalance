package com.wavebalance.app.model

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stages of the network speed & latency diagnostic test.
 */
enum class DiagnosticPhase(val displayName: String) {
    IDLE("Ready"),
    PING_JITTER("Probing Latency & Jitter"),
    DOWNLOAD("Testing Download Throughput"),
    UPLOAD("Testing Upload Throughput"),
    COMPLETED("Diagnostic Complete"),
    FAILED("Test Failed")
}

/**
 * Standard Bufferbloat Grade classifications based on latency increase under full saturation.
 */
enum class BufferbloatGrade(
    val grade: String,
    val description: String,
    val latencyDeltaThresholdMs: Double
) {
    A_PLUS("A+", "Negligible bufferbloat (<= 5 ms). Pristine real-time response under full load.", 5.0),
    A("A", "Minimal bufferbloat (<= 15 ms). Optimal for competitive esports gaming and high-tick servers.", 15.0),
    B("B", "Moderate bufferbloat (<= 30 ms). Minor latency increase during simultaneous saturation.", 30.0),
    C("C", "Noticeable bufferbloat (<= 60 ms). Stuttering or jitter may occur during concurrent heavy streaming/downloads.", 60.0),
    D("D", "Severe bufferbloat (<= 120 ms). Packet queuing causes perceptible rubber-banding and lag spikes.", 120.0),
    F("F", "Critical bufferbloat (> 120 ms). Excessive router buffer queues choke real-time packets.", Double.MAX_VALUE)
}

/**
 * Application suitability tier rating.
 */
enum class ApplicationRating(val title: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor")
}

/**
 * Detailed application suitability assessment across gaming, VoIP, 4K streaming, and cloud backup.
 */
data class QosAssessment(
    val gamingRating: ApplicationRating,
    val gamingDetail: String,
    val videoCallRating: ApplicationRating,
    val videoCallDetail: String,
    val streamingRating: ApplicationRating,
    val streamingDetail: String,
    val cloudTransferRating: ApplicationRating,
    val cloudTransferDetail: String
)

/**
 * Instantaneous point recorded during the diagnostic run.
 */
data class DiagnosticSamplePoint(
    val elapsedSec: Float,
    val speedMbps: Float,
    val pingMs: Float,
    val phase: DiagnosticPhase
)

/**
 * Download, upload and loaded-latency results. Absent from a ping-only test.
 */
data class ThroughputResult(
    // Average over the measured part of each phase (after TCP ramp-up)
    val downloadSpeedMbps: Double,
    // Highest one-second rate during the download phase
    val peakDownloadMbps: Double,
    val uploadSpeedMbps: Double,
    // Median latency while downloading / uploading
    val loadedDownloadPingMs: Double,
    val loadedUploadPingMs: Double,
    val bufferbloatDeltaMs: Double,
    val bufferbloatGrade: BufferbloatGrade,
    val qosAssessment: QosAssessment,
    // Bytes downloaded plus uploaded, including ramp-up
    val dataUsedBytes: Long
) {
    // The worse of the two directions, which is what the grade is based on
    val loadedPingMs: Double
        get() = max(loadedDownloadPingMs, loadedUploadPingMs)
}

/**
 * Results of a completed speed test or ping-only test.
 */
data class SpeedDiagnosticResult(
    val unloadedPingMs: Double,
    val jitterMs: Double,
    // Null for a ping-only test
    val throughput: ThroughputResult?,
    // Where the test ran, e.g. "M-Lab New York"; null if unknown
    val serverName: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Real-time state emitted during an ongoing diagnostic.
 */
data class DiagnosticState(
    val phase: DiagnosticPhase = DiagnosticPhase.IDLE,
    val progress: Float = 0f, // 0.0 to 1.0
    val currentSpeedMbps: Float = 0f,
    val currentPingMs: Float = 0f,
    val latestSamples: List<DiagnosticSamplePoint> = emptyList(),
    val result: SpeedDiagnosticResult? = null,
    // Set when phase is FAILED
    val error: String? = null
)

/**
 * The server a speed test runs against. Calls other than [prepare] block, and run on
 * Dispatchers.IO.
 */
interface SpeedTestServer {
    /** Picks a server and returns a display name for where the test runs. */
    suspend fun prepare(): String?

    /** One latency probe in ms, or null if the server didn't answer within [timeoutMs]. */
    fun probeLatencyMs(timeoutMs: Int): Double?

    /**
     * Runs one download stream until the server ends it or [onBytes] returns false,
     * calling [onBytes] with each amount received. Throws IOException if it fails.
     */
    fun download(onBytes: (Int) -> Boolean)

    /** Runs one upload stream, with the same contract as [download]. */
    fun upload(onBytes: (Int) -> Boolean)
}

data class SpeedTestConfig(
    val pingCount: Int = 12,
    val pingIntervalMs: Long = 100,
    val pingTimeoutMs: Int = 1_000,
    // Connections per direction. ndt7 uses one: its servers run BBR, which fills the
    // link with a single TCP stream
    val streams: Int = 1,
    // Longest a phase runs; an ndt7 server ends the download itself after about 10 s
    val phaseDurationMs: Long = 10_000,
    // Left out of the average while TCP ramps up
    val warmupMs: Long = 2_000,
    val sampleIntervalMs: Long = 250,
    val loadedPingIntervalMs: Long = 400
)

class SpeedTestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Core mathematical engine for Wi-Fi speed, latency jitter, and bufferbloat diagnostics.
 */
object SpeedDiagnosticEngine {

    /**
     * Calculates the statistical jitter (sample standard deviation) of ping round-trip times.
     * Formula: sigma = sqrt( (1 / (N - 1)) * sum( (p_i - mean)^2 ) )
     */
    fun calculateJitter(pings: List<Double>): Double {
        if (pings.size <= 1) return 0.0
        val mean = pings.average()
        val variance = pings.map { (it - mean).pow(2) }.sum() / (pings.size - 1)
        return sqrt(max(0.0, variance))
    }

    /**
     * Determines the bufferbloat grade based on the delta between loaded ping and unloaded ping.
     */
    fun evaluateBufferbloatGrade(deltaMs: Double): BufferbloatGrade {
        val nonNegativeDelta = max(0.0, deltaMs)
        return when {
            nonNegativeDelta <= 5.0 -> BufferbloatGrade.A_PLUS
            nonNegativeDelta <= 15.0 -> BufferbloatGrade.A
            nonNegativeDelta <= 30.0 -> BufferbloatGrade.B
            nonNegativeDelta <= 60.0 -> BufferbloatGrade.C
            nonNegativeDelta <= 120.0 -> BufferbloatGrade.D
            else -> BufferbloatGrade.F
        }
    }

    /**
     * Evaluates Quality of Service (QoS) across popular application profiles.
     */
    fun evaluateQos(
        unloadedPing: Double,
        jitter: Double,
        downloadMbps: Double,
        uploadMbps: Double,
        bufferbloatGrade: BufferbloatGrade
    ): QosAssessment {
        // 1. Gaming: Highly sensitive to jitter and bufferbloat
        val gamingRating = when {
            unloadedPing <= 30.0 && jitter <= 5.0 && (bufferbloatGrade == BufferbloatGrade.A_PLUS || bufferbloatGrade == BufferbloatGrade.A) -> ApplicationRating.EXCELLENT
            unloadedPing <= 55.0 && jitter <= 12.0 && bufferbloatGrade <= BufferbloatGrade.B -> ApplicationRating.GOOD
            unloadedPing <= 85.0 && jitter <= 25.0 -> ApplicationRating.FAIR
            else -> ApplicationRating.POOR
        }
        val gamingDetail = when (gamingRating) {
            ApplicationRating.EXCELLENT -> "Sub-30ms RTT & low jitter ensures competitive tournament-grade response."
            ApplicationRating.GOOD -> "Smooth online multiplayer with stable frame timings."
            ApplicationRating.FAIR -> "Playable, but slight latency spikes may be noticeable in fast FPS titles."
            ApplicationRating.POOR -> "High jitter or bufferbloat will cause rubber-banding and missed inputs."
        }

        // 2. Video Calls / VoIP: Sensitive to jitter and packet delivery timing
        val videoRating = when {
            unloadedPing <= 45.0 && jitter <= 8.0 && downloadMbps >= 10.0 -> ApplicationRating.EXCELLENT
            unloadedPing <= 75.0 && jitter <= 18.0 && downloadMbps >= 5.0 -> ApplicationRating.GOOD
            unloadedPing <= 120.0 && jitter <= 35.0 -> ApplicationRating.FAIR
            else -> ApplicationRating.POOR
        }
        val videoDetail = when (videoRating) {
            ApplicationRating.EXCELLENT -> "Crystal-clear HD/4K conferencing without robotic audio or stutter."
            ApplicationRating.GOOD -> "Reliable audio/video sync on Zoom, Meet, and Teams."
            ApplicationRating.FAIR -> "Minor packet jitter may occasionally pause video streams."
            ApplicationRating.POOR -> "Severe audio robotic artifacts and video freezing predicted."
        }

        // 3. 4K/8K Streaming: Sensitive to pure download bandwidth and stability
        val streamRating = when {
            downloadMbps >= 80.0 -> ApplicationRating.EXCELLENT
            downloadMbps >= 30.0 -> ApplicationRating.GOOD
            downloadMbps >= 15.0 -> ApplicationRating.FAIR
            else -> ApplicationRating.POOR
        }
        val streamDetail = when (streamRating) {
            ApplicationRating.EXCELLENT -> "Flawless concurrent multi-device 4K/8K HDR video playback."
            ApplicationRating.GOOD -> "Smooth single-stream 4K UHD or multiple 1080p streams."
            ApplicationRating.FAIR -> "Adequate for 1080p, but buffer stalls may occur on 4K bitrates."
            ApplicationRating.POOR -> "Insufficient bandwidth for high-definition streaming."
        }

        // 4. Cloud Transfer: Sensitive to upload throughput
        val cloudRating = when {
            uploadMbps >= 50.0 -> ApplicationRating.EXCELLENT
            uploadMbps >= 20.0 -> ApplicationRating.GOOD
            uploadMbps >= 8.0 -> ApplicationRating.FAIR
            else -> ApplicationRating.POOR
        }
        val cloudDetail = when (cloudRating) {
            ApplicationRating.EXCELLENT -> "High-speed backup and instant multi-gigabyte video uploads."
            ApplicationRating.GOOD -> "Fast photo sync and effortless file transfers."
            ApplicationRating.FAIR -> "Moderate upload speeds; large files will take extended time."
            ApplicationRating.POOR -> "Severely constrained upload pipeline; will bottleneck cloud drives."
        }

        return QosAssessment(
            gamingRating = gamingRating,
            gamingDetail = gamingDetail,
            videoCallRating = videoRating,
            videoCallDetail = videoDetail,
            streamingRating = streamRating,
            streamingDetail = streamDetail,
            cloudTransferRating = cloudRating,
            cloudTransferDetail = cloudDetail
        )
    }

    /** Median, so one slow or lost probe doesn't skew a latency figure. */
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of no values" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Total bytes transferred so far in a phase, read at [elapsedMs] into the phase. */
    data class ByteSample(val elapsedMs: Long, val bytes: Long)

    /** Rate between two readings in Mbps (bits per ms / 1000). */
    fun rateMbps(from: ByteSample, to: ByteSample): Double {
        val ms = to.elapsedMs - from.elapsedMs
        if (ms <= 0) return 0.0
        return (to.bytes - from.bytes) * 8.0 / ms / 1000.0
    }

    /**
     * Average rate from the last reading at or before [warmupMs] to the end of the phase,
     * leaving out TCP slow start.
     */
    fun steadyRateMbps(samples: List<ByteSample>, warmupMs: Long): Double {
        if (samples.size < 2) return 0.0
        val start = samples.lastOrNull { it.elapsedMs <= warmupMs } ?: samples.first()
        return rateMbps(start, samples.last())
    }

    /** Highest rate over any span of at least [windowMs] between readings. */
    fun peakRateMbps(samples: List<ByteSample>, windowMs: Long = 1_000): Double {
        var peak = 0.0
        var start = 0
        for (end in samples.indices) {
            // Move the start up as long as the span stays at least windowMs
            while (start + 1 < end && samples[end].elapsedMs - samples[start + 1].elapsedMs >= windowMs) start++
            if (samples[end].elapsedMs - samples[start].elapsedMs >= windowMs) {
                peak = max(peak, rateMbps(samples[start], samples[end]))
            }
        }
        return peak
    }

    /**
     * Full test against [server]: idle latency and jitter, then download and upload.
     * Latency is probed during both transfers to measure bufferbloat.
     * Ends with a COMPLETED state carrying the result, or a FAILED state with the reason.
     */
    fun runSpeedTest(server: SpeedTestServer, config: SpeedTestConfig = SpeedTestConfig()): Flow<DiagnosticState> = flow {
        val graph = mutableListOf<DiagnosticSamplePoint>()
        val clock = TestClock()

        emit(DiagnosticState(phase = DiagnosticPhase.PING_JITTER, progress = 0.02f))
        val serverName = server.prepare()
        val pings = measureIdleLatency(server, config, graph, clock, 0.02f, 0.15f)
        val unloaded = median(pings)
        val jitter = calculateJitter(pings)

        val download = measureTransfer(DiagnosticPhase.DOWNLOAD, server::download, server, config, graph, clock, unloaded, 0.15f, 0.575f)
        val upload = measureTransfer(DiagnosticPhase.UPLOAD, server::upload, server, config, graph, clock, unloaded, 0.575f, 1f)

        val downloadMbps = steadyRateMbps(download.readings, config.warmupMs)
        val uploadMbps = steadyRateMbps(upload.readings, config.warmupMs)
        val loadedDownload = download.loadedPings.takeIf { it.isNotEmpty() }?.let(::median) ?: unloaded
        val loadedUpload = upload.loadedPings.takeIf { it.isNotEmpty() }?.let(::median) ?: unloaded
        val deltaMs = max(0.0, max(loadedDownload, loadedUpload) - unloaded)
        val grade = evaluateBufferbloatGrade(deltaMs)

        val result = SpeedDiagnosticResult(
            unloadedPingMs = unloaded,
            jitterMs = jitter,
            throughput = ThroughputResult(
                downloadSpeedMbps = downloadMbps,
                peakDownloadMbps = max(downloadMbps, peakRateMbps(download.readings)),
                uploadSpeedMbps = uploadMbps,
                loadedDownloadPingMs = loadedDownload,
                loadedUploadPingMs = loadedUpload,
                bufferbloatDeltaMs = deltaMs,
                bufferbloatGrade = grade,
                qosAssessment = evaluateQos(unloaded, jitter, downloadMbps, uploadMbps, grade),
                dataUsedBytes = download.readings.last().bytes + upload.readings.last().bytes
            ),
            serverName = serverName
        )
        emit(
            DiagnosticState(
                phase = DiagnosticPhase.COMPLETED,
                progress = 1f,
                currentSpeedMbps = downloadMbps.toFloat(),
                currentPingMs = unloaded.toFloat(),
                latestSamples = graph.toList(),
                result = result
            )
        )
    }.catch { e -> emitFailure(e) }

    /** Latency and jitter only. The result has no throughput. */
    fun runPingTest(server: SpeedTestServer, config: SpeedTestConfig = SpeedTestConfig()): Flow<DiagnosticState> = flow {
        val graph = mutableListOf<DiagnosticSamplePoint>()
        emit(DiagnosticState(phase = DiagnosticPhase.PING_JITTER, progress = 0.05f))
        val serverName = server.prepare()
        val pings = measureIdleLatency(server, config, graph, TestClock(), 0.05f, 1f)
        val unloaded = median(pings)
        emit(
            DiagnosticState(
                phase = DiagnosticPhase.COMPLETED,
                progress = 1f,
                currentPingMs = unloaded.toFloat(),
                latestSamples = graph.toList(),
                result = SpeedDiagnosticResult(
                    unloadedPingMs = unloaded,
                    jitterMs = calculateJitter(pings),
                    throughput = null,
                    serverName = serverName
                )
            )
        )
    }.catch { e -> emitFailure(e) }

    private const val MAX_GRAPH_SAMPLES = 120

    private class TestClock {
        private val startNs = System.nanoTime()
        fun elapsedSec(): Float = (System.nanoTime() - startNs) / 1e9f
    }

    private class TransferMeasurement(val readings: List<ByteSample>, val loadedPings: List<Double>)

    // Only failures of the test itself become a FAILED state; anything else (including
    // cancellation) propagates
    private suspend fun FlowCollector<DiagnosticState>.emitFailure(e: Throwable) {
        val message = when (e) {
            is SpeedTestException -> e.message
            is IOException -> "Network error: ${e.message ?: e.javaClass.simpleName}"
            else -> throw e
        }
        emit(DiagnosticState(phase = DiagnosticPhase.FAILED, error = message))
    }

    private suspend fun FlowCollector<DiagnosticState>.measureIdleLatency(
        server: SpeedTestServer,
        config: SpeedTestConfig,
        graph: MutableList<DiagnosticSamplePoint>,
        clock: TestClock,
        progressFrom: Float,
        progressTo: Float
    ): List<Double> {
        val pings = mutableListOf<Double>()
        for (i in 1..config.pingCount) {
            val rtt = withContext(Dispatchers.IO) { server.probeLatencyMs(config.pingTimeoutMs) }
            if (rtt != null) {
                pings += rtt
                graph += DiagnosticSamplePoint(clock.elapsedSec(), 0f, rtt.toFloat(), DiagnosticPhase.PING_JITTER)
            }
            emit(
                DiagnosticState(
                    phase = DiagnosticPhase.PING_JITTER,
                    progress = progressFrom + (progressTo - progressFrom) * i / config.pingCount,
                    currentPingMs = (rtt ?: pings.lastOrNull())?.toFloat() ?: 0f,
                    latestSamples = graph.takeLast(MAX_GRAPH_SAMPLES)
                )
            )
            if (i < config.pingCount) delay(config.pingIntervalMs)
        }
        if (pings.size < max(3, config.pingCount / 2)) {
            throw SpeedTestException(
                "The test server didn't answer (${pings.size} of ${config.pingCount} latency probes). " +
                    "Check the internet connection."
            )
        }
        return pings
    }

    /**
     * Runs [transfer] on [SpeedTestConfig.streams] connections until the server ends them
     * or the phase time runs out, reading the byte counter every sample interval and
     * probing latency alongside.
     */
    private suspend fun FlowCollector<DiagnosticState>.measureTransfer(
        phase: DiagnosticPhase,
        transfer: ((Int) -> Boolean) -> Unit,
        server: SpeedTestServer,
        config: SpeedTestConfig,
        graph: MutableList<DiagnosticSamplePoint>,
        clock: TestClock,
        idlePingMs: Double,
        progressFrom: Float,
        progressTo: Float
    ): TransferMeasurement {
        val bytes = AtomicLong()
        val done = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val loadedPings = Collections.synchronizedList(mutableListOf<Double>())
        val phaseStartNs = System.nanoTime()
        fun phaseMs() = (System.nanoTime() - phaseStartNs) / 1_000_000

        val runningStreams = AtomicInteger(config.streams)
        val lastByteAtMs = AtomicLong(0)

        // Transfers block in socket reads and writes, which coroutine cancellation can't
        // interrupt. They check [done] after every read or write instead, and live in their
        // own scope so a stalled connection is abandoned rather than waited for.
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repeat(config.streams) {
            ioScope.launch {
                try {
                    transfer { n ->
                        bytes.addAndGet(n.toLong())
                        lastByteAtMs.set(phaseMs())
                        !done.get()
                    }
                } catch (e: IOException) {
                    // A stream that drops part-way still measured something; only a
                    // phase with no data at all fails (below)
                    failure.compareAndSet(null, e)
                } finally {
                    runningStreams.decrementAndGet()
                }
            }
        }
        ioScope.launch {
            // Give the connections a moment to fill the link before measuring latency under load
            delay(config.warmupMs / 2)
            while (!done.get()) {
                // An unanswered probe counts as the full timeout: latency was at least that long
                val rtt = server.probeLatencyMs(config.pingTimeoutMs) ?: config.pingTimeoutMs.toDouble()
                if (!done.get()) loadedPings += rtt
                delay(config.loadedPingIntervalMs)
            }
        }

        val readings = mutableListOf(ByteSample(0, 0))
        try {
            // Ends at the time limit, or earlier when the server has ended every stream
            while (phaseMs() < config.phaseDurationMs && runningStreams.get() > 0) {
                delay(config.sampleIntervalMs)
                val now = ByteSample(phaseMs(), bytes.get())
                readings += now
                // Show the rate over the last second, so the gauge doesn't jump on every read.
                // Skip the first moments of the phase: uploads look several times faster
                // than the link while the socket send buffers fill.
                val settled = readings.firstOrNull { it.elapsedMs >= config.warmupMs / 4 }
                val oneSecondAgo = readings.lastOrNull { now.elapsedMs - it.elapsedMs >= 1_000 } ?: readings.first()
                val windowStart = listOfNotNull(settled, oneSecondAgo).maxBy { it.elapsedMs }
                val currentMbps = if (settled == null || windowStart === now) 0f else rateMbps(windowStart, now).toFloat()
                val pingMs = (synchronized(loadedPings) { loadedPings.lastOrNull() } ?: idlePingMs).toFloat()
                graph += DiagnosticSamplePoint(clock.elapsedSec(), currentMbps, pingMs, phase)
                emit(
                    DiagnosticState(
                        phase = phase,
                        progress = progressFrom + (progressTo - progressFrom) *
                            min(1f, now.elapsedMs.toFloat() / config.phaseDurationMs),
                        currentSpeedMbps = currentMbps,
                        currentPingMs = pingMs,
                        latestSamples = graph.takeLast(MAX_GRAPH_SAMPLES)
                    )
                )
            }
        } finally {
            done.set(true)
            ioScope.cancel()
        }

        if (runningStreams.get() == 0) {
            // The server ended the streams between readings: end the measurement at the
            // last byte, so the idle time after it doesn't lower the average
            val lastByteMs = lastByteAtMs.get()
            readings.removeAll { it.elapsedMs > lastByteMs }
            if (readings.last().elapsedMs < lastByteMs) readings += ByteSample(lastByteMs, bytes.get())
        }

        val direction = if (phase == DiagnosticPhase.UPLOAD) "upload" else "download"
        if (readings.last().bytes == 0L) {
            val reason = failure.get()?.message?.let { ": $it" } ?: ""
            throw SpeedTestException("The $direction didn't transfer any data$reason", failure.get())
        }
        if (readings.last().elapsedMs <= config.warmupMs) {
            // No steady-state interval was measured. Reporting success here produces
            // 0 Mbps and can also award A+ before any loaded-latency probe finishes.
            throw SpeedTestException("The $direction ended before warm-up finished. Run the test again.")
        }
        return TransferMeasurement(readings, synchronized(loadedPings) { loadedPings.toList() })
    }

    /**
     * Synthesizes an exportable Markdown diagnostic audit report.
     */
    fun generateSpeedReportMarkdown(
        result: SpeedDiagnosticResult,
        activeSsid: String,
        bssid: String,
        // Negotiated Wi-Fi link rate, or null when not connected
        linkSpeedMbps: Int?
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestampStr = dateFormat.format(Date(result.timestamp))
        fun f(value: Double) = String.format(Locale.US, "%.1f", value)
        val throughput = result.throughput

        return buildString {
            appendLine("# WaveBalance Speed & Latency Diagnostic Audit")
            appendLine("**Generated:** $timestampStr")
            appendLine("**Target Network SSID:** `$activeSsid`")
            appendLine("**Connected BSSID:** `$bssid`")
            appendLine("**Wi-Fi Link Rate:** `${linkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"}`")
            appendLine("**Test Server:** `${result.serverName ?: "Unknown"}`")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 1. Executive Performance Metrics")
            if (throughput != null) {
                appendLine("- **Download:** `${f(throughput.downloadSpeedMbps)} Mbps` (Peak: `${f(throughput.peakDownloadMbps)} Mbps`)")
                appendLine("- **Upload:** `${f(throughput.uploadSpeedMbps)} Mbps`")
            }
            appendLine("- **Unloaded Idle Ping:** `${f(result.unloadedPingMs)} ms`")
            appendLine("- **Jitter (Latency Stability):** `±${f(result.jitterMs)} ms`")
            if (throughput == null) {
                appendLine()
                appendLine("*Ping-only test: download, upload and bufferbloat were not measured.*")
            } else {
                appendLine("- **Ping While Downloading:** `${f(throughput.loadedDownloadPingMs)} ms`")
                appendLine("- **Ping While Uploading:** `${f(throughput.loadedUploadPingMs)} ms`")
                appendLine("- **Bufferbloat Latency Delta:** `+${f(throughput.bufferbloatDeltaMs)} ms`")
                appendLine("- **Bufferbloat Rating:** **Grade ${throughput.bufferbloatGrade.grade}** (${throughput.bufferbloatGrade.description})")
                appendLine("- **Data Used:** `${throughput.dataUsedBytes / 1_000_000} MB`")
                appendLine()
                appendLine("---")
                appendLine()
                val qos = throughput.qosAssessment
                appendLine("## 2. Quality of Service (QoS) & Application Suitability")
                appendLine("| Application Profile | Rating | Assessment |")
                appendLine("|:---|:---:|:---|")
                appendLine("| 🎮 Competitive Gaming | **${qos.gamingRating.title}** | ${qos.gamingDetail} |")
                appendLine("| 📞 Video Conferencing & VoIP | **${qos.videoCallRating.title}** | ${qos.videoCallDetail} |")
                appendLine("| 🍿 4K/8K HDR Video Streaming | **${qos.streamingRating.title}** | ${qos.streamingDetail} |")
                appendLine("| ☁️ Cloud Sync & Backups | **${qos.cloudTransferRating.title}** | ${qos.cloudTransferDetail} |")
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## 3. Router Optimization Directives")
                if (throughput.bufferbloatGrade >= BufferbloatGrade.C) {
                    appendLine("> [!WARNING]")
                    appendLine("> **Bufferbloat Detected:** Active latency increases significantly (+${f(throughput.bufferbloatDeltaMs)} ms) under network load.")
                    appendLine("> Enable **Smart Queue Management (SQM)**, **FQ-CoDel**, or **CAKE** queueing algorithms in your router administration dashboard to prioritize latency-sensitive packets over bulk downloads.")
                } else {
                    appendLine("> [!NOTE]")
                    appendLine("> **Bufferbloat Passed:** Minimal bufferbloat observed (+${f(throughput.bufferbloatDeltaMs)} ms). Router queueing parameters are well balanced.")
                }
            }
            appendLine()
            appendLine("---")
            appendLine("*Report synthesized by WaveBalance Adaptive RF Spectrum & Network Diagnostic Engine*")
        }
    }
}
