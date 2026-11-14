/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * FletchTunes queue sync with the screen off. In Doze the app's network is blocked and its
 * coroutine timers stop while the CPU sleeps, so a background sync used to wait 4 to 7 minutes
 * for the next maintenance window. An allow-while-idle alarm fixes both: it wakes the CPU on time,
 * and Android lets the app use the network for about 10 seconds after it fires.
 */
object QueueSyncAlarm {
    fun set(context: Context, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            }
        } catch (e: SecurityException) {
            Timber.tag("QueueSync").w(e, "Could not set the sync alarm")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, QueueSyncAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Runs the due sync while the alarm's network window is open, and keeps the process up until it ends. */
@AndroidEntryPoint
class QueueSyncAlarmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var repository: QueueSyncRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.runDueFromAlarm()
            } finally {
                pending.finish()
            }
        }
    }
}
