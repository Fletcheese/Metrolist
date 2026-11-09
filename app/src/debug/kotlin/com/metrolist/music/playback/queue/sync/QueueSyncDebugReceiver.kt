/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.metrolist.music.constants.QueueSyncDeviceNameKey
import com.metrolist.music.constants.QueueSyncTokenKey
import com.metrolist.music.constants.QueueSyncUrlKey
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Debug builds only: sets up queue sync from a computer over adb, so the token never has to be
 * typed on the device. Only the adb shell can send it (it needs android.permission.DUMP).
 *
 * The token is not passed as an extra: adbd logs every shell command line to logcat. Pipe it
 * into the app's private files first, then broadcast; the file is read and deleted:
 *
 *   adb exec-in run-as com.metrolist.music.debug sh -c 'cat > files/queue_sync_token' < token.txt
 *   adb shell am broadcast -n com.metrolist.music.debug/com.metrolist.music.playback.queue.sync.QueueSyncDebugReceiver \
 *     [--es url <url>] [--es device <name>] [--ez sync true]
 */
@AndroidEntryPoint
class QueueSyncDebugReceiver : BroadcastReceiver() {
    @Inject
    lateinit var repository: QueueSyncRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tokenFile = File(context.filesDir, "queue_sync_token")
                val token = tokenFile.takeIf { it.exists() }?.readText()?.trim()
                tokenFile.delete()
                context.safeDataStoreEdit { prefs ->
                    token?.takeIf { it.isNotEmpty() }?.let { prefs[QueueSyncTokenKey] = it }
                    intent.getStringExtra("url")?.let { prefs[QueueSyncUrlKey] = it.trim() }
                    intent.getStringExtra("device")?.let { prefs[QueueSyncDeviceNameKey] = it.trim() }
                }
                if (intent.getBooleanExtra("sync", true)) repository.syncNow()
            } finally {
                pending.finish()
            }
        }
    }
}
