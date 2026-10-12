// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.PendingIntentCompat
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.utils.AlarmManagement
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes

/**
 * Schedules the next notification study round.
 *
 * Only the next round is scheduled; each round schedules the following one (see [NotifyStudyReceiver]).
 * [AlarmManager.setAndAllowWhileIdle] is used so rounds also happen in Doze, without requiring
 * the 'exact alarm' permission.
 */
object NotifyStudyAlarm {
    private fun pendingIntent(context: Context): PendingIntent? =
        PendingIntentCompat.getBroadcast(
            context,
            0,
            NotifyStudyReceiver.getIntent(context, NotifyStudyReceiver.ACTION_ROUND),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )

    /** Schedules the next round [NotifyStudyPrefs.intervalMinutes] from now, or cancels it if disabled */
    fun scheduleNext(context: Context) {
        val prefs = NotifyStudyPrefs(context)
        if (!prefs.enabled) {
            cancel(context)
            return
        }
        val pendingIntent = pendingIntent(context) ?: return
        val triggerAt = TimeManager.time.intTimeMS() + prefs.intervalMinutes.minutes.inWholeMilliseconds
        AlarmManagement.useAlarmManager(context) { alarmManager ->
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            prefs.nextRoundAtMs = triggerAt
            Timber.i("notify study: next round in %d minutes", prefs.intervalMinutes)
        }
    }

    /** Ensures a round is scheduled, without postponing one which is already scheduled */
    fun ensureScheduled(context: Context) {
        val prefs = NotifyStudyPrefs(context)
        if (!prefs.enabled) return
        val now = TimeManager.time.intTimeMS()
        val next = prefs.nextRoundAtMs
        val alarmExists =
            PendingIntentCompat.getBroadcast(
                context,
                0,
                NotifyStudyReceiver.getIntent(context, NotifyStudyReceiver.ACTION_ROUND),
                PendingIntent.FLAG_NO_CREATE,
                false,
            ) != null
        if (alarmExists && next > now) return
        scheduleNext(context)
    }

    fun cancel(context: Context) {
        val pendingIntent = pendingIntent(context) ?: return
        AlarmManagement.useAlarmManager(context) { alarmManager -> alarmManager.cancel(pendingIntent) }
        pendingIntent.cancel()
        NotifyStudyPrefs(context).nextRoundAtMs = 0
    }
}
