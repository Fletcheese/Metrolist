/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.metrolist.innertube.YouTube
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.EpisodeProgress
import com.metrolist.music.utils.episodeProgress
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug builds only: times a podcast show page's data path (the show's browse call, then the
 * listened-marker merge with local saved positions) from a computer over adb, with the screen
 * locked. Only the adb shell can send it (it needs android.permission.DUMP). Results go to
 * logcat under the tag FT_PROBE.
 *
 *   adb shell am broadcast -n com.metrolist.music.debug/com.metrolist.music.debug.PodcastProbeDebugReceiver \
 *     --es podcast MPSPPL... [--ei runs 3] [--ez newEpisodes true]
 */
@AndroidEntryPoint
class PodcastProbeDebugReceiver : BroadcastReceiver() {
    @Inject
    lateinit var database: MusicDatabase

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A cold start sets the login cookie asynchronously; wait for it briefly.
                var waited = 0
                while (YouTube.cookie == null && waited < 10_000) {
                    delay(250)
                    waited += 250
                }
                Log.i(TAG, "signedIn=${YouTube.cookie != null} (waited $waited ms)")
                val runs = intent.getIntExtra("runs", 3)
                intent.getStringExtra("podcast")?.let { probePodcast(it, runs) }
                if (intent.getBooleanExtra("newEpisodes", false)) probeNewEpisodes(runs)
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun probePodcast(podcastId: String, runs: Int) {
        repeat(runs) { run ->
            val t0 = SystemClock.elapsedRealtime()
            val page = YouTube.podcast(podcastId).getOrThrow()
            val t1 = SystemClock.elapsedRealtime()
            val refs = page.episodes.map { EpisodeProgress.Ref(it.id, it.playbackProgressPercent, it.duration) }
            val progress = database.episodeProgress(refs).first()
            val t2 = SystemClock.elapsedRealtime()
            val youtube = page.episodes.count { it.playbackProgressPercent != null }
            val played = progress.values.count { EpisodeProgress.isPlayed(it) }
            Log.i(
                TAG,
                "podcast run ${run + 1}: fetch ${t1 - t0} ms, merge ${t2 - t1} ms, total ${t2 - t0} ms; " +
                    "${page.episodes.size} episodes, $youtube with YouTube progress, ${progress.size} with any progress, " +
                    "$played played (hidden), continuation=${page.continuation != null}",
            )
            if (run == 0) {
                page.episodes.forEach {
                    Log.i(TAG, "  ${it.id} yt=${it.playbackProgressPercent} merged=${progress[it.id]} dur=${it.duration} ${it.title.take(60)}")
                }
            }
        }
    }

    private suspend fun probeNewEpisodes(runs: Int) {
        repeat(runs) { run ->
            val t0 = SystemClock.elapsedRealtime()
            YouTube.newEpisodesPlaylistInfo()
            val t1 = SystemClock.elapsedRealtime()
            val episodes = YouTube.newEpisodes().getOrThrow()
            val t2 = SystemClock.elapsedRealtime()
            val missing = episodes.count { it.artists.isEmpty() }
            val filled = YouTube.fillEpisodeAuthors(episodes)
            val t3 = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "newEpisodes run ${run + 1}: header browse alone ${t1 - t0} ms, list shown after newEpisodes() ${t2 - t1} ms, " +
                    "background show-name fill ${t3 - t2} ms ($missing missing, ${filled.count { it.artists.isEmpty() }} still missing); " +
                    "${episodes.size} episodes, ${episodes.count { it.playbackProgressPercent != null }} with YouTube progress",
            )
        }
    }

    private companion object {
        const val TAG = "FT_PROBE"
    }
}
