/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.R
import com.metrolist.music.constants.DiscoverMixPlaylistIdKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * FletchTunes keeps one discovery surface: the account's "Discover Mix", opened as a normal
 * online playlist. YouTube Music has no endpoint for it on its own, so the first tap reads the
 * Home feed (a few pages at most) to find its playlist id, which is then remembered. Nothing
 * runs until the entry is tapped.
 */
object DiscoverMix {
    private const val TAG = "DiscoverMix"
    private const val MAX_HOME_PAGES = 6
    private const val DISCOVER_MIX = "Discover Mix"
    private const val SUPERMIX = "My Supermix"

    const val ROUTE = "discover_mix"

    suspend fun open(
        context: Context,
        navController: NavController,
    ) {
        val cachedId = context.dataStore.data.first()[DiscoverMixPlaylistIdKey]
        val playlistId =
            cachedId?.takeIf { it.isNotBlank() } ?: run {
                Toast.makeText(context, R.string.discover_mix_finding, Toast.LENGTH_SHORT).show()
                val found = withContext(Dispatchers.IO) { find() }
                if (found == null) {
                    Toast.makeText(context, R.string.discover_mix_not_found, Toast.LENGTH_LONG).show()
                    return
                }
                context.safeDataStoreEdit { it[DiscoverMixPlaylistIdKey] = found.id }
                found.id
            }
        navController.navigate("online_playlist/$playlistId")
    }

    /** Discover Mix, or My Supermix when the feed offers no Discover Mix. */
    private suspend fun find(): PlaylistItem? {
        var fallback: PlaylistItem? = null
        var page = YouTube.home().onFailure { reportException(it) }.getOrNull() ?: return null
        repeat(MAX_HOME_PAGES) { pageIndex ->
            val playlists = page.sections.flatMap { it.items }.filterIsInstance<PlaylistItem>()
            playlists.firstOrNull { it.title.equals(DISCOVER_MIX, ignoreCase = true) }?.let {
                Timber.tag(TAG).d("Found %s (%s) on home page %d", it.title, it.id, pageIndex)
                return it
            }
            if (fallback == null) fallback = playlists.firstOrNull { it.title.equals(SUPERMIX, ignoreCase = true) }
            val continuation = page.continuation ?: return fallback
            page = YouTube.home(continuation = continuation).getOrNull() ?: return fallback
        }
        Timber.tag(TAG).d("No Discover Mix in %d home pages; fallback=%s", MAX_HOME_PAGES, fallback?.id)
        return fallback
    }
}
