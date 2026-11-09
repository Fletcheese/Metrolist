/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import android.content.Context
import com.metrolist.music.constants.DEFAULT_QUEUE_SYNC_URL
import com.metrolist.music.constants.QueueSyncCursorKey
import com.metrolist.music.constants.QueueSyncEnabledKey
import com.metrolist.music.constants.QueueSyncLastSuccessKey
import com.metrolist.music.constants.QueueSyncStatusKey
import com.metrolist.music.constants.QueueSyncTokenKey
import com.metrolist.music.constants.QueueSyncUrlKey
import com.metrolist.music.db.entities.QueueSyncEntity
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.playback.queue.QueueBoard
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** Two devices both changed [queueId] since they last agreed. The user picks which copy wins. */
data class SyncConflict(
    val queueId: String,
    val title: String,
    val remote: RemoteQueue,
    val localDeleted: Boolean,
)

/**
 * FletchTunes queue sync: keeps every queue the same on all of Fletcher's devices through the
 * `fletchtunes-sync` Worker. Local Room stays the source of truth for playback, and nothing here
 * ever waits on the network on the main thread or blocks playback; a failed sync is retried.
 *
 * One worker coroutine runs every sync, so two never overlap. It runs when:
 *  - the app comes to the foreground, and every [PERIOD_MS] while it stays there;
 *  - a queue's content is edited ([EDIT_DELAY_MS] later, so a burst of edits is one sync);
 *  - playback pauses or the queue switches ([FLUSH_DELAY_MS] later);
 *  - the app goes to the background, and on "Sync now";
 *  - after a failure, backing off from [RETRY_MIN_MS] to [RETRY_MAX_MS].
 * Position updates during playback are saved locally every 10 s and only sent on those triggers.
 *
 * Each sync pulls what changed on the server since the last one, then for every queue applies
 * [decideContent] and [decidePlayback]. See QueueSyncMerge.kt for the rules.
 */
@Singleton
class QueueSyncRepository @Inject constructor(
    private val queueBoard: QueueBoard,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _conflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val conflicts: StateFlow<List<SyncConflict>> = _conflicts.asStateFlow()

    private val client by lazy { QueueSyncApi.defaultClient() }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var dueAt = Long.MAX_VALUE
    @Volatile private var foreground = false
    private var failures = 0

    private enum class Choice { LOCAL, REMOTE }
    private val resolutions = mutableMapOf<String, Pair<SyncConflict, Choice>>()

    private sealed interface Outcome {
        data object Done : Outcome
        /** Not settled this time (the queue is playing here, say); pull it again next sync. */
        data object Deferred : Outcome
        data class Conflicted(val conflict: SyncConflict) : Outcome
    }

    init {
        scope.launch(Dispatchers.IO) { runLoop() }
        scope.launch {
            queueBoard.syncNudges.collect { nudge ->
                schedule(if (nudge == QueueBoard.SyncNudge.CONTENT) EDIT_DELAY_MS else FLUSH_DELAY_MS)
            }
        }
    }

    fun onForeground() {
        foreground = true
        schedule(0)
    }

    fun onBackground() {
        foreground = false
        schedule(0)
    }

    fun syncNow() = schedule(0)

    fun keepLocal(conflict: SyncConflict) = resolve(conflict, Choice.LOCAL)

    fun keepRemote(conflict: SyncConflict) = resolve(conflict, Choice.REMOTE)

    private fun resolve(conflict: SyncConflict, choice: Choice) {
        synchronized(lock) { resolutions[conflict.queueId] = conflict to choice }
        _conflicts.value = _conflicts.value.filterNot { it.queueId == conflict.queueId }
        schedule(0)
    }

    private fun schedule(delayMs: Long) {
        synchronized(lock) { dueAt = minOf(dueAt, System.currentTimeMillis() + delayMs) }
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        while (true) {
            val wait = synchronized(lock) { dueAt } .let { if (it == Long.MAX_VALUE) null else it - System.currentTimeMillis() }
            when {
                wait == null -> wake.receive()
                wait > 0 -> withTimeoutOrNull(wait) { wake.receive() }
                else -> {
                    synchronized(lock) { dueAt = Long.MAX_VALUE }
                    val ok = try {
                        syncOnce()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Queue sync failed")
                        setStatus("Failed: ${e.message ?: e.javaClass.simpleName}")
                        false
                    }
                    if (ok) {
                        failures = 0
                        if (foreground) schedule(PERIOD_MS)
                    } else {
                        failures++
                        if (foreground || failures <= BACKGROUND_RETRIES) {
                            schedule(minOf(RETRY_MIN_MS shl minOf(failures - 1, 10), RETRY_MAX_MS))
                        }
                    }
                }
            }
        }
    }

    /** One full sync. False if it failed and should be retried; true if done or switched off. */
    private suspend fun syncOnce(): Boolean {
        val store = context.dataStore
        val token = store.get(QueueSyncTokenKey, "").trim()
        val url = store.get(QueueSyncUrlKey, DEFAULT_QUEUE_SYNC_URL).trim().ifEmpty { DEFAULT_QUEUE_SYNC_URL }
        if (!store.get(QueueSyncEnabledKey, true)) {
            setStatus("Off")
            return true
        }
        if (token.isEmpty()) {
            setStatus("Not set up: paste the sync token")
            return true
        }
        val api = QueueSyncApi(url, token, client)
        val device = syncDeviceName(context)

        val chosen = synchronized(lock) { resolutions.values.toList().also { resolutions.clear() } }
        for ((conflict, choice) in chosen) {
            when (choice) {
                Choice.LOCAL -> {
                    val state = queueBoard.syncState(conflict.queueId)
                    if (state?.deleted == true) pushDelete(api, conflict.queueId, state, force = true)
                    else pushContent(api, conflict.queueId, force = true, device = device)
                }
                Choice.REMOTE -> queueBoard.applyRemote(conflict.remote, force = true)
            }
        }

        val cursor = store.get(QueueSyncCursorKey, 0L)
        val pulled = api.list(if (cursor > 0) maxOf(cursor - CURSOR_OVERLAP_MS, 0L) else 0L)
        val remotes = pulled.queues.associateBy { it.id }
        val (localIds, states) = queueBoard.syncSnapshot()

        var nextCursor = pulled.serverTime
        val conflicts = mutableListOf<SyncConflict>()
        for (id in localIds + states.keys + remotes.keys) {
            val remote = remotes[id]
            val outcome = try {
                syncQueue(api, id, id in localIds, states[id], remote, device)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // One bad queue must not stop the others syncing.
                Timber.tag(TAG).e(e, "Sync of queue $id failed")
                Outcome.Deferred
            }
            if (outcome != Outcome.Done && remote != null) nextCursor = minOf(nextCursor, remote.serverUpdatedAt - 1)
            if (outcome is Outcome.Conflicted) conflicts += outcome.conflict
        }
        _conflicts.value = conflicts

        context.safeDataStoreEdit {
            it[QueueSyncCursorKey] = nextCursor
            it[QueueSyncLastSuccessKey] = System.currentTimeMillis()
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        setStatus(
            buildString {
                append("Synced at $time")
                if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflict${if (conflicts.size == 1) "" else "s"} to settle")
            },
        )
        Timber.tag(TAG).d("Synced: ${pulled.queues.size} pulled, ${localIds.size} local, ${conflicts.size} conflicts")
        return true
    }

    private suspend fun syncQueue(
        api: QueueSyncApi,
        id: String,
        localExists: Boolean,
        state: QueueSyncEntity?,
        remote: RemoteQueue?,
        device: String,
    ): Outcome {
        val localHash = if (localExists) queueBoard.localContentHash(id) else null
        when (val decision = decideContent(localHash, state, remote)) {
            ContentDecision.None -> Unit
            ContentDecision.Push -> {
                val outcome = pushContent(api, id, force = false, device = device)
                if (outcome != Outcome.Done) return outcome
            }
            ContentDecision.PushDelete -> return pushDelete(api, id, state ?: QueueSyncEntity(queueId = id, deleted = true), force = false)
            is ContentDecision.ApplyRemote -> {
                if (queueBoard.isLoadedInPlayer(id) && queueBoard.playerPlaying) return Outcome.Deferred
                return if (queueBoard.applyRemote(decision.remote)) Outcome.Done else Outcome.Deferred
            }
            is ContentDecision.Adopt -> adopt(id, decision.remote, localHash)
            is ContentDecision.Conflict -> return Outcome.Conflicted(conflictFor(id, decision.remote, state))
        }

        val fresh = queueBoard.syncState(id)
        when (decidePlayback(queueBoard.exportPlayback(id) != null, fresh, remote)) {
            PlaybackDecision.None -> Unit
            PlaybackDecision.ApplyRemote -> {
                // Playing here: this device's position is the live one and goes up next time.
                if (!(queueBoard.isLoadedInPlayer(id) && queueBoard.playerPlaying)) queueBoard.applyRemotePlayback(remote!!)
            }
            PlaybackDecision.Push -> pushPlayback(api, id, fresh!!, device)
        }
        return Outcome.Done
    }

    private suspend fun pushContent(api: QueueSyncApi, id: String, force: Boolean, device: String): Outcome {
        val (content, hash, playback) = queueBoard.exportForSync(id) ?: return Outcome.Done
        val state = queueBoard.syncState(id) ?: QueueSyncEntity(queueId = id)
        val playbackAt = maxOf(state.playbackAt, 1L)
        val body = PutBody(
            baseRev = state.baseRev,
            force = force.takeIf { it },
            content = content,
            editedAt = state.editedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            editedBy = state.editedBy ?: device,
            playback = playback,
            playbackAt = playbackAt,
            playbackBy = device,
        )
        return when (val result = api.put(id, body)) {
            is PutResult.Ok -> {
                queueBoard.updateSyncState(id) {
                    it.copy(
                        baseRev = result.queue.rev,
                        syncedHash = hash,
                        localHash = it.localHash ?: hash,
                        editedAt = body.editedAt!!,
                        editedBy = body.editedBy,
                        playbackSyncedAt = maxOf(it.playbackSyncedAt, playbackAt),
                    )
                }
                Outcome.Done
            }
            is PutResult.Conflict -> {
                val remote = result.queue
                if (remote == null) {
                    // The server has no such queue at our base revision (it was reset): start over.
                    if (state.baseRev == 0L) return Outcome.Deferred
                    queueBoard.updateSyncState(id) { it.copy(baseRev = 0L) }
                    return pushContent(api, id, force, device)
                }
                when (val again = decideContent(hash, state, remote)) {
                    is ContentDecision.Adopt -> {
                        adopt(id, remote, hash)
                        Outcome.Done
                    }
                    is ContentDecision.ApplyRemote -> if (queueBoard.applyRemote(remote)) Outcome.Done else Outcome.Deferred
                    is ContentDecision.Conflict -> Outcome.Conflicted(conflictFor(id, again.remote, state))
                    else -> Outcome.Conflicted(conflictFor(id, remote, state))
                }
            }
            PutResult.NotFound -> Outcome.Done
        }
    }

    private suspend fun pushDelete(api: QueueSyncApi, id: String, state: QueueSyncEntity, force: Boolean): Outcome {
        val body = PutBody(
            baseRev = state.baseRev,
            force = force.takeIf { it },
            deleted = true,
            editedAt = state.editedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            editedBy = state.editedBy ?: syncDeviceName(context),
        )
        return when (val result = api.put(id, body)) {
            is PutResult.Ok -> {
                queueBoard.updateSyncState(id) { it.copy(baseRev = result.queue.rev, syncedHash = TOMBSTONE_HASH, deleted = true) }
                Outcome.Done
            }
            is PutResult.Conflict -> {
                val remote = result.queue
                when {
                    // Never reached the server, so there is nothing there to delete.
                    remote == null -> queueBoard.updateSyncState(id) { it.copy(syncedHash = TOMBSTONE_HASH) }
                    remote.deleted -> queueBoard.updateSyncState(id) { it.copy(baseRev = remote.rev, syncedHash = TOMBSTONE_HASH) }
                    else -> return Outcome.Conflicted(conflictFor(id, remote, state))
                }
                Outcome.Done
            }
            PutResult.NotFound -> Outcome.Done
        }
    }

    private suspend fun pushPlayback(api: QueueSyncApi, id: String, state: QueueSyncEntity, device: String) {
        val playback = queueBoard.exportPlayback(id) ?: return
        val result = api.put(id, PutBody(playback = playback, playbackAt = state.playbackAt, playbackBy = device))
        if (result is PutResult.Ok) {
            queueBoard.updateSyncState(id) { it.copy(playbackSyncedAt = maxOf(it.playbackSyncedAt, state.playbackAt)) }
        }
    }

    private suspend fun adopt(id: String, remote: RemoteQueue, localHash: String?) {
        queueBoard.updateSyncState(id) {
            it.copy(
                baseRev = remote.rev,
                syncedHash = if (remote.deleted) TOMBSTONE_HASH else localHash,
                localHash = localHash ?: it.localHash,
                deleted = remote.deleted || it.deleted,
            )
        }
    }

    private suspend fun conflictFor(id: String, remote: RemoteQueue, state: QueueSyncEntity?): SyncConflict {
        val title = remote.content?.title ?: queueBoard.queueFlow(id).firstOrNull()?.title ?: "Queue"
        return SyncConflict(queueId = id, title = title, remote = remote, localDeleted = state?.deleted == true)
    }

    private suspend fun setStatus(status: String) {
        context.safeDataStoreEdit { it[QueueSyncStatusKey] = status }
    }

    private companion object {
        const val TAG = "QueueSync"
        const val PERIOD_MS = 3 * 60_000L
        const val EDIT_DELAY_MS = 5_000L
        const val FLUSH_DELAY_MS = 2_000L
        const val RETRY_MIN_MS = 30_000L
        const val RETRY_MAX_MS = 15 * 60_000L
        const val BACKGROUND_RETRIES = 4
        /** Re-pull a minute of overlap, in case a write landed just as the last pull read. */
        const val CURSOR_OVERLAP_MS = 60_000L
    }
}
