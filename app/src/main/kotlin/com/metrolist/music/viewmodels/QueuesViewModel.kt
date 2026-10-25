/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.entities.QueueEntity
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queue.QueueBoard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueuesViewModel
@Inject
constructor(
    val queueBoard: QueueBoard,
) : ViewModel() {
    val activeQueues = queueBoard.activeQueues
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedQueues = queueBoard.archivedQueues
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeQueueId = queueBoard.activeQueueId

    private val _previewSongs = MutableStateFlow<Map<String, List<MediaMetadata>>>(emptyMap())
    val previewSongs = _previewSongs.asStateFlow()

    fun loadPreview(queue: QueueEntity) {
        if (_previewSongs.value.containsKey(queue.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val songs = queueBoard.loadSongs(queue.id)
            _previewSongs.value = _previewSongs.value + (queue.id to songs)
        }
    }

    fun delete(queue: QueueEntity) {
        viewModelScope.launch { queueBoard.delete(queue.id) }
    }

    fun unarchive(queue: QueueEntity) {
        viewModelScope.launch { queueBoard.unarchive(queue.id) }
    }
}
