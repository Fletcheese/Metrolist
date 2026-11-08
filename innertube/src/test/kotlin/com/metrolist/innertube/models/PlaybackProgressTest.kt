package com.metrolist.innertube.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackProgressTest {
    private fun percent(json: String) = MusicMultiRowListItemRenderer.PlaybackProgress.percent(Json.parseToJsonElement(json))

    @Test
    fun `reads the renderer percentage`() {
        assertEquals(
            37,
            percent(
                """{"musicPlaybackProgressRenderer":{"playbackProgressPercentage":37,"playbackProgressText":{"runs":[{"text":"41 min left"}]}}}""",
            ),
        )
    }

    @Test
    fun `played text without a number is 100`() {
        assertEquals(100, percent("""{"musicPlaybackProgressRenderer":{"playbackProgressText":{"runs":[{"text":"Played"}]}}}"""))
    }

    @Test
    fun `legacy fractional value is scaled`() {
        assertEquals(50, percent("""{"value":0.5}"""))
    }

    @Test
    fun `nothing useful is null`() {
        assertNull(percent("""{"musicPlaybackProgressRenderer":{"playbackProgressText":{"runs":[{"text":"Updated"}]}}}"""))
        assertNull(MusicMultiRowListItemRenderer.PlaybackProgress.percent(null))
    }

    @Test
    fun `renderer deserialises with the progress element`() {
        val json = Json { ignoreUnknownKeys = true }
        val renderer =
            json.decodeFromString(
                MusicMultiRowListItemRenderer.serializer(),
                """{"title":null,"subtitle":null,"thumbnail":null,"onTap":null,"displayStyle":null,"menu":null,"playbackProgress":{"musicPlaybackProgressRenderer":{"playbackProgressPercentage":100,"playbackProgressText":{"runs":[{"text":"Played"}]}}}}""",
            )
        assertEquals(100, renderer.playbackProgressPercent)
    }
}
