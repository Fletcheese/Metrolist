package com.metrolist.music.viewmodels

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.music.constants.HidePlayedEpisodesKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.utils.EpisodeProgress
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.episodeProgress
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlinePodcastViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val podcastId = savedStateHandle.get<String>("podcastId")!!

    val podcast = MutableStateFlow<PodcastItem?>(null)
    val episodes = MutableStateFlow<List<EpisodeItem>>(emptyList())

    val libraryPodcast = podcast.flatMapLatest { p ->
        p?.let { database.podcast(it.id) } ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** FletchTunes: when the page opened, for the load-time log lines tagged [PODCAST_PAGE]. */
    private var openedAtMs = SystemClock.elapsedRealtime()
    private var progressLogged = false

    /**
     * FletchTunes listened marker: episode id to merged 0-100 progress, from YouTube's
     * playbackProgress in the show's own browse response and the locally saved position.
     * One local query for the page, no extra network call; live, so a play updates the list.
     */
    val episodeProgress: StateFlow<Map<String, Int>> =
        episodes
            .flatMapLatest { list ->
                database.episodeProgress(list.map { EpisodeProgress.Ref(it.id, it.playbackProgressPercent, it.duration) })
            }.onEach { progress ->
                val list = episodes.value
                if (list.isNotEmpty() && !progressLogged) {
                    progressLogged = true
                    Timber.d(
                        "[PODCAST_PAGE] $podcastId progress ready ${SystemClock.elapsedRealtime() - openedAtMs} ms after open: " +
                            "${list.size} episodes, ${list.count { it.playbackProgressPercent != null }} with YouTube progress, " +
                            "${progress.size} with any progress, ${progress.values.count { EpisodeProgress.isPlayed(it) }} played",
                    )
                }
            }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The same "Hide played" setting as New Episodes: one switch for every episode list. */
    val hidePlayedEpisodes =
        context.dataStore.data
            .map { it[HidePlayedEpisodesKey] ?: true }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, context.dataStore.get(HidePlayedEpisodesKey, true))

    fun setHidePlayedEpisodes(hide: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[HidePlayedEpisodesKey] = hide }
        }
    }

    init {
        Timber.d("ViewModel init with podcastId: $podcastId")
        fetchPodcastData()
    }

    private fun fetchPodcastData() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("fetchPodcastData called for: $podcastId")
            _isLoading.value = true
            _error.value = null
            openedAtMs = SystemClock.elapsedRealtime()
            progressLogged = false

            YouTube.podcast(podcastId)
                .onSuccess { podcastPage ->
                    Timber.d("Success! Podcast: ${podcastPage.podcast.title}, Episodes: ${podcastPage.episodes.size}")
                    Timber.d("[PODCAST_PAGE] $podcastId fetched in ${SystemClock.elapsedRealtime() - openedAtMs} ms")
                    podcast.value = podcastPage.podcast
                    episodes.value = podcastPage.episodes
                    _isLoading.value = false
                }.onFailure { throwable ->
                    Timber.e(throwable, "Failed to load podcast: ${throwable.message}")
                    _error.value = throwable.message ?: "Failed to load podcast"
                    _isLoading.value = false
                    reportException(throwable)
                }
        }
    }

    /**
     * Toggle saving podcast to library.
     */
    fun toggleSubscription() {
        val currentPodcast = podcast.value ?: run {
            Timber.d("[PODCAST_TOGGLE] No podcast loaded, returning")
            return
        }
        val existingEntity = libraryPodcast.value
        val isCurrentlySaved = existingEntity?.inLibrary == true
        val shouldBeSaved = !isCurrentlySaved

        val channelId = currentPodcast.channelId ?: currentPodcast.author?.id
        Timber.d("[PODCAST_TOGGLE] toggleSubscription called: podcastId=${currentPodcast.id}, channelId=$channelId, authorId=${currentPodcast.author?.id}, isCurrentlySaved=$isCurrentlySaved, shouldBeSaved=$shouldBeSaved")

        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("[PODCAST_TOGGLE] Inside coroutine, updating database...")

            if (existingEntity != null) {
                val updated = existingEntity.toggleBookmark()
                Timber.d("[PODCAST_TOGGLE] Updating existing entity: bookmarkedAt=${updated.bookmarkedAt}")
                database.update(updated)
            } else {
                val newEntity = PodcastEntity(
                    id = currentPodcast.id,
                    title = currentPodcast.title,
                    author = currentPodcast.author?.name,
                    thumbnailUrl = currentPodcast.thumbnail,
                    channelId = channelId,
                    bookmarkedAt = LocalDateTime.now(),
                )
                Timber.d("[PODCAST_TOGGLE] Inserting new entity: ${newEntity.id}")
                database.insert(newEntity)
            }

            Timber.d("[PODCAST_TOGGLE] Database updated, calling syncUtils.savePodcast(${currentPodcast.id}, $shouldBeSaved)")
            // Sync with YouTube (handles login check internally)
            syncUtils.savePodcast(currentPodcast.id, shouldBeSaved)
        }
    }

    /**
     * Legacy method - now calls toggleSubscription
     */
    fun toggleLibrary() = toggleSubscription()

    fun retry() {
        fetchPodcastData()
    }
}
