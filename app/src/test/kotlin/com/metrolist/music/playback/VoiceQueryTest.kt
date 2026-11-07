/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.VoiceQuery.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueryTest {
    @Test
    fun `spoken playlist requests name the playlist`() {
        assertEquals(VoiceQuery(Kind.PLAYLIST, "driving"), VoiceQuery.parse("play my driving playlist"))
        assertEquals(VoiceQuery(Kind.PLAYLIST, "driving"), VoiceQuery.parse("my driving playlist"))
        assertEquals(VoiceQuery(Kind.PLAYLIST, "Road Trip"), VoiceQuery.parse("playlist Road Trip"))
        assertEquals(VoiceQuery(Kind.PLAYLIST, "discover"), VoiceQuery.parse("the discover mix on FletchTunes"))
    }

    @Test
    fun `spoken artist requests name the artist`() {
        assertEquals(VoiceQuery(Kind.ARTIST, "Fleetwood Mac"), VoiceQuery.parse("play artist Fleetwood Mac"))
        assertEquals(VoiceQuery(Kind.ARTIST, "Six60"), VoiceQuery.parse("songs by Six60"))
    }

    @Test
    fun `focus extras win over the wording`() {
        val playlist = VoiceQuery.parse(
            "driving",
            mapOf(
                VoiceQuery.EXTRA_FOCUS to "vnd.android.cursor.item/playlist",
                VoiceQuery.EXTRA_PLAYLIST to "Driving",
            ),
        )
        assertEquals(VoiceQuery(Kind.PLAYLIST, "Driving"), playlist)

        val artist = VoiceQuery.parse(
            "adele",
            mapOf(VoiceQuery.EXTRA_FOCUS to "vnd.android.cursor.item/artist", VoiceQuery.EXTRA_ARTIST to "Adele"),
        )
        assertEquals(VoiceQuery(Kind.ARTIST, "Adele"), artist)

        val song = VoiceQuery.parse("hello my playlist", mapOf(VoiceQuery.EXTRA_FOCUS to "vnd.android.cursor.item/audio"))
        assertEquals(Kind.SONG, song.kind)
    }

    @Test
    fun `anything else is an open query`() {
        assertEquals(VoiceQuery(Kind.ANY, "Hello by Adele"), VoiceQuery.parse("play Hello by Adele"))
    }

    @Test
    fun `shuffle is remembered`() {
        assertTrue(VoiceQuery.parse("shuffle my driving playlist").shuffle)
        assertFalse(VoiceQuery.parse("play my driving playlist").shuffle)
    }

    @Test
    fun `loose matching finds a playlist by part of its name`() {
        val playlists = listOf("Chill", "Driving Mix 2026", "Gym")
        assertEquals(1, VoiceNameMatcher.bestIndex("driving", playlists, strict = false))
        assertEquals(1, VoiceNameMatcher.bestIndex("drivin mix", playlists, strict = false))
        assertNull(VoiceNameMatcher.bestIndex("cooking", playlists, strict = false))
    }

    @Test
    fun `strict matching needs the whole name`() {
        val playlists = listOf("Hello Summer Hits", "Driving")
        assertNull(VoiceNameMatcher.bestIndex("hello", playlists, strict = true))
        assertEquals(1, VoiceNameMatcher.bestIndex("driving", playlists, strict = true))
        assertEquals(1, VoiceNameMatcher.bestIndex("my driving playlist", playlists, strict = true))
    }

    @Test
    fun `liked songs aliases match`() {
        val names = listOf("Downloads", "Liked songs")
        assertEquals(1, VoiceNameMatcher.bestIndex("my likes", names, strict = true))
        assertEquals(1, VoiceNameMatcher.bestIndex("liked songs", names, strict = true))
    }
}
