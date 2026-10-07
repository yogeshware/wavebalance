package com.wavebalance.app.data

import com.wavebalance.app.model.SpeedTestException
import com.wavebalance.app.model.SpeedTestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Speed test on Measurement Lab (M-Lab) servers with the ndt7 protocol:
 * https://github.com/m-lab/ndt-server/blob/main/spec/ndt7-protocol.md
 *
 * The locate API picks a nearby server and issues single-test URLs. Each direction is
 * one WebSocket: the server sends random binary messages for about 10 s on download,
 * and the client sends them on upload.
 *
 * M-Lab publishes every ndt7 result as open data, including the client's IP address,
 * so the app asks for consent before the first full test. Latency probes are plain
 * TCP connections and aren't ndt7 tests.
 */
class MLabNdt7Server(
    private val clientVersion: String,
    private val client: OkHttpClient = defaultClient
) : SpeedTestServer {

    @Volatile
    private var located: LocatedServer? = null

    @Volatile
    private var address: InetAddress? = null

    private val userAgent = "WaveBalance/$clientVersion (Android; +https://github.com/yogeshware/wavebalance)"

    override suspend fun prepare(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$LOCATE_URL?client_name=$CLIENT_NAME&client_version=$clientVersion")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        val candidates = try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parseLocateResponse(response.body?.string().orEmpty())
                    // M-Lab's documented "out of capacity" signal
                    204 -> throw SpeedTestException("M-Lab has no free test servers right now. Try again in a few minutes.")
                    else -> throw SpeedTestException("M-Lab's server locator answered HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            throw SpeedTestException("Can't reach M-Lab. Check the internet connection.", e)
        }

        // The locator's order isn't strictly by latency (two servers in one city can differ
        // by 25 ms), so use the candidate that answers fastest
        val fastest = candidates.mapNotNull { candidate ->
            val resolved = try {
                InetAddress.getByName(candidate.host)
            } catch (e: UnknownHostException) {
                return@mapNotNull null
            }
            val best = (1..CANDIDATE_PROBES).mapNotNull { connectMs(resolved, CANDIDATE_TIMEOUT_MS) }.minOrNull()
            best?.let { Triple(candidate, resolved, it) }
        }.minByOrNull { it.third }
            ?: throw SpeedTestException("None of the M-Lab servers offered answered. Check the internet connection.")

        located = fastest.first
        // Resolved here, so DNS lookups aren't part of the latency probes
        address = fastest.second
        listOfNotNull("M-Lab", fastest.first.city).joinToString(" ")
    }

    // The TCP handshake time is the network round trip alone; nothing is sent
    override fun probeLatencyMs(timeoutMs: Int): Double? = address?.let { connectMs(it, timeoutMs) }

    private fun connectMs(target: InetAddress, timeoutMs: Int): Double? = try {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(target, HTTPS_PORT), timeoutMs)
            (System.nanoTime() - start) / 1_000_000.0
        }
    } catch (e: IOException) {
        null
    }

    override fun download(onBytes: (Int) -> Boolean) {
        val url = located?.downloadUrl ?: throw IOException("No test server picked")
        val finished = CountDownLatch(1)
        val stopping = AtomicBoolean(false)
        val failure = AtomicReference<IOException?>(null)

        val webSocket = client.newWebSocket(request(url), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!onBytes(bytes.size) && stopping.compareAndSet(false, true)) {
                    webSocket.cancel()
                    finished.countDown()
                }
            }

            // Text messages are the server's measurements; the client measures for itself
            override fun onMessage(webSocket: WebSocket, text: String) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The server ends the test, normally after about 10 s
                webSocket.close(NORMAL_CLOSURE, null)
                finished.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopping.get()) failure.set(describeFailure(t, response))
                finished.countDown()
            }
        })

        if (!finished.await(MAX_TEST_SECONDS, TimeUnit.SECONDS)) webSocket.cancel()
        failure.get()?.let { throw it }
    }

    override fun upload(onBytes: (Int) -> Boolean) {
        val url = located?.uploadUrl ?: throw IOException("No test server picked")
        val opened = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val stopping = AtomicBoolean(false)
        val failure = AtomicReference<IOException?>(null)

        val webSocket = client.newWebSocket(request(url), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

            override fun onMessage(webSocket: WebSocket, text: String) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                finished.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopping.get()) failure.set(describeFailure(t, response))
                opened.countDown()
                finished.countDown()
            }
        })

        if (!opened.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            webSocket.cancel()
            throw IOException("Timed out connecting to the M-Lab server")
        }
        failure.get()?.let { throw it }

        var messageSize = INITIAL_MESSAGE_SIZE
        var queued = 0L
        var reported = 0L
        try {
            while (finished.count > 0) {
                // OkHttp queues sends (up to 16 MB) and writes them in the background. Keep
                // only a little queued, and count bytes once they've left the queue, so a
                // backlog isn't counted as uploaded.
                if (webSocket.queueSize() < MAX_QUEUED_BYTES) {
                    if (!webSocket.send(payload(messageSize))) break
                    queued += messageSize
                    // Grow messages for fast links, as the spec's appendix suggests
                    if (messageSize < MAX_MESSAGE_SIZE && messageSize < queued / 16) messageSize *= 2
                } else {
                    Thread.sleep(1)
                }
                val sent = queued - webSocket.queueSize()
                if (sent > reported) {
                    val keepGoing = onBytes((sent - reported).toInt())
                    reported = sent
                    if (!keepGoing) break
                }
            }
        } finally {
            stopping.set(true)
            webSocket.close(NORMAL_CLOSURE, null)
            if (!finished.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) webSocket.cancel()
        }
        failure.get()?.let { throw it }
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Sec-WebSocket-Protocol", NDT7_SUBPROTOCOL)
        .build()

    private fun describeFailure(t: Throwable, response: Response?): IOException = when {
        response != null -> IOException("the M-Lab server answered HTTP ${response.code}", t)
        t is IOException -> t
        else -> IOException(t.message ?: t.javaClass.simpleName, t)
    }

    internal data class LocatedServer(
        val host: String,
        val city: String?,
        val downloadUrl: String,
        val uploadUrl: String
    )

    companion object {
        private const val LOCATE_URL = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
        private const val CLIENT_NAME = "wavebalance-android"
        private const val NDT7_SUBPROTOCOL = "net.measurementlab.ndt.v7"
        private const val HTTPS_PORT = 443
        private const val NORMAL_CLOSURE = 1000

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val CLOSE_WAIT_SECONDS = 2L
        private const val CANDIDATE_PROBES = 2
        private const val CANDIDATE_TIMEOUT_MS = 1_000
        // The spec allows a test to be cut off after 13 s
        private const val MAX_TEST_SECONDS = 15L

        // Message sizes from the spec: start at 8 KB, never above 16 MB (we stop at 1 MB)
        private const val INITIAL_MESSAGE_SIZE = 1 shl 13
        private const val MAX_MESSAGE_SIZE = 1 shl 20
        private const val MAX_QUEUED_BYTES = 1L shl 20

        const val PRIVACY_POLICY_URL = "https://www.measurementlab.net/privacy/"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        // Random, so nothing along the way can compress it; one per message size
        private val randomBytes = Random.nextBytes(MAX_MESSAGE_SIZE)
        private val payloads = HashMap<Int, ByteString>()

        private fun payload(size: Int): ByteString =
            synchronized(payloads) { payloads.getOrPut(size) { randomBytes.toByteString(0, size) } }

        private fun secureWebSocketUri(url: String): URI? = try {
            URI(url).takeIf {
                it.scheme.equals("wss", ignoreCase = true) &&
                    !it.host.isNullOrBlank() && it.rawUserInfo == null &&
                    (it.port == -1 || it.port in 1..65535)
            }
        } catch (e: URISyntaxException) {
            null
        }

        /** The results offering ndt7 download and upload over TLS, in the locator's order. */
        internal fun parseLocateResponse(json: String): List<LocatedServer> {
            val servers = mutableListOf<LocatedServer>()
            try {
                val results = JSONObject(json).optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val result = results.optJSONObject(i) ?: continue
                        val urls = result.optJSONObject("urls") ?: continue
                        val download = urls.optString("wss:///ndt/v7/download").ifBlank { null } ?: continue
                        val upload = urls.optString("wss:///ndt/v7/upload").ifBlank { null } ?: continue
                        val host = secureWebSocketUri(download)?.host ?: continue
                        if (secureWebSocketUri(upload) == null) continue
                        val city = result.optJSONObject("location")?.optString("city")?.ifBlank { null }
                        servers += LocatedServer(host, city, download, upload)
                    }
                }
            } catch (e: JSONException) {
                throw SpeedTestException("M-Lab's server locator sent an unreadable answer", e)
            }
            if (servers.isEmpty()) throw SpeedTestException("M-Lab didn't offer a test server")
            return servers
        }
    }
}
