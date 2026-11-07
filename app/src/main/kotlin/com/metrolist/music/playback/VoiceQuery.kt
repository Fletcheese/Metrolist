/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * FletchTunes: what a voice "play ..." request asks for, from the spoken query and the focus
 * extras Assistant sends with it (MediaStore.EXTRA_MEDIA_FOCUS and friends). Kept free of Android
 * types so it can be unit tested.
 */
data class VoiceQuery(
    val kind: Kind,
    /** The name to look for: a playlist or artist name, or the song query. */
    val name: String,
    /** "Shuffle my driving playlist" rather than "play". */
    val shuffle: Boolean = false,
) {
    enum class Kind { ANY, PLAYLIST, ARTIST, ALBUM, SONG }

    companion object {
        const val EXTRA_FOCUS = "android.intent.extra.focus"
        const val EXTRA_ARTIST = "android.intent.extra.artist"
        const val EXTRA_ALBUM = "android.intent.extra.album"
        const val EXTRA_TITLE = "android.intent.extra.title"
        const val EXTRA_PLAYLIST = "android.intent.extra.playlist"

        private const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
        private const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
        private const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
        private const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"

        private val LEADING_PLAY = Regex("^(please\\s+)?(play|shuffle|put on|start)\\s+", RegexOption.IGNORE_CASE)
        private val TRAILING_APP = Regex("\\s+(on|in|with|using)\\s+fletch\\s*tunes$", RegexOption.IGNORE_CASE)
        private val PLAYLIST_SHAPE =
            Regex("^(my\\s+|the\\s+)?(.+?)\\s+(playlist|play list|mix)$", RegexOption.IGNORE_CASE)
        private val PLAYLIST_PREFIX = Regex("^(my\\s+)?(playlist|play list)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val ARTIST_PREFIX =
            Regex("^(the\\s+)?(artist|band|songs by|music by|some)\\s+(.+)$", RegexOption.IGNORE_CASE)

        fun parse(query: String, extras: Map<String, String?> = emptyMap()): VoiceQuery =
            parseCleaned(clean(query), extras).copy(shuffle = query.trim().startsWith("shuffle", ignoreCase = true))

        private fun parseCleaned(cleaned: String, extras: Map<String, String?>): VoiceQuery {
            val focus = extras[EXTRA_FOCUS]
            fun extra(key: String) = extras[key]?.trim()?.takeIf { it.isNotEmpty() }
            when (focus) {
                FOCUS_PLAYLIST -> return VoiceQuery(Kind.PLAYLIST, extra(EXTRA_PLAYLIST) ?: playlistName(cleaned))
                FOCUS_ARTIST -> return VoiceQuery(Kind.ARTIST, extra(EXTRA_ARTIST) ?: artistName(cleaned))
                FOCUS_ALBUM -> return VoiceQuery(Kind.ALBUM, extra(EXTRA_ALBUM) ?: cleaned)
                FOCUS_AUDIO -> return VoiceQuery(Kind.SONG, cleaned)
            }
            PLAYLIST_PREFIX.find(cleaned)?.let { return VoiceQuery(Kind.PLAYLIST, it.groupValues[3].trim()) }
            PLAYLIST_SHAPE.find(cleaned)?.let { return VoiceQuery(Kind.PLAYLIST, it.groupValues[2].trim()) }
            ARTIST_PREFIX.find(cleaned)?.let { return VoiceQuery(Kind.ARTIST, it.groupValues[3].trim()) }
            return VoiceQuery(Kind.ANY, cleaned)
        }

        private fun clean(query: String) =
            query.trim().replace(LEADING_PLAY, "").replace(TRAILING_APP, "").trim()

        private fun playlistName(cleaned: String) =
            PLAYLIST_PREFIX.find(cleaned)?.groupValues?.get(3)?.trim()
                ?: PLAYLIST_SHAPE.find(cleaned)?.groupValues?.get(2)?.trim()
                ?: cleaned

        private fun artistName(cleaned: String) = ARTIST_PREFIX.find(cleaned)?.groupValues?.get(3)?.trim() ?: cleaned
    }
}

/**
 * FletchTunes: looser name matching for voice requests ("driving" finds "Driving Mix 2026",
 * "my likes" finds "Liked songs"). Returns the index of the best candidate, or null.
 *
 * [strict] (used when the query did not say it was a playlist or an artist) only accepts a
 * name whose words are all spoken, so "play hello" does not hijack a playlist called
 * "Hello Summer Hits".
 */
object VoiceNameMatcher {
    private val FILLER = setOf("my", "the", "a", "playlist", "play", "list", "songs", "music")

    fun score(query: String, candidate: String): Double {
        val q = words(query)
        val c = words(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        val matchedQuery = q.count { qt -> c.any { similar(qt, it) } }
        val matchedCandidate = c.count { ct -> q.any { similar(it, ct) } }
        if (matchedQuery == 0) return 0.0
        val queryCoverage = matchedQuery.toDouble() / q.size
        val candidateCoverage = matchedCandidate.toDouble() / c.size
        // Every spoken word found weighs more than covering the whole name.
        return 0.7 * queryCoverage + 0.3 * candidateCoverage
    }

    fun bestIndex(query: String, candidates: List<String>, strict: Boolean): Int? {
        val threshold = if (strict) 0.99 else 0.7
        return candidates
            .mapIndexed { index, name -> index to score(query, name) }
            .filter { (_, s) -> s >= threshold }
            .maxByOrNull { (_, s) -> s }
            ?.first
    }

    internal fun words(text: String): Set<String> {
        val all = VoiceSearchMatcher.tokenize(text)
        return (all - FILLER).ifEmpty { all }
    }

    private fun similar(a: String, b: String) =
        a == b || (a.length >= 4 && b.length >= 4 && VoiceSearchMatcher.jaroWinkler(a, b) >= VoiceSearchMatcher.FUZZY_THRESHOLD)
}
