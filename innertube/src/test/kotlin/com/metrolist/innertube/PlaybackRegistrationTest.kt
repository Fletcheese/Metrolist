package com.metrolist.innertube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRegistrationTest {
    @Test
    fun eachListenFetchesFreshAuthenticatedTrackingWithoutAStreamCache() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/player")) {
                respond(playerResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("")
            }
        }
        client(engine).use { client ->
            val api = InnerTube(client).apply { cookie = "SAPISID=test-session" }
            assertNotNull(api.registerPlayback("song", "first-play"))
            api.registerPlayback("song", "second-play")
            assertEquals(
                listOf("/youtubei/v1/player", "/api/stats/playback", "/youtubei/v1/player", "/api/stats/playback"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            engine.requestHistory.forEach { assertNotNull(it.headers[HttpHeaders.Authorization]) }
            assertEquals("first-play", engine.requestHistory[1].url.parameters["cpn"])
            assertEquals("second-play", engine.requestHistory[3].url.parameters["cpn"])
        }
    }

    @Test
    fun privacyIsCheckedBeforeAndAfterFetchingTracking() = runBlocking {
        var enabled = false
        val engine = MockEngine {
            enabled = false
            respond(playerResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        client(engine).use { client ->
            val api = InnerTube(client)
            assertNull(api.registerPlayback("song", "nonce"))
            api.cookie = "SAPISID=test-session"
            assertNull(api.registerPlayback("song", "nonce") { enabled })
            assertTrue(engine.requestHistory.isEmpty())
            enabled = true
            assertNull(api.registerPlayback("song", "nonce") { enabled })
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun accountChangeDuringTrackingFetchPreventsRegistration() = runBlocking {
        lateinit var api: InnerTube
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            api.cookie = "SAPISID=another-session"
            respond(playerResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        client(engine).use { client ->
            api = InnerTube(client).apply { cookie = "SAPISID=test-session" }
            val registration = launch { api.registerPlayback("song", "nonce") }
            registration.join()
            assertTrue(registration.isCancelled)
            assertEquals(listOf("/youtubei/v1/player"), paths)
        }
    }

    @Test
    fun rejectsUntrustedTrackingUrls() = runBlocking {
        for (url in listOf(
            "https://example.com/api/stats/playback", "http://s.youtube.com/api/stats/playback",
            "https://s.youtube.com:444/api/stats/playback", "https://user:pass@s.youtube.com/api/stats/playback",
            "https://s.youtube.com/api/stats/watchtime",
        )) {
            val engine = MockEngine {
                respond(playerResponse(url), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            client(engine).use { client ->
                val api = InnerTube(client).apply { cookie = "SAPISID=test-session" }
                val result = runCatching { api.registerPlayback("song", "nonce") }
                assertTrue(url, result.exceptionOrNull() is IllegalArgumentException)
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun watchtimeUsesTheSameNonceAndCarriesProgress() = runBlocking {
        val body =
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"ep","lengthSeconds":"600"},""" +
                """"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?docid=ep"},""" +
                """"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=ep&ei=x"},""" +
                """"videostatsDefaultFlushIntervalSeconds":40}}"""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/player")) {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("", status = io.ktor.http.HttpStatusCode.NoContent)
            }
        }
        client(engine).use { client ->
            val api = InnerTube(client).apply { cookie = "SAPISID=test-session" }
            val session = checkNotNull(api.registerPlayback("ep", "nonce-1"))
            assertEquals(40_000L, session.flushIntervalMs)
            assertTrue(session.sendWatchtime(listOf(0.0 to 45.5, 50.0 to 60.0), 60.0, 70.0, playing = false, final = true))
            val ping = engine.requestHistory.last().url
            assertEquals("/api/stats/watchtime", ping.encodedPath)
            assertEquals("nonce-1", ping.parameters["cpn"])
            assertEquals("0.000,50.000", ping.parameters["st"])
            assertEquals("45.500,60.000", ping.parameters["et"])
            assertEquals("60.000", ping.parameters["cmt"])
            assertEquals("1", ping.parameters["final"])
            assertEquals("paused", ping.parameters["state"])
            assertEquals("600", ping.parameters["len"])
            assertEquals("ep", ping.parameters["docid"])
            assertNotNull(engine.requestHistory.last().headers[HttpHeaders.Authorization])
            api.cookie = "SAPISID=another-session"
            assertFalse(session.sendWatchtime(listOf(60.0 to 70.0), 70.0, 80.0, playing = true, final = false))
        }
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun playerResponse(url: String = "https://s.youtube.com/api/stats/playback?docid=song") =
        """{"playabilityStatus":{"status":"OK"},"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"$url"}}}"""
}
