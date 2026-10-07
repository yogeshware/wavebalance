package com.wavebalance.app.model

sealed class ScanStatus {
    object Idle : ScanStatus()
    object Scanning : ScanStatus()
    data class Success(val count: Int, val timestamp: Long) : ScanStatus()
    data class Cached(val count: Int, val lastMeasuredAt: Long?) : ScanStatus()
    data class Throttled(val secondsCooldown: Int) : ScanStatus()
    data class Error(val message: String) : ScanStatus()
}

/** Keeps measurement age separate from the time a cached scan was read. */
class ScanFreshness {
    var lastMeasuredAt: Long? = null
        private set
    private var newestResultMicros = 0L

    fun reset() {
        lastMeasuredAt = null
        newestResultMicros = 0L
    }

    fun onResults(
        previous: ScanStatus,
        count: Int,
        resultsUpdated: Boolean,
        resultTimestampMicros: Long?,
        elapsedRealtimeMicros: Long,
        wallClockMillis: Long
    ): ScanStatus {
        val timestamp = resultTimestampMicros?.takeIf { it > 0 && it <= elapsedRealtimeMicros }
        val newer = timestamp != null && timestamp > newestResultMicros
        if (newer) {
            newestResultMicros = timestamp!!
            lastMeasuredAt = wallClockMillis - (elapsedRealtimeMicros - timestamp) / 1000
        } else if (resultsUpdated && count == 0) {
            // A successfully completed empty scan is still a new observation.
            lastMeasuredAt = wallClockMillis
        }
        if (resultsUpdated && (newer || count == 0)) {
            return ScanStatus.Success(count, lastMeasuredAt!!)
        }
        // A cache read cannot erase a failed or rate-limited scan request.
        return when (previous) {
            is ScanStatus.Throttled, is ScanStatus.Error -> previous
            else -> ScanStatus.Cached(count, lastMeasuredAt)
        }
    }
}
