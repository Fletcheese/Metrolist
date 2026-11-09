package com.metrolist.music.playback.queue.sync

import com.metrolist.music.db.entities.QueueSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueSyncMergeTest {
    private fun content(title: String = "Mix", songs: List<String> = listOf("a", "b", "c")) =
        QueueContent(title = title, songs = songs.map { SyncSong(id = it) })

    private fun remote(rev: Long, content: QueueContent? = content(), deleted: Boolean = false, playbackAt: Long = 0L) =
        RemoteQueue(
            id = "q",
            rev = rev,
            deleted = deleted,
            content = if (deleted) null else content,
            editedAt = 1_000L,
            editedBy = "Tablet",
            playback = PlaybackState(currentIndex = 1),
            playbackAt = playbackAt,
        )

    private val base = content().hash()
    private val edited = content(songs = listOf("a", "b", "c", "d")).hash()
    private fun synced(rev: Long = 3L, hash: String? = base) =
        QueueSyncEntity(queueId = "q", baseRev = rev, syncedHash = hash, localHash = hash)

    // --- Hash ---

    @Test
    fun hashIgnoresSongMetadataButNotOrderTitleOrShuffle() {
        val plain = content()
        val richer = plain.copy(songs = plain.songs.map { it.copy(title = "t", thumbnailUrl = "x") })
        assertEquals(plain.hash(), richer.hash())
        assertNotEquals(plain.hash(), content(songs = listOf("b", "a", "c")).hash())
        assertNotEquals(plain.hash(), content(title = "Renamed").hash())
        assertNotEquals(plain.hash(), plain.copy(shuffled = true, shuffleOrder = listOf(2, 0, 1)).hash())
    }

    @Test
    fun shuffleOrderOnlyCountsWhenShuffled() {
        assertEquals(content().hash(), content().copy(shuffleOrder = listOf(2, 0, 1)).hash())
    }

    @Test
    fun hashMatchesTheFieldwiseFormQueueBoardUses() {
        val c = content().copy(sourceKey = "PL1", shuffled = true, shuffleOrder = listOf(1, 2, 0))
        assertEquals(contentHash("Mix", "PL1", true, listOf("a", "b", "c"), listOf(1, 2, 0)), c.hash())
    }

    // --- Never-synced queues ---

    @Test
    fun aNewLocalQueueIsPushed() {
        assertEquals(ContentDecision.Push, decideContent(base, null, null))
    }

    @Test
    fun aQueueOnlyOnTheServerIsCreatedHere() {
        val r = remote(1)
        assertEquals(ContentDecision.ApplyRemote(r), decideContent(null, null, r))
    }

    @Test
    fun aServerTombstoneForAQueueNeverSeenHereIsJustRecorded() {
        val r = remote(2, deleted = true)
        assertEquals(ContentDecision.Adopt(r), decideContent(null, null, r))
    }

    @Test
    fun sameIdOnBothSidesNeverSyncedAgreesIfTheContentMatches() {
        val r = remote(1)
        assertEquals(ContentDecision.Adopt(r), decideContent(base, null, r))
        assertEquals(ContentDecision.Conflict(r), decideContent(edited, null, r))
    }

    // --- One side changed: the change wins, no prompt ---

    @Test
    fun nothingChangedDoesNothing() {
        assertEquals(ContentDecision.None, decideContent(base, synced(), null))
        assertEquals(ContentDecision.None, decideContent(base, synced(), remote(3)))
    }

    @Test
    fun aLocalEditIsPushed() {
        assertEquals(ContentDecision.Push, decideContent(edited, synced(), null))
        assertEquals(ContentDecision.Push, decideContent(edited, synced(), remote(3)))
    }

    @Test
    fun aRemoteEditIsTakenWhenThisDeviceDidNotEdit() {
        val r = remote(4, content(songs = listOf("x")))
        assertEquals(ContentDecision.ApplyRemote(r), decideContent(base, synced(), r))
    }

    @Test
    fun aRemoteDeletionIsTakenWhenThisDeviceDidNotEdit() {
        val r = remote(4, deleted = true)
        assertEquals(ContentDecision.ApplyRemote(r), decideContent(base, synced(), r))
    }

    // --- Both changed since the common base ---

    @Test
    fun bothEditedDifferentlyIsAConflict() {
        val r = remote(4, content(songs = listOf("x")))
        assertEquals(ContentDecision.Conflict(r), decideContent(edited, synced(), r))
    }

    @Test
    fun bothMadeTheSameEditIsNotAConflict() {
        val r = remote(4, content(songs = listOf("a", "b", "c", "d")))
        assertEquals(ContentDecision.Adopt(r), decideContent(edited, synced(), r))
    }

    @Test
    fun editedHereButDeletedThereIsAConflict() {
        val r = remote(4, deleted = true)
        assertEquals(ContentDecision.Conflict(r), decideContent(edited, synced(), r))
    }

    @Test
    fun anEditUndoneLocallyIsNotAnEdit() {
        val r = remote(4, content(songs = listOf("x")))
        assertEquals(ContentDecision.ApplyRemote(r), decideContent(base, synced().copy(localHash = edited), r))
    }

    // --- Local deletions (tombstones) ---

    private fun deletedHere(rev: Long = 3L) = synced(rev).copy(deleted = true, localHash = null)

    @Test
    fun aLocalDeletionIsPushed() {
        assertEquals(ContentDecision.PushDelete, decideContent(null, deletedHere(), null))
        assertEquals(ContentDecision.PushDelete, decideContent(null, deletedHere(), remote(3)))
    }

    @Test
    fun deletedHereButEditedThereIsAConflict() {
        val r = remote(4, content(songs = listOf("x")))
        assertEquals(ContentDecision.Conflict(r), decideContent(null, deletedHere(), r))
    }

    @Test
    fun deletedOnBothIsJustRecorded() {
        val r = remote(4, deleted = true)
        assertEquals(ContentDecision.Adopt(r), decideContent(null, deletedHere(), r))
    }

    @Test
    fun aSyncedTombstoneIgnoresStaleCopiesButTakesAForcedRestore() {
        val tombstone = deletedHere(4).copy(syncedHash = TOMBSTONE_HASH)
        assertEquals(ContentDecision.None, decideContent(null, tombstone, null))
        assertEquals(ContentDecision.None, decideContent(null, tombstone, remote(4, deleted = true)))
        val restored = remote(5)
        assertEquals(ContentDecision.ApplyRemote(restored), decideContent(null, tombstone, restored))
    }

    // --- Playback: newest wins, never a conflict ---

    private fun playback(at: Long, syncedAt: Long) = synced().copy(playbackAt = at, playbackSyncedAt = syncedAt)

    @Test
    fun aNewerRemotePositionIsTaken() {
        assertEquals(PlaybackDecision.ApplyRemote, decidePlayback(true, playback(100, 100), remote(3, playbackAt = 200)))
    }

    @Test
    fun aNewerLocalPositionIsPushedEvenWhenTheServerHasAnOlderOne() {
        assertEquals(PlaybackDecision.Push, decidePlayback(true, playback(300, 100), remote(3, playbackAt = 200)))
        assertEquals(PlaybackDecision.Push, decidePlayback(true, playback(300, 100), null))
    }

    @Test
    fun aRemotePositionNewerThanALocalUnsentOneStillWins() {
        assertEquals(PlaybackDecision.ApplyRemote, decidePlayback(true, playback(300, 100), remote(3, playbackAt = 400)))
    }

    @Test
    fun playbackIsLeftAloneWhenInSyncOrTheQueueIsGone() {
        assertEquals(PlaybackDecision.None, decidePlayback(true, playback(200, 200), remote(3, playbackAt = 200)))
        assertEquals(PlaybackDecision.None, decidePlayback(false, playback(300, 100), remote(3, playbackAt = 400)))
        assertEquals(PlaybackDecision.None, decidePlayback(true, null, remote(3, playbackAt = 400)))
    }

    @Test
    fun playingAQueueDoesNotMakeItsContentLookEdited() {
        // Playback moves on both devices; content is untouched, so content stays None.
        val state = synced().copy(playbackAt = 500, playbackSyncedAt = 100)
        assertEquals(ContentDecision.None, decideContent(base, state, remote(3, playbackAt = 400)))
    }
}
