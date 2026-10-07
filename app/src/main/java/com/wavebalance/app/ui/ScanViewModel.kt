package com.wavebalance.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wavebalance.app.data.MLabNdt7Server
import com.wavebalance.app.model.ScanStatus
import com.wavebalance.app.data.WifiScanEngine
import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.ActiveConnectionInfo
import com.wavebalance.app.model.FrequencyBand
import com.wavebalance.app.model.NetworkGroups
import com.wavebalance.app.model.RssiSample
import com.wavebalance.app.model.WifiVendorLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    val engine = WifiScanEngine(application)

    // Analysis uses the complete scan; Radar filters only change its displayed list.
    val allAccessPoints: StateFlow<List<AccessPoint>> = engine.accessPoints
    val activeConnection: StateFlow<ActiveConnectionInfo?> = engine.activeConnection
    val scanStatus: StateFlow<ScanStatus> = engine.scanStatus
    val isMockMode: StateFlow<Boolean> = engine.isMockMode

    private val _selectedBandFilter = MutableStateFlow<FrequencyBand?>(null)
    val selectedBandFilter: StateFlow<FrequencyBand?> = _selectedBandFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedAp = MutableStateFlow<AccessPoint?>(null)
    val selectedAp: StateFlow<AccessPoint?> = _selectedAp.asStateFlow()

    private val _rssiHistory = MutableStateFlow<Map<String, List<RssiSample>>>(emptyMap())
    val rssiHistory: StateFlow<Map<String, List<RssiSample>>> = _rssiHistory.asStateFlow()

    private val _roamingHistory = MutableStateFlow<List<com.wavebalance.app.model.RoamingEvent>>(emptyList())
    val roamingHistory: StateFlow<List<com.wavebalance.app.model.RoamingEvent>> = _roamingHistory.asStateFlow()

    private val _surveyPoints = MutableStateFlow<List<com.wavebalance.app.model.SurveyPoint>>(emptyList())
    val surveyPoints: StateFlow<List<com.wavebalance.app.model.SurveyPoint>> = _surveyPoints.asStateFlow()

    val surveyAnalytics: StateFlow<com.wavebalance.app.model.SurveyAnalytics> = _surveyPoints
        .combine(MutableStateFlow(Unit)) { points, _ ->
            com.wavebalance.app.model.SiteSurveyEngine.computeAnalytics(points)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.wavebalance.app.model.SiteSurveyEngine.computeAnalytics(emptyList())
        )

    private val _diagnosticState = MutableStateFlow(com.wavebalance.app.model.DiagnosticState())
    val diagnosticState: StateFlow<com.wavebalance.app.model.DiagnosticState> = _diagnosticState.asStateFlow()

    private val _isDiagnosticRunning = MutableStateFlow(false)
    val isDiagnosticRunning: StateFlow<Boolean> = _isDiagnosticRunning.asStateFlow()

    private var diagnosticJob: kotlinx.coroutines.Job? = null

    private var lastActiveConn: ActiveConnectionInfo? = null

    val stickyClientAlert: StateFlow<com.wavebalance.app.model.StickyClientAlert?> = combine(
        activeConnection,
        engine.accessPoints
    ) { conn, aps ->
        com.wavebalance.app.model.RoamingMonitorEngine.evaluateStickyClient(conn, aps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredAccessPoints: StateFlow<List<AccessPoint>> = combine(
        engine.accessPoints,
        _selectedBandFilter,
        _searchQuery
    ) { aps, band, query ->
        aps.filter { ap ->
            val matchesBand = band == null || ap.band == band
            val matchesQuery = query.isBlank() ||
                    ap.ssid.contains(query, ignoreCase = true) ||
                    ap.bssid.contains(query, ignoreCase = true) ||
                    WifiVendorLookup.getVendor(ap.bssid).contains(query, ignoreCase = true)
            matchesBand && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Lowercase BSSIDs of every radio the user's own router or mesh broadcasts, so they
    // aren't counted as interference
    val ownNetworkBssids: StateFlow<Set<String>> = combine(engine.accessPoints, activeConnection) { aps, conn ->
        NetworkGroups.ownNetwork(aps, conn?.bssid, conn?.cleanSsid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Other networks' radios on the connected channel (same band; channel numbers repeat across bands)
    val collisionCount: StateFlow<Int> = combine(engine.accessPoints, activeConnection, ownNetworkBssids) { aps, conn, own ->
        if (conn == null || conn.channel <= 0) 0
        else aps.count { it.band == conn.band && it.channel == conn.channel && it.bssid.lowercase() !in own }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalApCount: StateFlow<Int> = engine.accessPoints
        .combine(MutableStateFlow(Unit)) { aps, _ -> aps.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Scan timestamp of the last sample recorded per BSSID, so re-reading cached
    // scan results doesn't add duplicate samples
    private val lastScanSampleTime = mutableMapOf<String, Long>()

    init {
        WifiVendorLookup.useRegistry { application.assets.open(WifiVendorLookup.REGISTRY_ASSET) }
        viewModelScope.launch(Dispatchers.IO) { WifiVendorLookup.preload() }

        // Start background sampling loop for historical sparklines
        startRssiSampler()

        viewModelScope.launch {
            engine.accessPoints.collect { aps ->
                recordScanSamples(aps)
                // Keep the selected AP current; if it drops out of a scan, keep the last known values
                val selectedBssid = _selectedAp.value?.bssid
                if (selectedBssid != null) {
                    aps.firstOrNull { it.bssid.equals(selectedBssid, ignoreCase = true) }?.let { _selectedAp.value = it }
                }
            }
        }

        // Monitor roaming handovers
        viewModelScope.launch {
            activeConnection.collect { current ->
                val prev = lastActiveConn
                if (prev != null && current != null) {
                    val event = com.wavebalance.app.model.RoamingMonitorEngine.detectRoamingTransition(prev, current)
                    if (event != null) {
                        _roamingHistory.value = _roamingHistory.value + event
                    }
                }
                lastActiveConn = current
            }
        }
    }

    private fun startRssiSampler() {
        viewModelScope.launch {
            while (isActive) {
                delay(3000)
                recordCurrentSamples()
            }
        }
    }

    /**
     * Samples the connected AP every 3 s from a fresh WifiInfo read. Other APs are
     * only measured by scans, so they get a sample per scan in [recordScanSamples].
     */
    private fun recordCurrentSamples() {
        engine.updateActiveConnectionInfo()
        val now = System.currentTimeMillis()
        val currentHistory = _rssiHistory.value.toMutableMap()

        val active = activeConnection.value
        if (active != null && active.bssid.isNotBlank()) {
            // Simulated data has a fixed RSSI, so give it some movement; live readings are recorded as-is
            val variance = if (isMockMode.value) Random.nextInt(-2, 3) else 0
            currentHistory.appendSample(active.bssid, RssiSample(now, (active.rssi + variance).coerceIn(-95, -30)))
        }

        val selected = _selectedAp.value
        if (isMockMode.value && selected != null && !selected.bssid.equals(active?.bssid, ignoreCase = true)) {
            currentHistory.appendSample(selected.bssid, RssiSample(now, (selected.rssi + Random.nextInt(-2, 3)).coerceIn(-95, -30)))
        }

        _rssiHistory.value = currentHistory
    }

    private fun recordScanSamples(aps: List<AccessPoint>) {
        if (isMockMode.value) return
        val activeBssid = activeConnection.value?.bssid
        val now = System.currentTimeMillis()
        val currentHistory = _rssiHistory.value.toMutableMap()
        var changed = false
        for (ap in aps) {
            if (ap.bssid.equals(activeBssid, ignoreCase = true)) continue
            if (lastScanSampleTime[ap.bssid] == ap.timestamp) continue
            lastScanSampleTime[ap.bssid] = ap.timestamp
            currentHistory.appendSample(ap.bssid, RssiSample(now, ap.rssi))
            changed = true
        }
        if (changed) _rssiHistory.value = currentHistory
    }

    private fun MutableMap<String, List<RssiSample>>.appendSample(bssid: String, sample: RssiSample) {
        val history = (this[bssid] ?: emptyList()) + sample
        this[bssid] = history.takeLast(MAX_SAMPLES)
    }

    fun selectAccessPoint(ap: AccessPoint?) {
        _selectedAp.value = ap
    }

    fun hasPermissions(): Boolean = engine.hasPermissions()
    fun isLocationServiceEnabled(): Boolean = engine.isLocationServiceEnabled()

    fun triggerScan() {
        engine.triggerScan()
    }

    fun setBandFilter(band: FrequencyBand?) {
        _selectedBandFilter.value = band
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleMockMode(enabled: Boolean) {
        if (enabled == isMockMode.value) return
        _selectedAp.value = null
        _rssiHistory.value = emptyMap()
        _roamingHistory.value = emptyList()
        lastScanSampleTime.clear()
        lastActiveConn = null
        engine.setMockMode(enabled)
    }

    fun toggleHomeTag(bssid: String) {
        engine.toggleHomeTag(bssid)
    }

    fun simulateChannelMigration(newChannel: Int, newWidth: com.wavebalance.app.model.ChannelWidth, band: FrequencyBand, centerFrequencyMhz: Int) {
        engine.simulateChannelMigration(newChannel, newWidth, band, centerFrequencyMhz)
    }

    fun simulateRoamToCandidate() {
        val alert = stickyClientAlert.value
        if (alert != null && alert.candidateBssid.isNotBlank()) {
            engine.simulateRoam(alert.candidateBssid, alert.candidateChannel, alert.candidateBand, alert.candidateRssi)
        }
    }

    fun simulateWalkDegradation() {
        engine.simulateWalkDegradation()
    }

    fun shareAuditReport(context: android.content.Context) {
        val conn = activeConnection.value
        val recommendation = com.wavebalance.app.model.ChannelOptimizerEngine.evaluateBand(
            band = conn?.band ?: FrequencyBand.BAND_5_GHZ,
            allAps = engine.accessPoints.value,
            currentChannel = conn?.channel ?: 0,
            targetWidth = conn?.channelWidth ?: com.wavebalance.app.model.ChannelWidth.UNKNOWN,
            ownNetworkBssids = NetworkGroups.ownNetwork(engine.accessPoints.value, conn?.bssid, conn?.cleanSsid)
        )
        val markdown = com.wavebalance.app.model.RfAuditReportGenerator.generateMarkdownReport(
            activeConnection = activeConnection.value,
            allAps = engine.accessPoints.value,
            recommendation = recommendation
        )
        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, markdown)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "WaveBalance RF Airspace Audit - ${activeConnection.value?.cleanSsid ?: "Wi-Fi"}")
            type = "text/plain"
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "Share RF Airspace Audit Report")
        shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    fun addSurveyPoint(point: com.wavebalance.app.model.SurveyPoint) {
        _surveyPoints.value = _surveyPoints.value + point
    }

    fun removeSurveyPoint(id: String) {
        _surveyPoints.value = _surveyPoints.value.filter { it.id != id }
    }

    fun clearSurveyPoints() {
        _surveyPoints.value = emptyList()
    }

    fun populateSimulatedWalkthrough(ssid: String, bssid: String) {
        _surveyPoints.value = com.wavebalance.app.model.SiteSurveyEngine.generateSimulatedWalkthrough(ssid, bssid)
    }

    private val speedTestPrefs = application.getSharedPreferences("speed_test", android.content.Context.MODE_PRIVATE)
    private val appVersion: String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "unknown"
    }

    // M-Lab publishes full test results (including the IP address), so the user has to
    // agree once before the first one
    private val _mlabConsentGiven = MutableStateFlow(speedTestPrefs.getBoolean(KEY_MLAB_CONSENT, false))
    val mlabConsentGiven: StateFlow<Boolean> = _mlabConsentGiven.asStateFlow()

    fun giveMlabConsent() {
        speedTestPrefs.edit().putBoolean(KEY_MLAB_CONSENT, true).apply()
        _mlabConsentGiven.value = true
    }

    // The network test is real in simulated mode too: only the Wi-Fi scan data is simulated
    fun startFullDiagnostic() {
        check(_mlabConsentGiven.value) { "M-Lab consent is required before a full test" }
        runDiagnostic(com.wavebalance.app.model.SpeedDiagnosticEngine.runSpeedTest(MLabNdt7Server(appVersion)))
    }

    // Latency probes only: no ndt7 test runs, so nothing is published
    fun startQuickPing() {
        runDiagnostic(com.wavebalance.app.model.SpeedDiagnosticEngine.runPingTest(MLabNdt7Server(appVersion)))
    }

    private fun runDiagnostic(test: kotlinx.coroutines.flow.Flow<com.wavebalance.app.model.DiagnosticState>) {
        diagnosticJob?.cancel()
        _isDiagnosticRunning.value = true
        diagnosticJob = viewModelScope.launch {
            try {
                test.collect { state -> _diagnosticState.value = state }
            } finally {
                _isDiagnosticRunning.value = false
            }
        }
    }

    fun cancelDiagnostic() {
        diagnosticJob?.cancel()
        _isDiagnosticRunning.value = false
        _diagnosticState.value = com.wavebalance.app.model.DiagnosticState(
            phase = com.wavebalance.app.model.DiagnosticPhase.IDLE,
            progress = 0f
        )
    }

    companion object {
        private const val MAX_SAMPLES = 20
        private const val KEY_MLAB_CONSENT = "mlab_consent"
    }

    override fun onCleared() {
        super.onCleared()
        engine.unregister()
    }
}
