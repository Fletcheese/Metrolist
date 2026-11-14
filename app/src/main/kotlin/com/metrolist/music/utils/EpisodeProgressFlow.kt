/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * FletchTunes listened marker: live merged progress for a list of episodes. One local query for
 * the whole list, re-run when a saved position changes; no network call.
 */
fun MusicDatabase.episodeProgress(episodes: List<EpisodeProgress.Ref>): Flow<Map<String, Int>> {
    EpisodeResumeHints.remember(episodes)
    return if (episodes.isEmpty()) {
        flowOf(emptyMap())
    } else {
        episodePositions(episodes.map { it.id }).map { rows -> EpisodeProgress.merge(episodes, rows) }
    }
}

/**
 * FletchTunes: YouTube's playbackProgress for the episodes the lists have shown, kept in memory so
 * playback can resume from the same merged position the marker shows, with no network call at
 * play time. MusicService runs in the app process, so it reads this directly.
 */
object EpisodeResumeHints {
    private val youtubePercent = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun remember(episodes: List<EpisodeProgress.Ref>) {
        episodes.forEach { episode -> episode.youtubePercent?.let { youtubePercent[episode.id] = it } }
    }

    fun youtubePercent(episodeId: String): Int? = youtubePercent[episodeId]
}
