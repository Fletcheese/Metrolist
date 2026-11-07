package com.metrolist.music.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Receives watch-time segments for a listen whose playback ping was accepted. */
internal interface WatchtimeSink {
    val flushIntervalMs: Long

    /** Segments are media-time [start, end] pairs in seconds. */
    suspend fun send(
        segments: List<Pair<Double, Double>>,
        currentMediaSeconds: Double,
        realTimeSeconds: Double,
        playing: Boolean,
        final: Boolean,
    ): Boolean
}

internal class YouTubeHistoryReporter(
    private val player: Player,
    private val scope: CoroutineScope,
    private val thresholdMs: () -> Long,
    private val isEnabled: () -> Boolean,
    /** Returns a watch-time sink once the playback ping is accepted, or null when it was not sent. */
    private val createReport: (String) -> suspend () -> WatchtimeSink?,
    onStatsReady: PlaybackStatsListener.Callback,
) {
    /** Media time actually played for one item, kept apart from the ping so it can start before the threshold. */
    private class Track(val videoId: String) {
        val startedRealtimeMs = SystemClock.elapsedRealtime()
        val segments = mutableListOf<Pair<Double, Double>>()
        var openFromMs: Long? = null
        var lastPositionMs = 0L
        var lastFlushRealtimeMs = startedRealtimeMs
        var listen: Listen? = null

        fun open(positionMs: Long) {
            if (openFromMs == null) openFromMs = positionMs
            lastPositionMs = positionMs
        }

        fun close(positionMs: Long) {
            openFromMs?.let { from -> if (positionMs > from) segments += from / 1000.0 to positionMs / 1000.0 }
            openFromMs = null
            lastPositionMs = positionMs
        }
    }

    private class Listen(
        val videoId: String,
        val startedAtMs: Long,
        val report: suspend () -> WatchtimeSink?,
        val track: Track,
    ) {
        var playTimeMs = 0L
        var attempts = 0
        var inFlight = false
        var sent = false
        var finished = false
        var sink: WatchtimeSink? = null
        var watchtimeInFlight = false
        var finalPending = false
        var finalSent = false
    }

    private val handler = Handler(player.applicationLooper)
    private val wakeUp = Runnable { refresh() }
    private var current: Listen? = null
    private var track: Track? = null
    private var closed = false
    val statsListener = PlaybackStatsListener(false) { eventTime, playbackStats ->
        val videoId = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
        if (playbackStats.totalPlayTimeMs > 0) {
            val listen = listenFor(videoId, playbackStats)
            listen.finished = true
            submit(listen)
            if (current === listen) current = null
            // Runs after this event batch, so the discontinuity that ended the item has closed its segment.
            handler.post { finishWatchtime(listen) }
        }
        onStatsReady.onPlaybackStatsReady(eventTime, playbackStats)
    }

    val listener: AnalyticsListener = object : AnalyticsListener {
        override fun onEvents(player: Player, events: AnalyticsListener.Events) {
            if (events.contains(AnalyticsListener.EVENT_PLAYER_RELEASED)) close() else refresh()
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            val videoId = eventTime.mediaId() ?: return
            val position = eventTime.currentPlaybackPositionMs
            if (isPlaying) {
                trackFor(videoId).open(position)
            } else {
                val paused = track?.takeIf { it.videoId == videoId } ?: return
                paused.close(position)
                paused.listen?.let { flush(it, playing = false, final = false) }
            }
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val old = track?.takeIf { it.videoId == oldPosition.mediaItem?.mediaId } ?: return
            val wasOpen = old.openFromMs != null
            old.close(oldPosition.positionMs)
            val sameItem =
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                    old.videoId == newPosition.mediaItem?.mediaId &&
                    reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            val next = if (sameItem) old else newPosition.mediaItem?.mediaId?.let { Track(it).also { track = it } }
            if (wasOpen) next?.open(newPosition.positionMs)
        }
    }

    fun refresh() {
        handler.removeCallbacks(wakeUp)
        if (closed) return
        val playbackStats = statsListener.playbackStats ?: return
        val videoId = player.currentMediaItem?.mediaId ?: return
        val listen = listenFor(videoId, playbackStats)
        current = listen
        submit(listen)
        if (player.isPlaying && isEnabled() && listen.attempts == 0) {
            // Wake only at the threshold; Media3 excludes pauses, buffering, and seeks.
            handler.postDelayed(wakeUp, (thresholdMs() - playbackStats.totalPlayTimeMs).coerceAtLeast(1L))
        }
    }

    /**
     * Called from a timer that already runs during playback, so progress reporting adds no wakeups of its own.
     * Sends at most one request per flush interval, and nothing while paused.
     */
    fun onPlaybackTick() {
        if (closed || !player.isPlaying) return
        val playing = track?.takeIf { it.videoId == player.currentMediaItem?.mediaId } ?: return
        val listen = playing.listen ?: return
        val sink = listen.sink ?: return
        if (SystemClock.elapsedRealtime() - playing.lastFlushRealtimeMs < sink.flushIntervalMs) return
        val position = player.currentPosition
        playing.close(position)
        playing.open(position)
        flush(listen, playing = true, final = false)
    }

    fun close() {
        closed = true
        handler.removeCallbacks(wakeUp)
    }

    private fun AnalyticsListener.EventTime.mediaId(): String? =
        if (timeline.isEmpty) null else timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId

    private fun trackFor(videoId: String): Track =
        track?.takeIf { it.videoId == videoId } ?: Track(videoId).also { track = it }

    private fun listenFor(videoId: String, playbackStats: PlaybackStats): Listen {
        val listen = current?.takeIf { it.videoId == videoId && it.startedAtMs == playbackStats.firstReportedTimeMs }
            ?: run {
                val itemTrack =
                    track?.takeIf { it.videoId == videoId && it.listen == null }
                        ?: Track(videoId).also { if (player.currentMediaItem?.mediaId == videoId) track = it }
                Listen(videoId, playbackStats.firstReportedTimeMs, createReport(videoId), itemTrack)
                    .also { itemTrack.listen = it }
            }
        listen.playTimeMs = playbackStats.totalPlayTimeMs
        return listen
    }

    private fun submit(listen: Listen) {
        if (!isEnabled() || listen.playTimeMs <= 0 || listen.playTimeMs < thresholdMs()) return
        if (listen.sent || listen.inFlight || listen.attempts >= (if (listen.finished) 2 else 1)) return
        listen.attempts = if (listen.finished) 2 else 1
        listen.inFlight = true
        scope.launch {
            try {
                listen.sink = listen.report()
                listen.sent = listen.sink != null
            } finally {
                listen.inFlight = false
            }
            // A failed live attempt gets one final retry using the same playback nonce.
            if (!listen.sent && listen.finished) submit(listen)
            if (listen.sent && listen.finished) finishWatchtime(listen)
        }
    }

    private fun finishWatchtime(listen: Listen) {
        val itemTrack = listen.track
        if (itemTrack.openFromMs != null && track !== itemTrack) itemTrack.close(itemTrack.lastPositionMs)
        flush(listen, playing = false, final = true)
    }

    private fun flush(listen: Listen, playing: Boolean, final: Boolean) {
        val sink = listen.sink ?: return
        if (listen.finalSent || !isEnabled()) return
        if (listen.watchtimeInFlight) {
            if (final) listen.finalPending = true
            return
        }
        val itemTrack = listen.track
        if (itemTrack.segments.isEmpty() && !final) return
        val segments = itemTrack.segments.toList().ifEmpty {
            listOf(itemTrack.lastPositionMs / 1000.0 to itemTrack.lastPositionMs / 1000.0)
        }
        itemTrack.segments.clear()
        val now = SystemClock.elapsedRealtime()
        itemTrack.lastFlushRealtimeMs = now
        if (final) listen.finalSent = true
        listen.watchtimeInFlight = true
        scope.launch {
            try {
                sink.send(
                    segments = segments,
                    currentMediaSeconds = itemTrack.lastPositionMs / 1000.0,
                    realTimeSeconds = (now - itemTrack.startedRealtimeMs) / 1000.0,
                    playing = playing,
                    final = final,
                )
            } finally {
                listen.watchtimeInFlight = false
            }
            if (listen.finalPending) {
                listen.finalPending = false
                flush(listen, playing = false, final = true)
            }
        }
    }
}
