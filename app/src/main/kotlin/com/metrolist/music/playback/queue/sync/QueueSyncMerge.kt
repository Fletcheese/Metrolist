/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import com.metrolist.music.db.entities.QueueSyncEntity

/**
 * FletchTunes queue sync: what to do with one queue, given this device's copy and the server's.
 * Pure functions, so the rules are unit-tested on their own (QueueSyncMergeTest).
 *
 * Content: the server keeps a revision per queue. This device remembers the revision its copy
 * was last equal to ([QueueSyncEntity.baseRev]) and that copy's hash. A side "changed" if it
 * moved on from that common base: locally, the content hash differs from the synced hash; on
 * the server, the revision is higher. One side changed: it wins. Both changed to different
 * content: a genuine conflict, which the user settles. So a newer edit wins without asking,
 * and only edits made on two devices since they last agreed ever prompt.
 *
 * Playback (current song, position, last played) never conflicts: the newer timestamp wins.
 */
sealed interface ContentDecision {
    data object None : ContentDecision
    /** Send this device's content (creates the queue on the server at base revision 0). */
    data object Push : ContentDecision
    /** Send this device's deletion. */
    data object PushDelete : ContentDecision
    /** Take the server's copy: create, overwrite or delete the local queue to match. */
    data class ApplyRemote(val remote: RemoteQueue) : ContentDecision
    /** Both sides already agree; just record the server's revision. */
    data class Adopt(val remote: RemoteQueue) : ContentDecision
    /** Both sides changed since they last agreed. Ask the user. */
    data class Conflict(val remote: RemoteQueue) : ContentDecision
}

enum class PlaybackDecision { None, Push, ApplyRemote }

/**
 * @param localHash this device's content hash for the queue, or null if it has no such queue.
 * @param state this device's sync state for the queue, or null if it has never been synced.
 * @param remote the server's row, or null if the server did not send one (it has none, or it
 *   has not changed since the last pull).
 */
fun decideContent(localHash: String?, state: QueueSyncEntity?, remote: RemoteQueue?): ContentDecision {
    val remoteHash = remote?.content?.takeUnless { remote.deleted }?.hash()

    if (state == null) {
        if (localHash == null) {
            return when {
                remote == null -> ContentDecision.None
                remote.deleted -> ContentDecision.Adopt(remote)
                else -> ContentDecision.ApplyRemote(remote)
            }
        }
        return when {
            remote == null -> ContentDecision.Push
            remoteHash == localHash -> ContentDecision.Adopt(remote)
            else -> ContentDecision.Conflict(remote)
        }
    }

    val remoteChanged = remote != null && remote.rev > state.baseRev

    if (state.deleted) {
        val deletionSynced = state.syncedHash == TOMBSTONE_HASH
        return when {
            deletionSynced && remoteChanged && !remote!!.deleted -> ContentDecision.ApplyRemote(remote)
            deletionSynced -> ContentDecision.None
            remote != null && remote.deleted -> ContentDecision.Adopt(remote)
            remoteChanged -> ContentDecision.Conflict(remote!!)
            else -> ContentDecision.PushDelete
        }
    }

    if (localHash == null) {
        // The queue went without a tombstone (a restored backup, say): take the server's copy.
        return if (remote != null && !remote.deleted) ContentDecision.ApplyRemote(remote) else ContentDecision.None
    }

    val localChanged = localHash != state.syncedHash
    return when {
        !remoteChanged -> if (localChanged) ContentDecision.Push else ContentDecision.None
        !localChanged -> ContentDecision.ApplyRemote(remote!!)
        remoteHash == localHash -> ContentDecision.Adopt(remote!!)
        else -> ContentDecision.Conflict(remote!!)
    }
}

fun decidePlayback(localExists: Boolean, state: QueueSyncEntity?, remote: RemoteQueue?): PlaybackDecision {
    if (!localExists || state == null || state.deleted) return PlaybackDecision.None
    val remoteNewer = remote != null && !remote.deleted && remote.playback != null &&
        remote.playbackAt > maxOf(state.playbackAt, state.playbackSyncedAt)
    return when {
        remoteNewer -> PlaybackDecision.ApplyRemote
        state.playbackAt > state.playbackSyncedAt -> PlaybackDecision.Push
        else -> PlaybackDecision.None
    }
}
