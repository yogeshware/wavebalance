package com.wavebalance.app

import com.wavebalance.app.model.AccessPoint
import com.wavebalance.app.model.NetworkGroups
import com.wavebalance.app.model.withConnectedBssid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPointConnectionTest {
    private val previous = AccessPoint("00:11:22:33:44:55", "Old network", -50, 5180, isConnected = true)
    private val current = AccessPoint("AC:12:34:56:78:90", "New network", -55, 5745)
    private val scan = listOf(previous, current)

    @Test
    fun connectionChange_updatesCachedBadgesAndOwnNetworkExclusion() {
        val updated = scan.withConnectedBssid(current.bssid.lowercase())

        assertEquals(listOf(current.bssid), updated.filter { it.isConnected }.map { it.bssid })
        assertEquals(setOf(current.bssid.lowercase()), NetworkGroups.ownNetwork(updated, current.bssid, current.ssid))
        assertEquals(scan.map { it.timestamp }, updated.map { it.timestamp })
        assertEquals(scan.map { it.rssi }, updated.map { it.rssi })
    }

    @Test
    fun disconnection_clearsCachedBadgesAndOwnNetworkExclusion() {
        val updated = scan.withConnectedBssid(null)

        assertTrue(updated.none { it.isConnected })
        assertTrue(NetworkGroups.ownNetwork(updated, null, null).isEmpty())
    }

    @Test
    fun missingOrRedactedConnection_doesNotLeaveTheOldApConnected() {
        for (bssid in listOf("", "AB:CD:EF:12:34:56")) {
            assertTrue(scan.withConnectedBssid(bssid).none { it.isConnected })
        }
    }
}
