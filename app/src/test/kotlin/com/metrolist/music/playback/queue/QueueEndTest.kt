package com.metrolist.music.playback.queue

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Row 198: a queue that runs out goes back to its first song in play order, paused at 0:00, in
 * two later main-thread turns, and only with repeat off.
 */
class QueueEndTest {
    /** A Player whose reads answer from these fields and whose writes are recorded and applied. */
    private class EndedPlayer(
        var state: Int = Player.STATE_ENDED,
        var repeat: Int = Player.REPEAT_MODE_OFF,
        var shuffle: Boolean = false,
        var playWhenReady: Boolean = true,
        val timeline: Timeline = ThreeSongTimeline(intArrayOf(2, 0, 1)),
    ) {
        val writes = mutableListOf<String>()
        val player: Player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                when (method.name) {
                    "getPlaybackState" -> state
                    "getRepeatMode" -> repeat
                    "getShuffleModeEnabled" -> shuffle
                    "getPlayWhenReady" -> playWhenReady
                    "getMediaItemCount" -> timeline.windowCount
                    "getCurrentTimeline" -> timeline
                    "setPlayWhenReady" -> {
                        playWhenReady = args!![0] as Boolean
                        writes += "setPlayWhenReady($playWhenReady)"
                        null
                    }
                    "seekTo" -> {
                        writes += "seekTo(" + args!!.joinToString(", ") + ")"
                        state = Player.STATE_BUFFERING
                        null
                    }
                    else -> {
                        if (!method.name.startsWith("get") && !method.name.startsWith("is") && !method.name.startsWith("has")) {
                            writes += method.name
                        }
                        if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                    }
                }
            } as Player
    }

    /** Three songs; with shuffle on they play in [shuffled] order (media indices). Only the counts and first index are read. */
    private class ThreeSongTimeline(private val shuffled: IntArray) : Timeline() {
        override fun getWindowCount() = 3

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long) = window

        override fun getPeriodCount() = 3

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean) = period

        override fun getIndexOfPeriod(uid: Any) = (uid as? Int) ?: -1

        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex

        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) = if (shuffleModeEnabled) shuffled.first() else 0
    }

    private fun runTurns(rec: EndedPlayer, onRestarted: () -> Unit = {}): Pair<Boolean, Int> {
        val posted = ArrayDeque<Runnable>()
        val scheduled = restartQueueAtEnd(rec.player, { posted.addLast(it) }, onRestarted)
        var turns = 0
        // The call itself changes nothing: the player is only touched in later turns.
        assertTrue(rec.writes.isEmpty())
        while (posted.isNotEmpty()) {
            posted.removeFirst().run()
            turns++
        }
        return scheduled to turns
    }

    @Test
    fun anEndedQueueGoesBackToItsFirstSongPausedInTwoTurns() {
        val rec = EndedPlayer()
        var restarted = false
        val (scheduled, turns) = runTurns(rec) { restarted = true }
        assertTrue(scheduled)
        assertEquals(2, turns)
        assertEquals(listOf("setPlayWhenReady(false)", "seekTo(0, 0)"), rec.writes)
        assertTrue(restarted)
    }

    @Test
    fun aShuffledQueueGoesBackToTheFirstSongInShuffleOrder() {
        val rec = EndedPlayer(shuffle = true)
        runTurns(rec)
        assertEquals(listOf("setPlayWhenReady(false)", "seekTo(2, 0)"), rec.writes)
    }

    @Test
    fun repeatAllAndRepeatOneAreLeftAlone() {
        for (mode in listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)) {
            val rec = EndedPlayer(repeat = mode)
            val (scheduled, turns) = runTurns(rec)
            assertFalse(scheduled)
            assertEquals(0, turns)
            assertTrue(rec.writes.isEmpty())
        }
    }

    @Test
    fun aQueueStillPlayingIsLeftAlone() {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_IDLE)) {
            val rec = EndedPlayer(state = state)
            assertFalse(runTurns(rec).first)
            assertTrue(rec.writes.isEmpty())
        }
    }

    @Test
    fun nothingHappensIfThePlayerMovedOnBeforeTheRestart() {
        // Play pressed (or a new queue started) between the end and the posted restart.
        val rec = EndedPlayer()
        val posted = ArrayDeque<Runnable>()
        assertTrue(restartQueueAtEnd(rec.player, { posted.addLast(it) }))
        rec.state = Player.STATE_READY
        while (posted.isNotEmpty()) posted.removeFirst().run()
        assertTrue(rec.writes.isEmpty())
    }

    @Test
    fun noSeekIfPlayWasPressedBetweenThePauseAndTheSeek() {
        val rec = EndedPlayer()
        val posted = ArrayDeque<Runnable>()
        var restarted = false
        restartQueueAtEnd(rec.player, { posted.addLast(it) }) { restarted = true }
        posted.removeFirst().run()
        rec.playWhenReady = true
        while (posted.isNotEmpty()) posted.removeFirst().run()
        assertEquals(listOf("setPlayWhenReady(false)"), rec.writes)
        assertFalse(restarted)
    }

    @Test
    fun anEmptyPlayerIsLeftAlone() {
        val rec = EndedPlayer(timeline = Timeline.EMPTY)
        assertFalse(runTurns(rec).first)
        assertEquals(null, firstInPlayOrder(rec.player))
    }
}
