/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

/** FletchTunes: a podcast episode's saved local position, for the listened marker. */
data class EpisodePosition(
    val id: String,
    val playbackPosition: Long?,
    val duration: Int,
)
