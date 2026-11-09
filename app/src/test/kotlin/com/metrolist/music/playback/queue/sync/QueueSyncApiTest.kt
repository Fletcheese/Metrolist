package com.metrolist.music.playback.queue.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSyncApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val row =
        """{"id":"q1","rev":2,"deleted":false,"content":{"title":"Mix","songs":[{"id":"a","title":"A"}]},""" +
            """"editedAt":10,"editedBy":"Phone","playback":{"currentIndex":0},"playbackAt":5,"playbackBy":"Phone","serverUpdatedAt":11}"""

    private fun api(handler: MockEngine) = QueueSyncApi("https://sync.test/", "secret", HttpClient(handler) { expectSuccess = false })

    @Test
    fun listSendsTheTokenAndCursorAndParsesRows() = runBlocking {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond("""{"serverTime":99,"queues":[$row],"extra":"ignored"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val result = api(engine).list(since = 42)
        assertEquals("https://sync.test/v1/queues?since=42", seen!!.url.toString())
        assertEquals("Bearer secret", seen!!.headers[HttpHeaders.Authorization])
        assertEquals(99L, result.serverTime)
        assertEquals("Mix", result.queues.single().content!!.title)
        assertEquals("Phone", result.queues.single().editedBy)
    }

    @Test
    fun putEncodesOnlyWhatIsSetAndReadsTheRow() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("https://sync.test/v1/queues/q1", request.url.toString())
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"queue":$row}""", HttpStatusCode.OK, jsonHeaders)
        }
        val result = api(engine).put("q1", PutBody(playback = PlaybackState(currentIndex = 3), playbackAt = 7, playbackBy = "Tablet"))
        assertTrue(result is PutResult.Ok)
        val sent = Json.parseToJsonElement(body).jsonObject
        // The Worker treats a present "content" or "deleted" as a content write, so they must be absent here.
        assertFalse("content" in sent)
        assertFalse("deleted" in sent)
        assertFalse("force" in sent)
        assertEquals(setOf("playback", "playbackAt", "playbackBy"), sent.keys)
    }

    @Test
    fun aConflictCarriesTheServersRow() = runBlocking {
        val engine = MockEngine { respond("""{"conflict":true,"queue":$row}""", HttpStatusCode.Conflict, jsonHeaders) }
        val result = api(engine).put("q1", PutBody(baseRev = 1, content = QueueContent("Mix", songs = emptyList())))
        assertEquals(2L, (result as PutResult.Conflict).queue!!.rev)
    }

    @Test
    fun aConflictWithNoRowAndNotFoundAreDistinct() = runBlocking {
        val conflict = api(MockEngine { respond("""{"conflict":true,"queue":null}""", HttpStatusCode.Conflict, jsonHeaders) })
        assertEquals(PutResult.Conflict(null), conflict.put("q1", PutBody(baseRev = 3, deleted = true)))
        val missing = api(MockEngine { respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders) })
        assertEquals(PutResult.NotFound, missing.put("q1", PutBody(playback = PlaybackState(), playbackAt = 1)))
    }

    @Test(expected = QueueSyncException::class)
    fun aRefusedTokenIsAnError() {
        runBlocking {
            api(MockEngine { respond("""{"error":"unauthorised"}""", HttpStatusCode.Unauthorized, jsonHeaders) }).list(0)
        }
    }

    @Test
    fun syncSongsRoundTripThroughMediaMetadata() {
        val song = SyncSong(
            id = "a",
            title = "Song",
            artists = listOf(SyncArtist("UC1", "Artist")),
            duration = 200,
            thumbnailUrl = "https://i/x.jpg",
            albumId = "MPRE1",
            albumTitle = "Album",
            explicit = true,
            isEpisode = true,
        )
        assertEquals(song, song.toMediaMetadata().toSyncSong())
    }
}
