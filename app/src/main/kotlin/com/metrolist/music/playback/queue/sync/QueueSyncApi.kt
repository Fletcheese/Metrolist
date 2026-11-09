/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException

sealed interface PutResult {
    data class Ok(val queue: RemoteQueue) : PutResult
    /** The server's row moved on (or, with a null queue, it has none at that revision). */
    data class Conflict(val queue: RemoteQueue?) : PutResult
    data object NotFound : PutResult
}

class QueueSyncException(message: String) : IOException(message)

/** The `fletchtunes-sync` Worker's two calls. [client] is injectable for tests (Ktor MockEngine). */
class QueueSyncApi(
    private val baseUrl: String,
    private val token: String,
    private val client: HttpClient,
) {
    suspend fun list(since: Long): ListResponse {
        val response = client.get("${baseUrl.trimEnd('/')}/v1/queues") {
            auth()
            parameter("since", since)
        }
        response.requireOk()
        return SyncJson.decodeFromString(ListResponse.serializer(), response.bodyAsText())
    }

    suspend fun put(id: String, body: PutBody): PutResult {
        val response = client.put("${baseUrl.trimEnd('/')}/v1/queues/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(PutBody.serializer(), body))
        }
        return when (response.status) {
            HttpStatusCode.OK -> PutResult.Ok(decodeQueue(response) ?: throw QueueSyncException("Empty reply"))
            HttpStatusCode.Conflict -> PutResult.Conflict(decodeQueue(response))
            HttpStatusCode.NotFound -> PutResult.NotFound
            else -> {
                response.requireOk()
                throw QueueSyncException("HTTP ${response.status.value}")
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun decodeQueue(response: HttpResponse): RemoteQueue? =
        SyncJson.decodeFromString(QueueResponse.serializer(), response.bodyAsText()).queue

    private fun HttpResponse.requireOk() {
        when {
            status == HttpStatusCode.Unauthorized -> throw QueueSyncException("Token refused")
            status.value !in 200..299 -> throw QueueSyncException("HTTP ${status.value}")
        }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
            }
            expectSuccess = false
        }
    }
}
