// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.R
import com.ichi2.anki.libanki.CardId
import timber.log.Timber

/**
 * Builds and posts the notifications of [NotifyStudyEngine].
 *
 * The notifications are ongoing and flagged 'no clear', so "Clear all" does not remove them.
 * On Android 14+ a user may still swipe a single one away; the card stays pending and is shown
 * again on the next round.
 */
object NotifyStudyNotifications {
    const val CHANNEL_ID = "notify_study"
    private const val TAG_PREFIX = "com.ichi2.anki.notify_study:"
    private const val NOTIFICATION_ID = 4501

    /** Maximum characters of the question shown in a single notification line */
    private const val MAX_TEXT_LENGTH = 1200

    fun tag(cardId: CardId) = "$TAG_PREFIX$cardId"

    fun ensureChannel(context: Context) {
        val channel =
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.notify_study_channel_name))
                .setDescription(context.getString(R.string.notify_study_channel_description))
                .setShowBadge(true)
                .setVibrationEnabled(true)
                .setVibrationPattern(longArrayOf(0, 300))
                .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun showQuestion(
        context: Context,
        card: StudyCard,
        position: Int,
        total: Int,
        alert: Boolean,
    ) {
        val question = card.question.ifBlank { context.getString(R.string.notify_study_empty_card) }.take(MAX_TEXT_LENGTH)
        val builder =
            baseBuilder(context, card, position, total)
                .setContentText(question)
                .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                .setSilent(!alert)
                .addAction(
                    R.drawable.ic_star_notify,
                    context.getString(R.string.notify_study_show_answer),
                    broadcast(context, NotifyStudyReceiver.ACTION_REVEAL, card.cardId),
                ).addAction(
                    R.drawable.ic_draw_filled,
                    context.getString(R.string.notify_study_practice_pad),
                    practicePad(context, card.cardId),
                ).addAction(
                    R.drawable.ic_skip_next,
                    context.getString(R.string.notify_study_skip),
                    broadcast(context, NotifyStudyReceiver.ACTION_SKIP, card.cardId),
                )
        post(context, card.cardId, builder)
    }

    fun showAnswer(
        context: Context,
        revealed: RevealedCard,
        position: Int,
        total: Int,
    ) {
        val card = revealed.card
        val answer = card.answer.ifBlank { context.getString(R.string.notify_study_empty_card) }.take(MAX_TEXT_LENGTH)
        val question = card.question.take(MAX_TEXT_LENGTH)

        val collapsed =
            RemoteViews(context.packageName, R.layout.view_notify_study_answer_collapsed).apply {
                setTextViewText(R.id.notify_study_answer, answer)
            }
        val expanded =
            RemoteViews(context.packageName, R.layout.view_notify_study_answer_expanded).apply {
                setTextViewText(R.id.notify_study_question, question)
                setTextViewText(R.id.notify_study_answer, answer)
                val buttons =
                    listOf(
                        Triple(R.id.notify_study_again, Rating.AGAIN, R.string.notify_study_again),
                        Triple(R.id.notify_study_hard, Rating.HARD, R.string.notify_study_hard),
                        Triple(R.id.notify_study_good, Rating.GOOD, R.string.notify_study_good),
                        Triple(R.id.notify_study_easy, Rating.EASY, R.string.notify_study_easy),
                    )
                buttons.forEachIndexed { index, (id, rating, label) ->
                    val interval = revealed.intervals.getOrNull(index)
                    val text = context.getString(label) + (interval?.let { "\n$it" } ?: "")
                    setTextViewText(id, text)
                    setOnClickPendingIntent(id, broadcast(context, NotifyStudyReceiver.ACTION_ANSWER, card.cardId, rating))
                }
            }

        val builder =
            baseBuilder(context, card, position, total)
                .setContentText(answer)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setSilent(true)
                .addAction(
                    R.drawable.ic_draw_filled,
                    context.getString(R.string.notify_study_practice_pad),
                    practicePad(context, card.cardId),
                ).addAction(
                    R.drawable.ic_skip_next,
                    context.getString(R.string.notify_study_skip),
                    broadcast(context, NotifyStudyReceiver.ACTION_SKIP, card.cardId),
                )
        post(context, card.cardId, builder)
    }

    fun cancel(
        context: Context,
        cardId: CardId,
    ) {
        context.getSystemService<NotificationManager>()?.cancel(tag(cardId), NOTIFICATION_ID)
    }

    fun isShowing(
        context: Context,
        cardId: CardId,
    ): Boolean {
        val manager = context.getSystemService<NotificationManager>() ?: return false
        val tag = tag(cardId)
        return runCatching { manager.activeNotifications.any { it.tag == tag && it.id == NOTIFICATION_ID } }.getOrDefault(false)
    }

    private fun baseBuilder(
        context: Context,
        card: StudyCard,
        position: Int,
        total: Int,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star_notify)
            .setColor(context.getColor(R.color.notify_study_accent))
            .setContentTitle(context.getString(R.string.notify_study_title, card.deckName, position, total))
            .setSubText(context.getString(R.string.notify_study_subtext))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setOnlyAlertOnce(false)
            .setContentIntent(practicePad(context, card.cardId))

    private fun post(
        context: Context,
        cardId: CardId,
        builder: NotificationCompat.Builder,
    ) {
        ensureChannel(context)
        val notification = builder.build()
        // not removed by "Clear all"
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        val manager = context.getSystemService<NotificationManager>()
        if (manager == null) {
            Timber.w("notify study: no NotificationManager")
            return
        }
        manager.notify(tag(cardId), NOTIFICATION_ID, notification)
    }

    private fun broadcast(
        context: Context,
        action: String,
        cardId: CardId,
        rating: Rating? = null,
    ): PendingIntent? =
        PendingIntentCompat.getBroadcast(
            context,
            0,
            NotifyStudyReceiver.getIntent(context, action, cardId, rating),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )

    private fun practicePad(
        context: Context,
        cardId: CardId,
    ): PendingIntent? =
        PendingIntentCompat.getActivity(
            context,
            0,
            PracticePadActivity.getIntent(context, cardId),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )
}
