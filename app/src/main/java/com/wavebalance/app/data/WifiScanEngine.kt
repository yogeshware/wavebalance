package com.wavebalance.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.wavebalance.app.model.ScanFreshness
import com.wavebalance.app.model.ScanStatus
import androidx.core.content.ContextCompat
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ActiveConnectionInfo
import com.wavebalance.app.model.ChannelPlan
import com.wavebalance.app.model.ChannelWidth
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.InformationElements
import com.wavebalance.app.model.RawInformationElement
import com.wavebalance.app.model.WifiStandard
import com.wavebalance.app.model.withConnectedBssid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WifiScanEngine(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _accessPoints = MutableStateFlow<List<AccessPoint>>(emptyList())
    val accessPoints: StateFlow<List<AccessPoint>> = _accessPoints.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnectionInfo?>(null)
    val activeConnection: StateFlow<ActiveConnectionInfo?> = _activeConnection.asStateFlow()

    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val _isMockMode = MutableStateFlow(false)
    val isMockMode: StateFlow<Boolean> = _isMockMode.asStateFlow()

    private val _taggedHomeBssids = MutableStateFlow<Set<String>>(emptySet())
    val taggedHomeBssids: StateFlow<Set<String>> = _taggedHomeBssids.asStateFlow()

    private var lastScanTime: Long? = null
    private val scanFreshness = ScanFreshness()
    private var mockScanJob: Job? = null
    private var throttleJob: Job? = null
    private val scanCooldownMs = 25_000L // Android 4-scans/2-min rate limit (~30s window)

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                processScanResults(success)
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerReceivers()
        observeNetworkCapabilities()
        updateActiveConnectionInfo()
    }

    fun setMockMode(enabled: Boolean) {
        if (_isMockMode.value == enabled) return
        mockScanJob?.cancel()
        mockScanJob = null
        _isMockMode.value = enabled
        _accessPoints.value = emptyList()
        _activeConnection.value = null
        _scanStatus.value = ScanStatus.Idle
        scanFreshness.reset()
        if (enabled) {
            _accessPoints.value = MockWifiDataProvider.getMockAccessPoints()
            _activeConnection.value = MockWifiDataProvider.getMockActiveConnection()
            _scanStatus.value = ScanStatus.Success(_accessPoints.value.size, System.currentTimeMillis())
        } else {
            updateActiveConnectionInfo()
            refreshCachedScanResults()
        }
    }

    fun simulateChannelMigration(newChannel: Int, newWidth: ChannelWidth, band: FrequencyBand, centerFrequencyMhz: Int) {
        if (!_isMockMode.value) return
        val active = _activeConnection.value ?: return
        val block = ChannelPlan.blocksForPrimary(newChannel, band, newWidth)
            .firstOrNull { it.centerFrequencyMhz == centerFrequencyMhz } ?: return
        val newFreq = block.primaryFrequencyMhz
        _activeConnection.value = active.copy(
            channel = newChannel,
            band = band,
            frequencyMhz = newFreq,
            channelWidth = newWidth
        )
        _accessPoints.value = _accessPoints.value.map { ap ->
            if (ap.isConnected) {
                ap.copy(
                    channel = newChannel,
                    band = band,
                    frequencyMhz = newFreq,
                    centerFrequencyMhz = centerFrequencyMhz,
                    channelWidth = newWidth
                )
            } else ap
        }
    }

    fun simulateRoam(candidateBssid: String, candidateChannel: Int, candidateBand: FrequencyBand, candidateRssi: Int) {
        val active = _activeConnection.value ?: return
        val newFreq = FrequencyBand.channelToFrequency(candidateChannel, candidateBand)
        val candidateAp = _accessPoints.value.firstOrNull { it.bssid.equals(candidateBssid, ignoreCase = true) }
        _activeConnection.value = active.copy(
            bssid = candidateBssid,
            channel = candidateChannel,
            band = candidateBand,
            frequencyMhz = newFreq,
            rssi = candidateRssi,
            channelWidth = candidateAp?.channelWidth ?: active.channelWidth
        )
        _accessPoints.value = _accessPoints.value.map { ap ->
            when {
                ap.bssid.equals(candidateBssid, ignoreCase = true) -> ap.copy(isConnected = true, rssi = candidateRssi)
                ap.bssid.equals(active.bssid, ignoreCase = true) -> ap.copy(isConnected = false)
                else -> ap
            }
        }
    }

    fun simulateWalkDegradation() {
        val active = _activeConnection.value ?: return
        // Drop active RSSI to -78 dBm to trigger sticky client syndrome
        _activeConnection.value = active.copy(rssi = -78)
        _accessPoints.value = _accessPoints.value.map { ap ->
            if (ap.isConnected) ap.copy(rssi = -78) else ap
        }
    }

    fun toggleHomeTag(bssid: String) {
        val current = _taggedHomeBssids.value.toMutableSet()
        if (current.contains(bssid)) {
            current.remove(bssid)
        } else {
            current.add(bssid)
        }
        _taggedHomeBssids.value = current

        // Update current access points list with new tag
        _accessPoints.value = _accessPoints.value.map { ap ->
            if (ap.bssid == bssid) ap.copy(isUserTaggedHome = current.contains(bssid)) else ap
        }
    }

    fun hasPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val nearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return fineLocation && nearbyDevices
    }

    fun isLocationServiceEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    fun triggerScan() {
        if (_isMockMode.value) {
            mockScanJob?.cancel()
            _scanStatus.value = ScanStatus.Scanning
            mockScanJob = scope.launch {
                delay(800)
                if (!_isMockMode.value) return@launch
                _accessPoints.value = MockWifiDataProvider.getMockAccessPoints().shuffled()
                _activeConnection.value = MockWifiDataProvider.getMockActiveConnection()
                _scanStatus.value = ScanStatus.Success(_accessPoints.value.size, System.currentTimeMillis())
            }
            return
        }

        if (!hasPermissions()) {
            _scanStatus.value = ScanStatus.Error("Location/Nearby permissions required to scan.")
            return
        }

        val wifi = wifiManager
        if (wifi == null || !wifi.isWifiEnabled) {
            _scanStatus.value = ScanStatus.Error("Wi-Fi is currently disabled on device.")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val elapsed = lastScanTime?.let { now - it }
        if (elapsed != null && elapsed < scanCooldownMs) {
            val remainingSec = ((scanCooldownMs - elapsed) / 1000).toInt() + 1
            _scanStatus.value = ScanStatus.Throttled(remainingSec)
            // Still update with available cached results
            refreshCachedScanResults()
            countDownThrottle(retryAt = now - elapsed + scanCooldownMs)
            return
        }

        _scanStatus.value = ScanStatus.Scanning
        val started = try {
            @Suppress("DEPRECATION")
            wifi.startScan()
        } catch (e: Exception) {
            false
        }

        lastScanTime = now

        if (!started) {
            // Android throttled the hardware scan or system is busy; read cached results immediately
            processScanResults(resultsUpdated = false)
        }
    }

    /**
     * Keeps the Throttled countdown true while it's shown, then labels the results as
     * cached once a scan may be requested again. Anything newer (a fresh scan, an error,
     * a mode switch) replaces Throttled and ends the countdown.
     */
    private fun countDownThrottle(retryAt: Long) {
        throttleJob?.cancel()
        throttleJob = scope.launch {
            while (true) {
                val shown = _scanStatus.value as? ScanStatus.Throttled ?: return@launch
                val left = retryAt - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    _scanStatus.value = ScanStatus.Cached(_accessPoints.value.size, scanFreshness.lastMeasuredAt)
                    return@launch
                }
                val seconds = (left / 1000).toInt() + 1
                if (shown.secondsCooldown != seconds) _scanStatus.value = ScanStatus.Throttled(seconds)
                delay(250)
            }
        }
    }

    fun refreshCachedScanResults() {
        if (_isMockMode.value) return
        processScanResults(resultsUpdated = false)
    }

    private fun processScanResults(resultsUpdated: Boolean) {
        if (_isMockMode.value) return
        val wifi = wifiManager ?: return
        if (!hasPermissions()) {
            _scanStatus.value = ScanStatus.Error("Location/Nearby permissions required to read scans.")
            return
        }

        val rawResults: List<ScanResult> = try {
            wifi.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            _scanStatus.value = ScanStatus.Error("Wi-Fi scan permission was denied; previous results may be stale.")
            return
        }

        val activeBssid = _activeConnection.value?.bssid ?: ""
        val homeTags = _taggedHomeBssids.value

        val mapped = rawResults.map { scan ->
            val wifiStd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WifiStandard.fromScanResultStandard(scan.wifiStandard, scan.frequency)
            } else {
                WifiStandard.fromScanResultStandard(0, scan.frequency)
            }

            val channelWidth = ChannelWidth.fromScanResult(scan.channelWidth)
            // centerFreq0 is 0 for 20 MHz channels, where the primary frequency is the centre
            val centerFrequency = if (scan.centerFreq0 > 0) scan.centerFreq0 else scan.frequency
            val advertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InformationElements.parse(
                    scan.informationElements.map { ie ->
                        val buffer = ie.bytes
                        val bytes = ByteArray(buffer.remaining())
                        buffer.duplicate().get(bytes)
                        RawInformationElement(ie.id, ie.idExt, bytes)
                    }
                )
            } else null
            val isConnected = activeBssid.isNotBlank() && scan.BSSID.equals(activeBssid, ignoreCase = true)
            val isHome = homeTags.contains(scan.BSSID)

            AccessPoint(
                bssid = scan.BSSID ?: "00:00:00:00:00:00",
                ssid = scan.SSID ?: "",
                rssi = scan.level,
                frequencyMhz = scan.frequency,
                channel = FrequencyBand.frequencyToChannel(scan.frequency),
                band = FrequencyBand.fromFrequency(scan.frequency),
                standard = wifiStd,
                channelWidth = channelWidth,
                centerFrequencyMhz = centerFrequency,
                capabilities = scan.capabilities ?: "",
                advertised = advertised,
                isConnected = isConnected,
                isUserTaggedHome = isHome,
                timestamp = scan.timestamp
            )
        }.sortedByDescending { it.rssi }

        _accessPoints.value = mapped
        _scanStatus.value = scanFreshness.onResults(
            previous = _scanStatus.value,
            count = mapped.size,
            resultsUpdated = resultsUpdated,
            resultTimestampMicros = rawResults.maxOfOrNull { it.timestamp },
            elapsedRealtimeMicros = SystemClock.elapsedRealtimeNanos() / 1000,
            wallClockMillis = System.currentTimeMillis()
        )
        updateActiveConnectionInfo()
    }

    fun updateActiveConnectionInfo() {
        if (_isMockMode.value) return
        val wifi = wifiManager ?: return
        val info: WifiInfo? = try {
            @Suppress("DEPRECATION")
            wifi.connectionInfo
        } catch (e: Exception) {
            null
        }

        if (info != null && info.networkId != -1 && info.bssid != null && info.bssid != "02:00:00:00:00:00") {
            val freq = info.frequency
            val wifiStd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WifiStandard.fromScanResultStandard(info.wifiStandard, freq)
            } else {
                WifiStandard.UNKNOWN
            }

            val rxSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps else -1
            val txSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps else -1

            val ipInt = info.ipAddress
            val ipStr = if (ipInt != 0) {
                "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
            } else ""

            val cleanSsid = info.ssid?.removeSurrounding("\"") ?: ""
            val matchingAp = _accessPoints.value.firstOrNull { it.bssid.equals(info.bssid, ignoreCase = true) }
                ?: _accessPoints.value.firstOrNull { cleanSsid.isNotBlank() && it.ssid.equals(cleanSsid, ignoreCase = true) && it.frequencyMhz == freq }
                ?: _accessPoints.value.firstOrNull { cleanSsid.isNotBlank() && it.ssid.equals(cleanSsid, ignoreCase = true) }

            // Android only reports channel width in scan results, so it stays unknown
            // until the connected AP appears in one
            val detectedWidth = matchingAp?.channelWidth ?: ChannelWidth.UNKNOWN
            val maxTx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedTxLinkSpeedMbps else -1
            val maxRx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.maxSupportedRxLinkSpeedMbps else -1

            _activeConnection.value = ActiveConnectionInfo(
                ssid = info.ssid ?: "",
                bssid = info.bssid ?: "",
                rssi = info.rssi,
                frequencyMhz = freq,
                linkSpeedMbps = info.linkSpeed,
                rxLinkSpeedMbps = rxSpeed,
                txLinkSpeedMbps = txSpeed,
                channel = FrequencyBand.frequencyToChannel(freq),
                band = FrequencyBand.fromFrequency(freq),
                standard = if (wifiStd != WifiStandard.UNKNOWN) wifiStd else (matchingAp?.standard ?: WifiStandard.UNKNOWN),
                ipAddress = ipStr,
                channelWidth = detectedWidth,
                maxSupportedTxLinkSpeedMbps = maxTx,
                maxSupportedRxLinkSpeedMbps = maxRx
            )
        } else {
            _activeConnection.value = null
        }
        _accessPoints.value = _accessPoints.value.withConnectedBssid(_activeConnection.value?.bssid)
    }

    private fun registerReceivers() {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            context.registerReceiver(scanReceiver, filter)
        } catch (e: Exception) {
            // Ignore if registration failed in background
        }
    }

    private fun observeNetworkCapabilities() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scope.launch {
                    updateActiveConnectionInfo()
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    // A handover may already have another Wi-Fi connection. The refresh
                    // also respects mock mode and updates cached AP connection badges.
                    updateActiveConnectionInfo()
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            // Callback registration fallback
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(scanReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        scope.cancel()
    }
}
