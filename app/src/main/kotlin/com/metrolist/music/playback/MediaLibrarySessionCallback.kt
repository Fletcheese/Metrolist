/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import androidx.media3.session.MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.queue.QueueBoard
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.DefaultAndroidAutoSectionsRaw
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.utils.DiscoverMix
import androidx.media3.session.MediaConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
    val queueBoard: QueueBoard,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    fun release() {
        scope.cancel()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * FletchTunes: "play" from the car, a headset or the system media controls with nothing
     * loaded resumes the last QueueBoard queue. MusicService.onCreate() already restores that
     * queue (with its saved shuffle order) on its own, so this waits for that rather than
     * setting the songs again, which would lose the shuffle order. Once the player has songs,
     * the future fails on purpose: Media3 then just presses play on what is there.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future {
            val player = service.player
            awaitPlayerItems(RESTORE_WAIT_MS)
            if (player.mediaItemCount > 0) {
                if (isForPlayback) throw HandledByService()
                return@future MediaItemsWithStartPosition(
                    (0 until player.mediaItemCount).map { player.getMediaItemAt(it) },
                    player.currentMediaItemIndex,
                    player.currentPosition,
                )
            }
            val (queue, songs) =
                queueBoard.mostRecentQueue()?.takeIf { it.second.isNotEmpty() }
                    ?: return@future MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
            val startIndex = queue.currentIndex.coerceIn(0, songs.size - 1)
            if (!isForPlayback) {
                return@future MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, startIndex, queue.positionMs)
            }
            resumeBoardQueue(queue.id)
            awaitPlayerItems(RESTORE_WAIT_MS)
            throw HandledByService()
        }

    private suspend fun awaitPlayerItems(timeoutMs: Long) {
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                while (service.player.mediaItemCount == 0) delay(100)
            }
        }
    }

    /** Plays a saved QueueBoard queue the way the Queues tab does: same row, saved song, position and shuffle order. */
    private suspend fun resumeBoardQueue(queueId: String): Boolean {
        val queue = queueBoard.queueFlow(queueId).first() ?: return false
        val songs = queueBoard.loadSongs(queueId)
        if (songs.isEmpty()) return false
        withContext(Dispatchers.Main) {
            if (queueBoard.activeQueueId.value == queueId && service.player.mediaItemCount > 0) {
                service.player.play()
            } else {
                service.playQueue(
                    ListQueue(
                        title = queue.title,
                        items = songs.map { it.toMediaItem() },
                        startIndex = queue.currentIndex.coerceIn(0, songs.size - 1),
                        position = queue.positionMs,
                        sourceKey = queue.sourceKey,
                    ),
                    resumeBoardQueueId = queue.id,
                )
            }
        }
        return true
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                rootParams(params),
            ),
        )

    /** Car content style: playlists, artists and albums as a grid, songs and queues as a list. */
    private fun rootParams(params: MediaLibraryService.LibraryParams?): MediaLibraryService.LibraryParams {
        val extras = Bundle(params?.extras ?: Bundle.EMPTY).apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return MediaLibraryService.LibraryParams.Builder()
            .setOffline(params?.isOffline ?: false)
            .setRecent(params?.isRecent ?: false)
            .setSuggested(params?.isSuggested ?: false)
            .setExtras(extras)
            .build()
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(
            if (isBrowsableMediaId(parentId)) {
                LibraryResult.ofVoid(params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            },
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            try {
                loadLocalChildren(parentId, page, pageSize)?.let { children ->
                    return@future LibraryResult.ofItemList(children, params)
                }

                val children = when (parentId) {
                    MusicService.ROOT -> {
                        val sectionsRaw = context.dataStore.get(AndroidAutoSectionsOrderKey, DefaultAndroidAutoSectionsRaw)
                        val showYoutubePlaylists = context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)
                        val rootItems = androidAutoRootSections(sectionsRaw)
                            .map { section ->
                                when (section) {
                                    AndroidAutoSection.QUEUES -> browsableMediaItem(
                                        AA_QUEUES,
                                        context.getString(R.string.queues),
                                        null,
                                        drawableUri(R.drawable.queue_music),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                    )
                                    AndroidAutoSection.LIKED -> browsableMediaItem(
                                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                        context.getString(R.string.liked_songs),
                                        null,
                                        drawableUri(R.drawable.favorite),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                   AndroidAutoSection.SONGS -> browsableMediaItem(
                                        MusicService.SONG,
                                        context.getString(R.string.songs),
                                        null,
                                        drawableUri(R.drawable.music_note),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                    AndroidAutoSection.ARTISTS -> browsableMediaItem(
                                        MusicService.ARTIST,
                                        context.getString(R.string.artists),
                                        null,
                                        drawableUri(R.drawable.artist),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                    )
                                    AndroidAutoSection.ALBUMS -> browsableMediaItem(
                                        MusicService.ALBUM,
                                        context.getString(R.string.albums),
                                        null,
                                        drawableUri(R.drawable.album),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                    )
                                    AndroidAutoSection.PLAYLISTS -> browsableMediaItem(
                                        MusicService.PLAYLIST,
                                        context.getString(R.string.playlists),
                                        null,
                                        drawableUri(R.drawable.playlist_play),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                    )
                                }
                            }
                        if (showYoutubePlaylists) {
                            rootItems + browsableMediaItem(
                                MusicService.YOUTUBE_PLAYLIST,
                                context.getString(R.string.mixes),
                                null,
                                drawableUri(R.drawable.explore_outlined),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            )
                        } else {
                            rootItems
                        }
                    }
                    MusicService.YOUTUBE_PLAYLIST -> {
                        if (!context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)) {
                            emptyList()
                        } else {
                            try {
                                val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                                var continuation: String? = null
                                val maxPages = 4

                                for (page in 0 until maxPages) {
                                    val result = YouTube.home(continuation)
                                        .onFailure { reportException(it) }
                                        .getOrNull() ?: break
                                    allSections.addAll(result.sections)
                                    continuation = result.continuation
                                    if (continuation == null) break
                                }

                                // Drop playlists already saved to the local library,
                                // which are exposed under MusicService.PLAYLIST.
                                val savedBrowseIds = database.bookmarkedPlaylistBrowseIds().toSet()

                                val playlists = allSections
                                    .flatMap { it.items }
                                    .filterIsInstance<PlaylistItem>()
                                    .filterNot { it.id in savedBrowseIds }
                                    .distinctBy { it.id }

                                playlists.map { playlist ->
                                    browsableMediaItem(
                                        "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                        playlist.title,
                                        playlist.author?.name,
                                        playlist.thumbnail?.toUri(),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }
                        }
                    }

                    AA_QUEUES -> queueBoard.activeQueues.first().map { queue ->
                        val artwork = queue.currentSongId?.let { database.song(it).first()?.song?.thumbnailUrl?.toUri() }
                        MediaItem.Builder()
                            .setMediaId("$AA_QUEUES/${queue.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(queue.title)
                                    .setSubtitle(
                                        context.resources.getQuantityString(R.plurals.n_song, queue.songCount, queue.songCount),
                                    )
                                    .setArtworkUri(artwork ?: drawableUri(R.drawable.queue_music))
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                    .build(),
                            ).build()
                    }

                    AA_DISCOVER_MIX ->
                        DiscoverMix.playlistId(context)
                            ?.let { youtubePlaylistChildren("${MusicService.YOUTUBE_PLAYLIST}/$it", it) }
                            ?: emptyList()

                    else ->
                        when {
                            parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") ->
                                youtubePlaylistChildren(parentId, parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/"))

                            else -> emptyList()
                        }
                }
                LibraryResult.ofItemList(children.paginate(page, pageSize), params)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }

    private suspend fun youtubePlaylistChildren(parentId: String, playlistId: String): List<MediaItem> =
        try {
            val songs = YouTube.playlist(playlistId).getOrNull()?.songs
                ?.take(100)
                ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                ?: emptyList()

            // Add shuffle item at the top
            listOf(
                MediaItem.Builder()
                    .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(context.getString(R.string.shuffle))
                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build()
                    ).build()
            ) + songs.map { songItem ->
                MediaItem.Builder()
                    .setMediaId("$parentId/${songItem.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(songItem.title)
                            .setExtras(Bundle().apply {
                                if (songItem.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                            })
                            .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                ArtistNameAliases.resolve(it.id, it.name)
                            })
                            .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                ArtistNameAliases.resolve(it.id, it.name)
                            })
                            .setArtworkUri(songItem.thumbnail.toUri())
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build()
                    )
                    .build()
            }
        } catch (e: Exception) {
            reportException(e)
            emptyList()
        }

    private suspend fun loadLocalChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val request = androidAutoPageRequest(page, pageSize)
        return when {
            parentId == MusicService.ARTIST ->
                database.artistsByCreateDateAsc(request.limit, request.offset).map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        ArtistNameAliases.resolve(artist.id, artist.artist.name),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            artist.songCount,
                            artist.songCount,
                        ),
                        artist.artist.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ARTIST,
                    )
                }

            parentId == MusicService.ALBUM ->
                database.albumsByCreateDateAsc(request.limit, request.offset).map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString {
                            ArtistNameAliases.resolve(it.id, it.name)
                        },
                        album.album.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ALBUM,
                    )
                }

            parentId == MusicService.PLAYLIST ->
                loadPlaylistContainers(request)

            parentId == MusicService.SONG ->
                database.songsByCreateDateAsc(request.limit, request.offset)
                    .map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ARTIST}/") ->
                database.artistSongsByCreateDateAsc(
                    parentId.removePrefix("${MusicService.ARTIST}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ALBUM}/") ->
                database.albumSongs(
                    parentId.removePrefix("${MusicService.ALBUM}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.PLAYLIST}/") ->
                loadPlaylistChildren(parentId, request)

            else -> null
        }
    }

    private suspend fun loadPlaylistContainers(request: AndroidAutoPageRequest): List<MediaItem> {
        val builtInItems =
            if (request.offset < PLAYLIST_BUILT_IN_COUNT && request.limit > 0) {
                val likedSongCount = database.likedSongsCount().first()
                val downloadedSongCount = downloadUtil.downloads.value.size
                listOf(
                    browsableMediaItem(
                        AA_DISCOVER_MIX,
                        context.getString(R.string.discover_mix),
                        null,
                        drawableUri(R.drawable.explore_outlined),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                        context.getString(R.string.liked_songs),
                        context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                        drawableUri(R.drawable.favorite),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                        context.getString(R.string.downloaded_songs),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            downloadedSongCount,
                            downloadedSongCount,
                        ),
                        drawableUri(R.drawable.download),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                ).drop(request.offset).take(request.limit)
            } else {
                emptyList()
            }
        val playlistRequest = request.afterLeadingItems(PLAYLIST_BUILT_IN_COUNT)
        val playlists =
            if (playlistRequest.limit == 0) {
                emptyList()
            } else {
                database.playlistsByCreateDateAsc(playlistRequest.limit, playlistRequest.offset)
            }
        return builtInItems + playlists.map { playlist ->
            browsableMediaItem(
                "${MusicService.PLAYLIST}/${playlist.id}",
                playlist.playlist.name,
                context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                playlist.thumbnails.firstOrNull()?.toUri(),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )
        }
    }

    private suspend fun loadPlaylistChildren(
        parentId: String,
        request: AndroidAutoPageRequest,
    ): List<MediaItem> {
        val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
        val includeShuffle = request.offset == 0 && request.limit > 0
        val songRequest = request.afterLeadingItems(1)
        val songs = when {
            songRequest.limit == 0 -> emptyList()
            playlistId == PlaylistEntity.LIKED_PLAYLIST_ID ->
                database.likedSongsByCreateDateDesc(songRequest.limit, songRequest.offset)

            playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                val downloads = downloadUtil.downloads.value
                val completedSongIds = downloads.entries
                    .asSequence()
                    .filter { it.value.state == Download.STATE_COMPLETED }
                    .sortedBy { it.value.updateTimeMs }
                    .map { it.key }
                    .toList()
                val existingSongIds = completedSongIds
                    .chunked(MAX_ANDROID_AUTO_PAGE_SIZE)
                    .flatMapTo(mutableSetOf()) { database.existingSongIds(it) }
                val songIds = completedSongIds.asSequence()
                    .filter(existingSongIds::contains)
                    .drop(songRequest.offset)
                    .take(songRequest.limit)
                    .toList()
                val songsById = database.getSongsByIds(songIds).associateBy { it.id }
                songIds.mapNotNull(songsById::get)
            }

            else ->
                database.playlistSongs(playlistId, songRequest.limit, songRequest.offset)
                    .map { it.song }
        }

        return buildList {
            if (includeShuffle) {
                add(
                    MediaItem.Builder()
                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.shuffle))
                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        ).build()
                )
            }
            addAll(songs.map { it.toMediaItem(parentId) })
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            try {
                database.song(mediaId).first()?.toMediaItem()?.let {
                    LibraryResult.ofItem(it, null)
                } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()
                val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                val allLocalSongs = database.searchSongsExtended(query, limit).first()
                allLocalSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = false,
                    ))
                }

                try {
                    val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                        ?.filter { onlineSong ->
                            !allLocalSongs.any { localSong ->
                                localSong.id == onlineSong.id ||
                                (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                 localSong.artists.any { artist ->
                                     onlineSong.artists.any {
                                         it.name.equals(artist.name, ignoreCase = true)
                                     }
                                 })
                            }
                        } ?: emptyList()

                    onlineResults.forEach { songItem ->
                        try {
                            database.query { insert(songItem.toMediaMetadata()) }
                        } catch (e: Exception) {
                        }
                        
                        searchResults.add(
                            MediaItem.Builder()
                                .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setExtras(Bundle().apply {
                                            if (songItem.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                                        })
                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtworkUri(songItem.thumbnail.toUri())
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    reportException(e)
                }
                
                LibraryResult.ofItemList(searchResults, params)
                
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) { resolveMediaItems(mediaItems, startIndex, startPositionMs) }

    /**
     * FletchTunes: the phone's MEDIA_PLAY_FROM_SEARCH intent (MainActivity) goes through the same
     * voice path as Android Auto and Assistant. A blank query ("play some music") resumes the
     * last queue.
     */
    fun playFromVoiceSearch(query: String, extras: Map<String, String?>) {
        scope.launch {
            if (query.isBlank()) {
                if (service.player.mediaItemCount > 0) {
                    service.player.play()
                } else {
                    queueBoard.mostRecentQueue()?.first?.id?.let { resumeBoardQueue(it) }
                }
                return@launch
            }
            val request = MediaItem.Builder()
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setSearchQuery(query)
                        .setExtras(Bundle().apply { extras.forEach { (key, value) -> putString(key, value) } })
                        .build(),
                ).build()
            val result =
                try {
                    withContext(Dispatchers.IO) { resolveMediaItems(listOf(request), 0, C.TIME_UNSET) }
                } catch (_: HandledByService) {
                    return@launch
                } catch (e: Exception) {
                    reportException(e)
                    return@launch
                }
            if (result.mediaItems.isEmpty()) return@launch
            service.player.setMediaItems(result.mediaItems, result.startIndex, result.startPositionMs)
            service.player.prepare()
            service.player.play()
        }
    }

    private suspend fun resolveMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition {
            val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val voiceQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
            val voiceExtras = mediaItems.firstOrNull()?.requestMetadata?.extras?.let { bundle ->
                bundle.keySet().associateWith { key -> bundle.getString(key) }
            }.orEmpty()

            val path = if (!voiceQuery.isNullOrBlank()) {
                listOf(MusicService.SEARCH, voiceQuery, "")
            } else {
                mediaItems.firstOrNull()?.mediaId?.split("/")
            } ?: return defaultResult

            return when (path.firstOrNull()) {
                AA_QUEUES -> {
                    val queueId = path.getOrNull(1) ?: return defaultResult
                    if (!resumeBoardQueue(queueId)) return defaultResult
                    throw HandledByService()
                }

                // FletchTunes: a song picked from the library or an artist's songs is the only
                // song in the queue; the songs after it go to Autoplay.
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return defaultResult
                    val allSongs = database.songsByCreateDateAsc().first().map { it.toMediaItem() }
                    val index = allSongs.indexOfFirst { it.mediaId == songId }.takeIf { it != -1 } ?: 0
                    songOnlySessionQueue(allSongs, index, startPositionMs) ?: defaultResult
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return defaultResult
                    val artistId = path.getOrNull(1) ?: return defaultResult
                    val songs = database.artistSongsByCreateDateAsc(artistId).first().map { it.toMediaItem() }
                    val index = songs.indexOfFirst { it.mediaId == songId }.takeIf { it != -1 } ?: 0
                    songOnlySessionQueue(songs, index, startPositionMs) ?: defaultResult
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return defaultResult
                    val albumId = path.getOrNull(1) ?: return defaultResult
                    val albumWithSongs = database.albumWithSongs(albumId).first() ?: return defaultResult
                    val songs = albumWithSongs.songs.map { it.toMediaItem() }
                    sessionQueue(
                        ListQueue(title = albumWithSongs.album.title, items = songs, sourceKey = "album:$albumId"),
                        songs,
                        albumWithSongs.songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs,
                    )
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return defaultResult
                    val playlistId = path.getOrNull(1) ?: return defaultResult
                    val songs = when (playlistId) {
                        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { songs ->
                                    songs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }
                    }.first()

                    val items = songs.map { it.toMediaItem() }
                    val queue = ListQueue(title = null, items = items, sourceKey = "playlist:$playlistId")
                    // Check if this is a shuffle action (TalkToMe's "play my music" uses it).
                    // FletchTunes: the songs stay in playlist order and shuffle is turned on with
                    // one fixed order, starting on a random song.
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        if (items.isEmpty()) return defaultResult
                        sessionQueue(queue, items, items.indices.random(), C.TIME_UNSET, shuffle = true)
                    } else {
                        sessionQueue(
                            queue,
                            items,
                            songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs,
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return defaultResult
                    val playlistId = path.getOrNull(1) ?: return defaultResult

                    val songs = try {
                        YouTube.playlist(playlistId).getOrNull()?.songs?.map {
                            it.toMediaItem()
                        } ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        return defaultResult
                    }

                    val queue = ListQueue(title = null, items = songs, sourceKey = "yt_playlist:$playlistId")
                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        if (songs.isEmpty()) return defaultResult
                        sessionQueue(queue, songs, songs.indices.random(), C.TIME_UNSET, shuffle = true)
                    } else {
                        sessionQueue(
                            queue,
                            songs,
                            songs.indexOfFirst { it.mediaId.endsWith(songId) }.takeIf { it != -1 } ?: 0,
                            C.TIME_UNSET,
                        )
                    }
                }

                MusicService.SEARCH -> {
                    val songId = path.getOrNull(2) ?: return defaultResult
                    val spokenQuery = path.getOrNull(1) ?: return defaultResult

                    val isVoiceSearch = songId.isBlank() && spokenQuery.isNotBlank()
                    var searchQuery = spokenQuery

                    if (isVoiceSearch) {
                        // FletchTunes: a playlist, artist or album named by voice plays that
                        // collection; anything else is a song search.
                        val voice = VoiceQuery.parse(spokenQuery, voiceExtras)
                        voiceCollection(voice, startPositionMs)?.let { return it }
                        searchQuery = voice.name.ifBlank { spokenQuery }
                    }

                    val searchResults = mutableListOf<Song>()
                    val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                    val allLocalSongs = database.searchSongsExtended(searchQuery, limit).first()
                    searchResults.addAll(allLocalSongs)
                    if (!isVoiceSearch && songId.isNotBlank() && searchResults.indexOfFirst { it.id == songId } == -1) {
                        database.song(songId).first()?.let { searchResults.add(it) }
                    }
                    
                    try {
                        val onlineResults = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?.filter { onlineSong ->
                                !allLocalSongs.any { localSong ->
                                    localSong.id == onlineSong.id ||
                                    (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                     localSong.artists.any { artist ->
                                         onlineSong.artists.any {
                                             it.name.equals(artist.name, ignoreCase = true)
                                         }
                                     })
                                }
                            } ?: emptyList()

                        onlineResults.forEach { songItem ->
                            try {
                                database.query { insert(songItem.toMediaMetadata()) }
                                database.song(songItem.id).first()?.let { newSong ->
                                    searchResults.add(newSong)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                    
                    if (searchResults.isEmpty()) {
                        return defaultResult
                    }

                    val selectedSong =
                        if (isVoiceSearch) {    //Check if the voiceQuery is about a specific song
                            val snapshot: List<Song> =
                                synchronized(searchResults) { searchResults.toList() }
                            // FletchTunes: nothing in the results is a strong match, so play what
                            // YouTube ranks first (the online results follow the local ones).
                            VoiceSearchMatcher.findBest(searchQuery, snapshot)
                                ?: snapshot.getOrNull(allLocalSongs.size)
                                ?: snapshot.firstOrNull()
                        } else {
                            searchResults.firstOrNull { it.id == songId }
                        }

                    if(context.dataStore.get(AutoRadioQueueKey, true)) {
                        val radioQueue = YouTubeQueue.radio(selectedSong?.toMediaMetadata() ?: return defaultResult)
                        val radioStatus = runCatching {
                            withContext(Dispatchers.IO) {
                                radioQueue
                                    .getInitialStatus()
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            }
                        }.getOrNull()

                        if (radioStatus != null && radioStatus.items.isNotEmpty()) {
                            // FletchTunes: the queue gets only the song; the radio (and its
                            // further pages) go to Autoplay.
                            return sessionQueue(
                                radioQueue,
                                radioStatus.items,
                                radioStatus.items.indexOfFirst { it.mediaId == selectedSong.id }.coerceAtLeast(0),
                                C.TIME_UNSET,
                                title = radioStatus.title,
                                suggestions = radioStatus.suggestions,
                            )
                        }
                    }

                    val items = listOf(selectedSong?.toMediaItem() ?: return defaultResult)
                    sessionQueue(
                        ListQueue(
                            title = selectedSong.song.title,
                            items = items,
                        ),
                        items,
                        0,
                        C.TIME_UNSET,
                        title = selectedSong.song.title,
                    )
                }

                else -> defaultResult
            }
        }

    /**
     * FletchTunes: a voice request for a playlist ("play my driving playlist"), an artist ("play
     * artist Y", or a library artist's exact name) or an album. Null when it names none of them,
     * so the caller falls back to a song search.
     */
    private suspend fun voiceCollection(voice: VoiceQuery, startPositionMs: Long): MediaItemsWithStartPosition? {
        if (voice.name.isBlank()) return null
        val kind = voice.kind
        val strict = kind == VoiceQuery.Kind.ANY
        val leaf = if (voice.shuffle) MusicService.SHUFFLE_ACTION else ""

        if (kind == VoiceQuery.Kind.PLAYLIST || kind == VoiceQuery.Kind.ANY) {
            val targets = mutableListOf<Pair<String, String>>()
            listOf("Liked songs", "Liked", "My likes", "Favourites", "Favorites", context.getString(R.string.liked_songs))
                .forEach { targets += it to "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}" }
            listOf("Downloads", "Downloaded", context.getString(R.string.downloaded_songs))
                .forEach { targets += it to "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}" }
            targets += context.getString(R.string.discover_mix) to AA_DISCOVER_MIX
            database.playlistsByCreateDateAsc().first().forEach {
                targets += it.playlist.name to "${MusicService.PLAYLIST}/${it.playlist.id}"
            }
            VoiceNameMatcher.bestIndex(voice.name, targets.map { it.first }, strict)?.let { index ->
                val target = targets[index].second
                val mediaId =
                    if (target == AA_DISCOVER_MIX) {
                        DiscoverMix.playlistId(context)?.let { "${MusicService.YOUTUBE_PLAYLIST}/$it/$leaf" }
                    } else {
                        "$target/$leaf"
                    }
                if (mediaId != null) {
                    val result = resolveMediaItems(listOf(MediaItem.Builder().setMediaId(mediaId).build()), 0, startPositionMs)
                    if (result.mediaItems.isNotEmpty()) return result
                }
            }
        }

        if (kind == VoiceQuery.Kind.ARTIST || kind == VoiceQuery.Kind.ANY) {
            val artists = database.artistsByCreateDateAsc().first()
            val names = artists.map { ArtistNameAliases.resolve(it.id, it.artist.name) }
            VoiceNameMatcher.bestIndex(voice.name, names, strict)?.let { index ->
                val artist = artists[index]
                val items = database.artistSongsByCreateDateAsc(artist.id).first().map { it.toMediaItem() }
                if (items.isNotEmpty()) {
                    // An artist always plays shuffled; in creation order it would start on the same song every time.
                    return sessionQueue(
                        ListQueue(title = names[index], items = items, sourceKey = "artist:${artist.id}"),
                        items,
                        items.indices.random(),
                        C.TIME_UNSET,
                        shuffle = true,
                    )
                }
            }
        }

        if (kind == VoiceQuery.Kind.ALBUM) {
            val albums = database.searchAlbums(voice.name).first()
            VoiceNameMatcher.bestIndex(voice.name, albums.map { it.album.title }, strict = false)?.let { index ->
                val result = resolveMediaItems(
                    listOf(MediaItem.Builder().setMediaId("${MusicService.ALBUM}/${albums[index].id}/").build()),
                    0,
                    startPositionMs,
                )
                if (result.mediaItems.isNotEmpty()) return result
            }
        }
        return null
    }

    /**
     * FletchTunes: tells MusicService what the session is about to play (so it gets its own
     * QueueBoard queue, a fixed shuffle order when [shuffle] is set, and [suggestions] in
     * Autoplay), then returns the songs for the session to set.
     */
    private suspend fun sessionQueue(
        queue: Queue,
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        shuffle: Boolean = false,
        title: String? = (queue as? ListQueue)?.title,
        suggestions: List<MediaItem> = emptyList(),
    ): MediaSession.MediaItemsWithStartPosition {
        withContext(Dispatchers.Main) {
            service.prepareSessionQueue(queue, title, items, shuffle, suggestions)
        }
        return MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
    }

    /** One song picked from a list: it alone goes in the queue, the songs after it to Autoplay. */
    private suspend fun songOnlySessionQueue(
        songs: List<MediaItem>,
        index: Int,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition? {
        val song = songs.getOrNull(index) ?: return null
        val items = listOf(song)
        return sessionQueue(
            ListQueue(title = song.mediaMetadata.title?.toString(), items = items),
            items,
            0,
            startPositionMs,
            suggestions = songs.drop(index + 1),
        )
    }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                 MediaMetadata
                     .Builder()
                     .setTitle(song.title)
                     .setExtras(Bundle().apply {
                         if (song.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                     })
                     .setSubtitle(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtist(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtworkUri(song.thumbnailUrl?.toUri())
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}

/** FletchTunes browse-tree nodes, kept here rather than in MusicService. */
internal const val AA_QUEUES = "queues"
internal const val AA_DISCOVER_MIX = "discover_mix"

/** Discover Mix, Liked songs and Downloaded songs lead the Playlists list. */
internal const val PLAYLIST_BUILT_IN_COUNT = 3

/** How long a "play" with nothing loaded waits for MusicService's own queue restore. */
private const val RESTORE_WAIT_MS = 3_000L

/**
 * Thrown from a session callback when MusicService has already started the playback itself
 * (a QueueBoard queue resumes through playQueue() to keep its row and shuffle order). Media3
 * then leaves the player alone: a failed onSetMediaItems is ignored for car/legacy controllers,
 * and a failed onPlaybackResumption just presses play.
 */
internal class HandledByService : Exception("Playback started by MusicService")

/** The root sections the car shows, in order. Liked songs when every section is switched off. */
internal fun androidAutoRootSections(raw: String): List<AndroidAutoSection> =
    deserializeSections(raw)
        .filter { (_, enabled) -> enabled }
        .map { it.first }
        .ifEmpty { listOf(AndroidAutoSection.LIKED) }

internal fun isBrowsableMediaId(mediaId: String): Boolean =
    mediaId == MusicService.ROOT ||
        mediaId == AA_QUEUES ||
        mediaId == AA_DISCOVER_MIX ||
        mediaId == MusicService.SONG ||
        mediaId == MusicService.ARTIST ||
        mediaId == MusicService.ALBUM ||
        mediaId == MusicService.PLAYLIST ||
        mediaId == MusicService.YOUTUBE_PLAYLIST ||
        mediaId.startsWith("${MusicService.ARTIST}/") ||
        mediaId.startsWith("${MusicService.ALBUM}/") ||
        mediaId.startsWith("${MusicService.PLAYLIST}/") ||
        mediaId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/")

internal fun <T> List<T>.paginate(
    page: Int,
    pageSize: Int,
): List<T> {
    val fromIndex = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
    val toIndex = (fromIndex.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
    return subList(fromIndex, toIndex)
}

internal const val MAX_ANDROID_AUTO_PAGE_SIZE = 500

internal data class AndroidAutoPageRequest(
    val offset: Int,
    val limit: Int,
) {
    fun afterLeadingItems(count: Int): AndroidAutoPageRequest {
        val leadingItemsOnPage = (count - offset).coerceIn(0, limit)
        return AndroidAutoPageRequest(
            offset = (offset - count).coerceAtLeast(0),
            limit = limit - leadingItemsOnPage,
        )
    }
}

internal fun androidAutoPageRequest(
    page: Int,
    pageSize: Int,
): AndroidAutoPageRequest {
    val safePage = page.coerceAtLeast(0)
    val safePageSize = pageSize.coerceAtLeast(0)
    val effectivePageSize = safePageSize.coerceAtMost(MAX_ANDROID_AUTO_PAGE_SIZE)
    return AndroidAutoPageRequest(
        offset = (safePage.toLong() * effectivePageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        limit = effectivePageSize,
    )
}
