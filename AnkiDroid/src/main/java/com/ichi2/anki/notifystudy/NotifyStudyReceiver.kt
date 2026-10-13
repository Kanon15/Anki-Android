// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.R
import com.ichi2.anki.common.android.AnkiBroadcastReceiver
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.runGloballyWithTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * Handles the notification study alarm and the buttons of its notifications.
 * Not exported: only this app can send these broadcasts.
 */
class NotifyStudyReceiver : AnkiBroadcastReceiver() {
    override fun onReceiveBroadcast(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val cardId = intent.getLongExtra(EXTRA_CARD_ID, NO_CARD)
        Timber.i("notify study: received %s for card %d", action, cardId)
        runGloballyWithTimeout(TIMEOUT) {
            handle(context, action, cardId, intent.getIntExtra(EXTRA_RATING, -1))
        }
    }

    companion object {
        const val ACTION_ROUND = "com.ichi2.anki.notifystudy.ROUND"
        const val ACTION_REVEAL = "com.ichi2.anki.notifystudy.REVEAL"
        const val ACTION_ANSWER = "com.ichi2.anki.notifystudy.ANSWER"
        const val ACTION_SKIP = "com.ichi2.anki.notifystudy.SKIP"

        /** delete intents: the user removed a notification which must stay */
        const val ACTION_RESTORE_QUESTION = "com.ichi2.anki.notifystudy.RESTORE_QUESTION"
        const val ACTION_RESTORE_ANSWER = "com.ichi2.anki.notifystudy.RESTORE_ANSWER"

        private const val EXTRA_CARD_ID = "card_id"
        private const val EXTRA_RATING = "rating"
        private const val NO_CARD = -1L
        private val TIMEOUT = 8.seconds

        fun getIntent(
            context: Context,
            action: String,
            cardId: CardId? = null,
            rating: Rating? = null,
        ): Intent =
            Intent(context, NotifyStudyReceiver::class.java).apply {
                this.action = action
                // a distinct data URI makes each card/button combination a distinct PendingIntent
                data = "ankidroid-notify-study://$action/${cardId ?: 0}/${rating?.number ?: 0}".toUri()
                cardId?.let { putExtra(EXTRA_CARD_ID, it) }
                rating?.let { putExtra(EXTRA_RATING, it.number) }
            }

        internal suspend fun handle(
            context: Context,
            action: String,
            cardId: CardId,
            ratingNumber: Int,
        ) {
            when (action) {
                ACTION_ROUND -> {
                    // schedule first, so a failing round does not stop future rounds
                    NotifyStudyAlarm.scheduleNext(context)
                    NotifyStudyEngine.runRound(context)
                }
                ACTION_REVEAL -> if (cardId != NO_CARD) NotifyStudyEngine.reveal(context, cardId)
                ACTION_SKIP -> NotifyStudyEngine.skipRound(context)
                ACTION_RESTORE_QUESTION -> if (cardId != NO_CARD) NotifyStudyEngine.restore(context, cardId, revealed = false)
                ACTION_RESTORE_ANSWER -> if (cardId != NO_CARD) NotifyStudyEngine.restore(context, cardId, revealed = true)
                ACTION_ANSWER -> {
                    val rating = Rating.forNumber(ratingNumber)
                    if (cardId == NO_CARD || rating == null || rating == Rating.UNRECOGNIZED) {
                        Timber.w("notify study: invalid answer %d / %d", cardId, ratingNumber)
                        return
                    }
                    val result = NotifyStudyEngine.answer(context, cardId, rating)
                    val message =
                        when (result) {
                            AnswerResult.ANSWERED -> null
                            AnswerResult.ALREADY_STUDIED -> R.string.notify_study_already_studied
                            AnswerResult.FAILED -> R.string.notify_study_answer_failed
                        }
                    if (message != null) {
                        withContext(Dispatchers.Main) { showThemedToast(context, context.getString(message), true) }
                    }
                }
                else -> Timber.w("notify study: unknown action %s", action)
            }
        }
    }
}

/**
 * Restores the notification study schedule after a reboot or an app update.
 * Alarms are cleared on reboot, so they must be scheduled again.
 */
class NotifyStudyBootReceiver : AnkiBroadcastReceiver() {
    override fun onReceiveBroadcast(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("notify study: restoring schedule after %s", intent.action)
                NotifyStudyAlarm.scheduleNext(context)
            }
        }
    }
}
