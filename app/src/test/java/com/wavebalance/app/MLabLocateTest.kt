package com.wavebalance.app

import com.wavebalance.app.data.MLabNdt7Server
import com.wavebalance.app.model.SpeedTestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class MLabLocateTest {

    private fun candidate(download: String, upload: String): JSONObject = JSONObject()
        .put("urls", JSONObject()
            .put("wss:///ndt/v7/download", download)
            .put("wss:///ndt/v7/upload", upload))

    @Test
    fun malformedAndInsecureCandidates_doNotDiscardAValidFallback() {
        val validDownload = "wss://valid.example/ndt/v7/download"
        val validUpload = "wss://valid.example/ndt/v7/upload"
        val results = JSONArray()
            .put(candidate("wss://bad host/ndt/v7/download", validUpload))
            .put(candidate(validDownload, "wss://bad host/ndt/v7/upload"))
            .put(candidate("ws://insecure.example/ndt/v7/download", validUpload))
            .put(candidate(validDownload, "ws://insecure.example/ndt/v7/upload"))
            .put(candidate("wss:///ndt/v7/download", validUpload))
            .put(candidate(validDownload, "wss:///ndt/v7/upload"))
            .put(candidate("wss://user:password@valid.example/download", validUpload))
            .put(candidate(validDownload, validUpload))
        val servers = MLabNdt7Server.parseLocateResponse(JSONObject().put("results", results).toString())

        assertEquals(1, servers.size)
        assertEquals("valid.example", servers.single().host)
    }

    @Test
    fun nonObjectCandidates_areSkipped() {
        val results = JSONArray().put(JSONObject.NULL).put("unusable")
            .put(candidate("wss://valid.example/download", "wss://valid.example/upload"))
        val servers = MLabNdt7Server.parseLocateResponse(JSONObject().put("results", results).toString())
        assertEquals("valid.example", servers.single().host)
    }

    @Test(expected = SpeedTestException::class)
    fun malformedUrlsWithoutAFallback_areAHandledTestError() {
        val results = JSONArray().put(candidate("wss://bad host/download", "wss://bad host/upload"))
        MLabNdt7Server.parseLocateResponse(JSONObject().put("results", results).toString())
    }

    // Shaped like a real locate v2 answer; tokens shortened
    private val response = """
        {"results":[
          {"machine":"mlab1-lga03.mlab-oti.measurement-lab.org",
           "location":{"city":"New York","country":"US"},
           "urls":{
             "ws:///ndt/v7/download":"ws://ndt-mlab1-lga03.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=a",
             "ws:///ndt/v7/upload":"ws://ndt-mlab1-lga03.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=b",
             "wss:///ndt/v7/download":"wss://ndt-mlab1-lga03.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=c",
             "wss:///ndt/v7/upload":"wss://ndt-mlab1-lga03.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=d"}},
          {"machine":"mlab2-iad05.mlab-oti.measurement-lab.org",
           "location":{"city":"Washington","country":"US"},
           "urls":{
             "wss:///ndt/v7/download":"wss://ndt-mlab2-iad05.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=e",
             "wss:///ndt/v7/upload":"wss://ndt-mlab2-iad05.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=f"}}
        ]}
    """.trimIndent()

    @Test
    fun readsEveryServerWithSecureUrls() {
        val servers = MLabNdt7Server.parseLocateResponse(response)

        assertEquals(listOf("New York", "Washington"), servers.map { it.city })
        val server = servers.first()

        assertEquals("ndt-mlab1-lga03.mlab-oti.measurement-lab.org", server.host)
        assertEquals("New York", server.city)
        assertEquals("wss://ndt-mlab1-lga03.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=c", server.downloadUrl)
        assertEquals("wss://ndt-mlab1-lga03.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=d", server.uploadUrl)
    }

    @Test
    fun skipsResultsWithoutSecureUrls() {
        val insecureOnly = """{"results":[{"location":{},"urls":{"ws:///ndt/v7/download":"ws://a/ndt/v7/download","ws:///ndt/v7/upload":"ws://a/ndt/v7/upload"}},
            {"urls":{"wss:///ndt/v7/download":"wss://b.example/ndt/v7/download","wss:///ndt/v7/upload":"wss://b.example/ndt/v7/upload"}}]}"""

        val servers = MLabNdt7Server.parseLocateResponse(insecureOnly)

        assertEquals(1, servers.size)
        assertEquals("b.example", servers[0].host)
        assertNull(servers[0].city)
    }

    @Test(expected = SpeedTestException::class)
    fun noResults_isATestError() {
        MLabNdt7Server.parseLocateResponse("""{"results":[]}""")
    }

    @Test(expected = SpeedTestException::class)
    fun unreadableAnswer_isATestError() {
        MLabNdt7Server.parseLocateResponse("<html>busy</html>")
    }
}
