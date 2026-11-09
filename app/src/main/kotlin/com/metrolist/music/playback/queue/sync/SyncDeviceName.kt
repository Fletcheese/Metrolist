/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.metrolist.music.constants.QueueSyncDeviceNameKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

/** The phone's own name ("OnePlus 6T"), as set in Android's About phone, else its model. */
fun systemDeviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/** The name other devices see this one by: the Sync setting if set, else [systemDeviceName]. Blocking. */
fun syncDeviceName(context: Context): String =
    context.dataStore.get(QueueSyncDeviceNameKey, "").trim().ifEmpty { systemDeviceName(context) }
