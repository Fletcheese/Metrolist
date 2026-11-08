package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeProgressTest {
    @Test
    fun noDataIsNull() {
        assertNull(EpisodeProgress.percent(null, null, 3600))
        assertNull(EpisodeProgress.percent(null, 0L, 3600))
        assertNull(EpisodeProgress.percent(null, 60_000L, null))
        assertFalse(EpisodeProgress.isPlayed(null))
    }

    @Test
    fun localPositionGivesPercent() {
        assertEquals(25, EpisodeProgress.percent(null, 900_000L, 3600))
    }

    @Test
    fun furthestSourceWins() {
        assertEquals(40, EpisodeProgress.percent(40, 900_000L, 3600))
        assertEquals(25, EpisodeProgress.percent(10, 900_000L, 3600))
    }

    @Test
    fun youtubePlayedCountsAsPlayed() {
        val p = EpisodeProgress.percent(100, null, 3600)
        assertEquals(100, p)
        assertTrue(EpisodeProgress.isPlayed(p))
    }

    @Test
    fun lastMinuteLocallyCountsAsPlayed() {
        assertEquals(100, EpisodeProgress.percent(null, 3_550_000L, 3600))
        assertTrue(EpisodeProgress.isPlayed(EpisodeProgress.percent(null, 3_550_000L, 3600)))
    }

    @Test
    fun shortClipNeedsRealProgress() {
        // 90 s clip, 40 s in: not played just because less than a minute is left.
        assertEquals(44, EpisodeProgress.percent(null, 40_000L, 90))
        assertFalse(EpisodeProgress.isPlayed(44))
    }

    @Test
    fun youtubePercentIsClamped() {
        assertEquals(100, EpisodeProgress.percent(140, null, null))
    }
}
