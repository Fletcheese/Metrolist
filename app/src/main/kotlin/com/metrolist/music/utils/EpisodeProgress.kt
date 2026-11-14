/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.db.entities.EpisodePosition

/**
 * FletchTunes: how far a podcast episode has been listened to, merging YouTube's own
 * playbackProgress (which covers listening in the YouTube / YouTube Music apps) with the position
 * FletchTunes saved locally. The furthest of the two wins.
 */
object EpisodeProgress {
    /** An episode at or past this percentage counts as played (YouTube marks "Played" near the end too). */
    const val PLAYED_PERCENT = 95

    /** Local positions within this much of the end also count as played (outros, ad tails). */
    private const val PLAYED_REMAINING_MS = 60_000L

    /**
     * Merged 0-100 progress, or null when neither source has anything.
     *
     * @param youtubePercent YouTube's playbackProgress percentage, if the browse response had one.
     * @param localPositionMs the saved local playback position in milliseconds.
     * @param durationSeconds the episode length in seconds (YouTube's or the local row's).
     */
    fun percent(
        youtubePercent: Int?,
        localPositionMs: Long?,
        durationSeconds: Int?,
    ): Int? {
        val local = localPercent(localPositionMs, durationSeconds)
        return listOfNotNull(youtubePercent?.coerceIn(0, 100), local).maxOrNull()
    }

    fun isPlayed(percent: Int?): Boolean = percent != null && percent >= PLAYED_PERCENT

    /** One episode as a list knows it: its id, YouTube's playbackProgress, and its length. */
    data class Ref(
        val id: String,
        val youtubePercent: Int?,
        val durationSeconds: Int?,
    )

    /**
     * Episode id to merged 0-100 progress for a list of episodes, given the local saved positions
     * for them. Episodes with no progress from either source are left out.
     */
    fun merge(
        episodes: List<Ref>,
        local: List<EpisodePosition>,
    ): Map<String, Int> {
        val byId = local.associateBy { it.id }
        return episodes
            .mapNotNull { episode ->
                val row = byId[episode.id]
                percent(
                    youtubePercent = episode.youtubePercent,
                    localPositionMs = row?.playbackPosition,
                    durationSeconds = episode.durationSeconds?.takeIf { it > 0 } ?: row?.duration,
                )?.let { episode.id to it }
            }.toMap()
    }

    /** The "Hide played" filter: drops played episodes, but never the one playing now. */
    fun <T> unplayed(
        items: List<T>,
        progress: Map<String, Int>,
        playingId: String?,
        id: (T) -> String,
    ): List<T> = items.filter { id(it) == playingId || !isPlayed(progress[id(it)]) }

    private fun localPercent(
        positionMs: Long?,
        durationSeconds: Int?,
    ): Int? {
        if (positionMs == null || positionMs <= 0) return null
        if (durationSeconds == null || durationSeconds <= 0) return null
        val durationMs = durationSeconds * 1000L
        // Long episodes: a minute of outro left is "played", but never for a sub-two-minute clip.
        if (durationMs > 2 * PLAYED_REMAINING_MS && positionMs >= durationMs - PLAYED_REMAINING_MS) return 100
        return ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
    }
}
