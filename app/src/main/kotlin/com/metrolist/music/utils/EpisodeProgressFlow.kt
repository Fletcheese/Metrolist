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
fun MusicDatabase.episodeProgress(episodes: List<EpisodeProgress.Ref>): Flow<Map<String, Int>> =
    if (episodes.isEmpty()) {
        flowOf(emptyMap())
    } else {
        episodePositions(episodes.map { it.id }).map { rows -> EpisodeProgress.merge(episodes, rows) }
    }
