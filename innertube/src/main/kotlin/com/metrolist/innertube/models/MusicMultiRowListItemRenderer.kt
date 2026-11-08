package com.metrolist.innertube.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class MusicMultiRowListItemRenderer(
    val title: Runs?,
    val subtitle: Runs?,
    @SerialName("secondSubtitle")
    val secondSubtitle: Runs? = null,
    @SerialName("secondarySubtitle")
    val secondarySubtitle: Runs? = null,
    val thumbnail: ThumbnailRenderer?,
    val onTap: NavigationEndpoint?,
    // Kept as raw JSON: YouTube wraps it as
    // {"musicPlaybackProgressRenderer":{"playbackProgressPercentage":N,"playbackProgressText":{...}}}
    // and the exact shape has drifted before, so PlaybackProgress.percent() searches it.
    val playbackProgress: JsonElement? = null,
    val displayStyle: String?,
    val menu: Menu?,
) {
    /** YouTube's listened percentage for this episode (0-100), or null when it reports none. */
    val playbackProgressPercent: Int?
        get() = PlaybackProgress.percent(playbackProgress)

    object PlaybackProgress {
        /**
         * Reads a 0-100 percentage from a playbackProgress element: the first numeric field whose
         * name contains "percent" (or "value", which older parsers assumed), else 100 when the
         * progress text says "Played".
         */
        fun percent(element: JsonElement?): Int? {
            if (element == null) return null
            findNumber(element)?.let { return it.coerceIn(0.0, 100.0).toInt() }
            if (texts(element).any { it.trim().equals("Played", ignoreCase = true) }) return 100
            return null
        }

        private fun findNumber(element: JsonElement): Double? =
            when (element) {
                is JsonObject ->
                    element.entries.firstNotNullOfOrNull { (key, value) ->
                        if ((key.contains("percent", ignoreCase = true) || key == "value") && value is JsonPrimitive) {
                            value.doubleOrNull?.let { if (key == "value" && it <= 1.0) it * 100 else it }
                        } else {
                            null
                        }
                    } ?: element.values.firstNotNullOfOrNull { findNumber(it) }
                is JsonArray -> element.firstNotNullOfOrNull { findNumber(it) }
                else -> null
            }

        private fun texts(element: JsonElement): List<String> =
            when (element) {
                is JsonObject ->
                    element.entries.flatMap { (key, value) ->
                        if ((key == "text" || key == "simpleText") && value is JsonPrimitive && value.isString) {
                            listOf(value.content)
                        } else {
                            texts(value)
                        }
                    }
                is JsonArray -> element.flatMap { texts(it) }
                else -> emptyList()
            }
    }
}
