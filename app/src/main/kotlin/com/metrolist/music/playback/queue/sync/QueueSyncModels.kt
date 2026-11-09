/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import com.metrolist.music.models.MediaMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * FletchTunes queue sync: the wire format shared with the `fletchtunes-sync` Worker
 * (fletcheese-dev `apps/fletchtunes/sync-worker/`). The server stores [QueueContent] and
 * [PlaybackState] as opaque JSON, so their fields are defined here only.
 */

val SyncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Enough of a song for another device to show and play it without looking it up first. */
@Serializable
data class SyncSong(
    val id: String,
    val title: String = "",
    val artists: List<SyncArtist> = emptyList(),
    val duration: Int = -1,
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val explicit: Boolean = false,
    val musicVideoType: String? = null,
    val isEpisode: Boolean = false,
)

@Serializable
data class SyncArtist(val id: String? = null, val name: String)

/** The part of a queue whose edits can conflict: what is in it, in what order, and its name. */
@Serializable
data class QueueContent(
    val title: String,
    val sourceKey: String? = null,
    val shuffled: Boolean = false,
    /** In play order, as QueueBoard stores them. */
    val songs: List<SyncSong>,
    /** For a shuffled queue, each song's place in the real order (QueueBoard's shuffle file). */
    val shuffleOrder: List<Int>? = null,
    val createdAt: Long = 0L,
)

/** Where a queue is up to. Newest wins; never a conflict. */
@Serializable
data class PlaybackState(
    val currentIndex: Int = 0,
    val currentSongId: String? = null,
    val positionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
)

@Serializable
data class RemoteQueue(
    val id: String,
    val rev: Long,
    val deleted: Boolean = false,
    val content: QueueContent? = null,
    val editedAt: Long = 0L,
    val editedBy: String? = null,
    val playback: PlaybackState? = null,
    val playbackAt: Long = 0L,
    val playbackBy: String? = null,
    val serverUpdatedAt: Long = 0L,
)

@Serializable
data class ListResponse(val serverTime: Long, val queues: List<RemoteQueue>)

@Serializable
data class QueueResponse(val queue: RemoteQueue? = null, val conflict: Boolean = false)

@Serializable
data class PutBody(
    val baseRev: Long? = null,
    val force: Boolean? = null,
    val content: QueueContent? = null,
    val deleted: Boolean? = null,
    val editedAt: Long? = null,
    val editedBy: String? = null,
    val playback: PlaybackState? = null,
    val playbackAt: Long? = null,
    val playbackBy: String? = null,
)

/** [com.metrolist.music.db.entities.QueueSyncEntity.syncedHash] of a queue whose deletion the server has. */
const val TOMBSTONE_HASH = "deleted"

/**
 * Identifies a queue's content: title, source, shuffle flag, song ids in order and shuffle
 * order. Song metadata is left out, so a fresher thumbnail on one device is not an edit.
 */
fun contentHash(
    title: String,
    sourceKey: String?,
    shuffled: Boolean,
    songIds: List<String>,
    shuffleOrder: List<Int>?,
): String {
    val canonical = buildString {
        append(title).append('\u0000')
        append(sourceKey.orEmpty()).append('\u0000')
        append(shuffled).append('\u0000')
        songIds.joinTo(this, ",")
        append('\u0000')
        shuffleOrder?.joinTo(this, ",")
    }
    return MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

fun QueueContent.hash(): String =
    contentHash(title, sourceKey, shuffled, songs.map { it.id }, shuffleOrder?.takeIf { shuffled })

fun MediaMetadata.toSyncSong() = SyncSong(
    id = id,
    title = title,
    artists = artists.map { SyncArtist(it.id, it.name) },
    duration = duration,
    thumbnailUrl = thumbnailUrl,
    albumId = album?.id,
    albumTitle = album?.title,
    explicit = explicit,
    musicVideoType = musicVideoType,
    isEpisode = isEpisode,
)

fun SyncSong.toMediaMetadata() = MediaMetadata(
    id = id,
    title = title.ifEmpty { id },
    artists = artists.map { MediaMetadata.Artist(it.id, it.name) },
    duration = duration,
    thumbnailUrl = thumbnailUrl,
    album = albumId?.let { MediaMetadata.Album(it, albumTitle.orEmpty()) },
    explicit = explicit,
    musicVideoType = musicVideoType,
    isEpisode = isEpisode,
)
