// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.app.NotificationManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.SchedulingStates
import anki.scheduler.cardAnswer
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.NOTIFICATION_MIN_DELAY
import com.ichi2.anki.cardviewer.AndroidCardRenderContext
import com.ichi2.anki.cardviewer.SingleCardSide
import com.ichi2.anki.cardviewer.TypeAnswer
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Decks
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** The content of a card, prepared for display in a notification */
data class StudyCard(
    val cardId: CardId,
    val reps: Int,
    val deckName: String,
    val question: String,
    val answer: String,
)

/** A card revealed in a notification, with the labels of the four answer buttons */
data class RevealedCard(
    val card: StudyCard,
    /** Next intervals for again/hard/good/easy, e.g. "<1m", "6m", "10m", "4d" */
    val intervals: List<String>,
)

/**
 * The card pages for the practice pad, rendered like the reviewer renders them:
 * AnkiDroid's card template and stylesheets, theme classes (night mode), card CSS, media.
 */
data class CardHtml(
    val cardId: CardId,
    val deckName: String,
    val questionPage: String,
    val answerPage: String,
    val mediaDir: String,
)

enum class RoundResult {
    DISABLED,
    DO_NOT_DISTURB,
    NOTIFICATIONS_BLOCKED,
    NO_CARDS,
    SHOWN,
    FAILED,
}

enum class AnswerResult {
    ANSWERED,

    /** The card was studied elsewhere since it was shown, so the answer was not recorded */
    ALREADY_STUDIED,
    FAILED,
}

/**
 * Notification study: every [NotifyStudyPrefs.intervalMinutes] minutes, the top cards of the study
 * queue are shown as ongoing notifications, which can be revealed and answered without opening the app.
 *
 * See [RoundPlanner] for which cards are shown.
 */
object NotifyStudyEngine {
    /** Serializes changes to the pending state, e.g. an answer arriving while a round is being built */
    private val mutex = Mutex()

    /**
     * Shows a round of cards.
     *
     * @param manual true if the user asked for the round: the feature does not need to be enabled,
     * and Do Not Disturb is ignored.
     */
    suspend fun runRound(
        context: Context,
        manual: Boolean = false,
    ): RoundResult =
        mutex.withLock {
            val prefs = NotifyStudyPrefs(context)
            if (!manual && !prefs.enabled) {
                Timber.i("notify study: disabled, skipping round")
                return@withLock RoundResult.DISABLED
            }
            if (!manual && isDoNotDisturbActive(context)) {
                // the cards stay pending: they will be shown on the next round instead of piling up
                Timber.i("notify study: do not disturb is active, skipping round")
                return@withLock RoundResult.DO_NOT_DISTURB
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Timber.w("notify study: notifications are blocked")
                return@withLock RoundResult.NOTIFICATIONS_BLOCKED
            }

            val previous = prefs.pending
            val planned =
                try {
                    withCol {
                        val stillPending =
                            previous
                                .filter { isStillPending(it) }
                                .map { PlannedCard(it.cardId, it.sourceDeckId) }
                        val decks = selectedDecks(prefs.deckIds)
                        // enough to replace carried over cards, and cards found in several decks
                        val fetchLimit = (prefs.cardCount + stillPending.size + NotifyStudyPrefs.MAX_NOTIFICATIONS).coerceAtMost(100)
                        val queues = queuedCardIds(decks, fetchLimit)
                        RoundPlanner
                            .plan(stillPending, queues, prefs.countMode, prefs.cardCount)
                            .mapNotNull { planned -> runCatching { studyCard(getCard(planned.cardId)) to planned }.getOrNull() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "notify study: failed to build round")
                    return@withLock RoundResult.FAILED
                }
            val cards = planned.map { it.first }

            val now = TimeManager.time.intTimeMS()
            val newIds = cards.map { it.cardId }.toSet()
            for (old in previous) {
                if (old.cardId !in newIds) NotifyStudyNotifications.cancel(context, old.cardId)
            }
            // cards which are still on screen keep their original time, so 'time taken' stays meaningful
            val shownAt = previous.associate { it.cardId to it.shownAtMs }
            prefs.pending =
                planned.map { (card, plan) ->
                    val keepTime = shownAt[card.cardId]?.takeIf { isOnScreen(context, card.cardId) }
                    PendingCard(card.cardId, card.reps, keepTime ?: now, plan.sourceDeckId)
                }

            cards.forEachIndexed { index, card ->
                // Android drops notifications posted faster than its rate limit
                if (index > 0) delay(NOTIFICATION_MIN_DELAY)
                NotifyStudyNotifications.showQuestion(context, card, index + 1, cards.size, alert = index == 0)
            }
            Timber.i("notify study: showing %d card(s)", cards.size)
            if (cards.isEmpty()) RoundResult.NO_CARDS else RoundResult.SHOWN
        }

    /** Shows the answer of a card, with the answer buttons */
    suspend fun reveal(
        context: Context,
        cardId: CardId,
    ) {
        val prefs = NotifyStudyPrefs(context)
        val pending = prefs.pending
        val index = pending.indexOfFirst { it.cardId == cardId }
        val revealed =
            try {
                withCol {
                    val card = getCard(cardId)
                    val states = backend.getSchedulingStates(cardId)
                    RevealedCard(studyCard(card), sched.describeNextStates(states))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "notify study: failed to reveal card")
                NotifyStudyNotifications.cancel(context, cardId)
                prefs.removePending(cardId)
                return
            }
        NotifyStudyNotifications.showAnswer(
            context,
            revealed,
            position = if (index >= 0) index + 1 else 1,
            total = if (index >= 0) pending.size else 1,
        )
    }

    /**
     * Records an answer for a card, as if it was answered in the reviewer.
     *
     * @param timeTakenMs time spent on the card. If null, estimated from the time the notification was shown.
     */
    suspend fun answer(
        context: Context,
        cardId: CardId,
        rating: Rating,
        timeTakenMs: Long? = null,
    ): AnswerResult =
        mutex.withLock {
            val prefs = NotifyStudyPrefs(context)
            val pending = prefs.pending.firstOrNull { it.cardId == cardId }
            val result =
                try {
                    withCol {
                        val card = getCard(cardId)
                        if (pending != null && card.reps != pending.reps) {
                            return@withCol AnswerResult.ALREADY_STUDIED
                        }
                        ensureAnswerable(cardId)
                        val now = TimeManager.time.intTimeMS()
                        val taken = timeTakenMs ?: (now - (pending?.shownAtMs ?: now))
                        val states = backend.getSchedulingStates(cardId)
                        val changes =
                            sched.answerCard(
                                cardAnswer {
                                    this.cardId = cardId
                                    currentState = states.current
                                    newState = states.forRating(rating)
                                    this.rating = rating
                                    answeredAtMillis = now
                                    millisecondsTaken = taken.coerceIn(0, card.timeLimit(this@withCol).toLong()).toInt()
                                },
                            )
                        ChangeManager.publish(changes)
                        AnswerResult.ANSWERED
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "notify study: failed to answer card")
                    AnswerResult.FAILED
                }
            if (result != AnswerResult.FAILED) {
                NotifyStudyNotifications.cancel(context, cardId)
                prefs.removePending(cardId)
            }
            Timber.i("notify study: answer %s -> %s", rating, result)
            result
        }

    /**
     * Hides the cards of the current round. They stay pending, so they are shown again on the next round.
     */
    suspend fun skipRound(context: Context) =
        mutex.withLock {
            val prefs = NotifyStudyPrefs(context)
            for (card in prefs.pending) {
                NotifyStudyNotifications.cancel(context, card.cardId)
            }
            Timber.i("notify study: round skipped")
        }

    /** Removes all notifications and pending cards, e.g. when the feature is disabled */
    suspend fun clear(context: Context) =
        mutex.withLock {
            val prefs = NotifyStudyPrefs(context)
            for (card in prefs.pending) {
                NotifyStudyNotifications.cancel(context, card.cardId)
            }
            prefs.pending = emptyList()
        }

    /**
     * @param context a themed context: the page follows the current day/night theme
     */
    suspend fun cardHtml(
        context: Context,
        cardId: CardId,
    ): CardHtml =
        withCol {
            val card = getCard(cardId)
            val renderer =
                AndroidCardRenderContext.createInstance(
                    context,
                    this,
                    TypeAnswer.createInstance(context.sharedPrefs()),
                )
            CardHtml(
                cardId = cardId,
                deckName = deckName(card),
                questionPage = renderer.renderCard(this, card, SingleCardSide.FRONT).html,
                answerPage = renderer.renderCard(this, card, SingleCardSide.BACK).html,
                mediaDir = media.dir.absolutePath,
            )
        }

    /** True if the user enabled Do Not Disturb (any mode other than 'all notifications allowed') */
    fun isDoNotDisturbActive(context: Context): Boolean {
        val filter = context.getSystemService<NotificationManager>()?.currentInterruptionFilter ?: return false
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun isOnScreen(
        context: Context,
        cardId: CardId,
    ): Boolean = NotifyStudyNotifications.isShowing(context, cardId)

    /** A card shown earlier is still pending if it exists, wasn't studied since, and isn't suspended or buried */
    private fun Collection.isStillPending(pending: PendingCard): Boolean {
        val card = runCatching { getCard(pending.cardId) }.getOrNull() ?: return false
        return card.reps == pending.reps && card.queue.code >= 0
    }

    /**
     * The backend only accepts answers for the card at the top of its cached study queue.
     * Notification cards can be answered in any order (or belong to another deck), so if the card
     * isn't at the top, the cached queue is discarded first. Without a cached queue, the backend
     * accepts an answer for any card.
     *
     * A no-op write through the database proxy discards the cached queues. It also discards the
     * undo history, so it is only done when needed.
     */
    private fun Collection.ensureAnswerable(cardId: CardId) {
        val top =
            backend
                .getQueuedCards(fetchLimit = 1, intradayLearningOnly = false)
                .cardsList
                .firstOrNull()
                ?.card
                ?.id
        if (top == cardId) return
        Timber.d("notify study: card is not at the top of the queue, discarding cached queues")
        db.execute("update cards set mod = mod where id = ?", cardId)
    }

    /** The selected decks which still exist, or [NotifyStudyPrefs.CURRENT_DECK] if there are none */
    private fun Collection.selectedDecks(deckIds: List<DeckId>): List<DeckId> =
        deckIds
            .filter { decks.get(it, default = false) != null }
            .ifEmpty { listOf(NotifyStudyPrefs.CURRENT_DECK) }

    /**
     * The top of the study queue of each deck, in the order the reviewer would show them
     * (including daily limits, learning cards and subdecks).
     *
     * The backend only builds the queue of the current deck, so each deck is selected in turn,
     * then the user's deck is selected again.
     */
    @VisibleForTesting
    internal fun Collection.queuedCardIds(
        deckIds: List<DeckId>,
        fetchLimit: Int,
    ): Map<DeckId, List<CardId>> {
        val previous = decks.selected()
        val result = LinkedHashMap<DeckId, List<CardId>>()
        try {
            for (deckId in deckIds) {
                val target = if (deckId == NotifyStudyPrefs.CURRENT_DECK) previous else deckId
                if (decks.selected() != target) decks.select(target)
                result[deckId] =
                    backend
                        .getQueuedCards(fetchLimit = fetchLimit, intradayLearningOnly = false)
                        .cardsList
                        .map { it.card.id }
            }
        } finally {
            if (decks.selected() != previous) decks.select(previous)
        }
        return result
    }

    private fun Collection.deckName(card: Card): String = Decks.basename(decks.name(card.currentDeckId()))

    private fun Collection.studyCard(card: Card): StudyCard {
        val output = card.renderOutput(this, reload = true)
        return StudyCard(
            cardId = card.id,
            reps = card.reps,
            deckName = deckName(card),
            question = CardText.toPlainText(output.questionText),
            answer = CardText.toPlainText(CardText.answerOnly(output.answerText)),
        )
    }

    private fun SchedulingStates.forRating(rating: Rating) =
        when (rating) {
            Rating.AGAIN -> again
            Rating.HARD -> hard
            Rating.GOOD -> good
            Rating.EASY -> easy
            Rating.UNRECOGNIZED -> error("unrecognized rating")
        }
}
