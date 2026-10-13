// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.QueueType
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NotifyStudyEngineTest : RobolectricTest() {
    private val notificationManager get() = targetContext.getSystemService<NotificationManager>()!!
    private val prefs get() = NotifyStudyPrefs(targetContext)

    @Before
    override fun setUp() {
        super.setUp()
        shadowOf(notificationManager).apply {
            setNotificationsEnabled(true)
            setNotificationPolicyAccessGranted(true)
        }
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        prefs.apply {
            enabled = true
            cardCount = 2
            countMode = CountMode.TOTAL
            deckIds = emptyList()
            pending = emptyList()
        }
    }

    /** card ids which currently have a notification study notification */
    private fun shownCards(): List<CardId> =
        shadowOf(notificationManager)
            .activeNotifications
            .filter { it.tag?.startsWith("com.ichi2.anki.notify_study:") == true }
            .map { it.tag.substringAfter(':').toLong() }

    private fun notificationOf(cardId: CardId): Notification? =
        shadowOf(notificationManager).activeNotifications.firstOrNull { it.tag == NotifyStudyNotifications.tag(cardId) }?.notification

    @Test
    fun `round shows the configured number of cards as ongoing notifications`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 5)

            val result = NotifyStudyEngine.runRound(targetContext)

            assertThat(result, equalTo(RoundResult.SHOWN))
            val shown = shownCards()
            assertThat(shown, hasSize(2))
            assertThat(prefs.pending.map { it.cardId }, containsInAnyOrder(*shown.toTypedArray()))
            for (cid in shown) {
                val notification = notificationOf(cid)!!
                assertThat("not cleared by 'clear all'", notification.flags and Notification.FLAG_NO_CLEAR, not(equalTo(0)))
                assertThat("ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT, not(equalTo(0)))
                assertThat(notification.channelId, equalTo(NotifyStudyNotifications.CHANNEL_ID))
                assertThat(notification.actions.map { it.title.toString() }, equalTo(listOf("답 보기", "연습장", "넘기기")))
                assertThat(notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(), equalTo("Front"))
            }
        }

    @Test
    fun `no round when disabled`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 2)
            prefs.enabled = false

            assertThat(NotifyStudyEngine.runRound(targetContext), equalTo(RoundResult.DISABLED))
            assertThat(shownCards(), empty())
        }

    @Test
    fun `no round in do not disturb mode, and nothing piles up afterwards`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 5)
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

            assertThat(NotifyStudyEngine.runRound(targetContext), equalTo(RoundResult.DO_NOT_DISTURB))
            assertThat(NotifyStudyEngine.runRound(targetContext), equalTo(RoundResult.DO_NOT_DISTURB))
            assertThat(shownCards(), empty())

            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            assertThat(NotifyStudyEngine.runRound(targetContext), equalTo(RoundResult.SHOWN))
            assertThat("only the configured number of cards", shownCards(), hasSize(2))
        }

    @Test
    fun `manual round ignores do not disturb and the enabled setting`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 3)
            prefs.enabled = false
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

            assertThat(NotifyStudyEngine.runRound(targetContext, manual = true), equalTo(RoundResult.SHOWN))
            assertThat(shownCards(), hasSize(2))
        }

    @Test
    fun `answering records a review and removes the notification`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 3)
            NotifyStudyEngine.runRound(targetContext)
            val cid = shownCards().first()
            val repsBefore = col.getCard(cid).reps

            val result = NotifyStudyEngine.answer(targetContext, cid, Rating.GOOD)

            assertThat(result, equalTo(AnswerResult.ANSWERED))
            val card = col.getCard(cid)
            assertThat(card.reps, equalTo(repsBefore + 1))
            assertThat(card.queue, not(equalTo(QueueType.New)))
            assertThat(notificationOf(cid), nullValue())
            assertThat(prefs.pending.map { it.cardId }, not(hasItem(cid)))
            assertThat("revlog entry", col.db.queryScalar("select count() from revlog where cid = ?", cid), equalTo(1))
        }

    @Test
    fun `cards can be answered in any order`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 3)
            NotifyStudyEngine.runRound(targetContext)
            val top =
                col.backend
                    .getQueuedCards(fetchLimit = 1, intradayLearningOnly = false)
                    .cardsList
                    .first()
                    .card.id
            val notTop = shownCards().first { it != top }

            assertThat(NotifyStudyEngine.answer(targetContext, notTop, Rating.GOOD), equalTo(AnswerResult.ANSWERED))
            assertThat(NotifyStudyEngine.answer(targetContext, top, Rating.GOOD), equalTo(AnswerResult.ANSWERED))
            assertThat(col.db.queryScalar("select count() from revlog"), equalTo(2))
        }

    @Test
    fun `answering the top card keeps the undo history`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 3)
            NotifyStudyEngine.runRound(targetContext)
            val top =
                col.backend
                    .getQueuedCards(fetchLimit = 1, intradayLearningOnly = false)
                    .cardsList
                    .first()
                    .card.id

            NotifyStudyEngine.answer(targetContext, top, Rating.GOOD)

            assertThat("answer can be undone", col.undoStatus().undo, notNullValue())
        }

    @Test
    fun `easy answer graduates a new card`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 1)
            NotifyStudyEngine.runRound(targetContext)
            val cid = shownCards().single()

            NotifyStudyEngine.answer(targetContext, cid, Rating.EASY)

            assertThat(col.getCard(cid).queue, equalTo(QueueType.Rev))
        }

    @Test
    fun `unanswered cards are shown again, topped up to the limit`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 5)
            NotifyStudyEngine.runRound(targetContext)
            val (answered, unanswered) = shownCards().sorted()

            NotifyStudyEngine.answer(targetContext, answered, Rating.EASY)
            NotifyStudyEngine.runRound(targetContext)

            val second = shownCards()
            assertThat(second, hasSize(2))
            assertThat(second, hasItem(unanswered))
            assertThat(second, not(hasItem(answered)))
        }

    @Test
    fun `skipping hides the round and shows the same cards on the next round`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 5)
            NotifyStudyEngine.runRound(targetContext)
            val first = shownCards()

            NotifyStudyEngine.skipRound(targetContext)
            assertThat(shownCards(), empty())
            assertThat("cards stay pending", prefs.pending.map { it.cardId }, containsInAnyOrder(*first.toTypedArray()))

            NotifyStudyEngine.runRound(targetContext)
            assertThat(shownCards(), containsInAnyOrder(*first.toTypedArray()))
        }

    @Test
    fun `a swiped away card comes back on the next round`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 5)
            NotifyStudyEngine.runRound(targetContext)
            val first = shownCards()
            notificationManager.cancel(NotifyStudyNotifications.tag(first[0]), 4501)

            NotifyStudyEngine.runRound(targetContext)

            assertThat(shownCards(), containsInAnyOrder(*first.toTypedArray()))
        }

    @Test
    fun `card studied elsewhere is not answered twice`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 3)
            NotifyStudyEngine.runRound(targetContext)
            // studied in the reviewer, which always answers the card at the top of the queue
            val cid =
                col.backend
                    .getQueuedCards(fetchLimit = 1, intradayLearningOnly = false)
                    .cardsList
                    .first()
                    .card.id
            assertThat(shownCards(), hasItem(cid))
            col.sched.answerCard(col.getCard(cid), Rating.GOOD)

            assertThat(NotifyStudyEngine.answer(targetContext, cid, Rating.EASY), equalTo(AnswerResult.ALREADY_STUDIED))
            assertThat(col.db.queryScalar("select count() from revlog where cid = ?", cid), equalTo(1))
            assertThat(notificationOf(cid), nullValue())
        }

    @Test
    fun `reveal shows the answer with four answer buttons`() =
        runTest {
            val deckId = addDeck("Deck", setAsSelected = true)
            addBasicNote("Question text", "Answer text").firstCard().update { did = deckId }
            NotifyStudyEngine.runRound(targetContext)
            val cid = shownCards().single()

            NotifyStudyEngine.reveal(targetContext, cid)

            val notification = notificationOf(cid)
            assertThat(notification, notNullValue())
            assertThat(notification!!.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(), equalTo("Answer text"))
            assertThat(
                "custom view with the answer buttons",
                notification.extras.getString(Notification.EXTRA_TEMPLATE),
                equalTo("android.app.Notification\$DecoratedCustomViewStyle"),
            )
            assertThat(notification.actions.map { it.title.toString() }, equalTo(listOf("연습장", "넘기기")))
            assertThat("still not clearable", notification.flags and Notification.FLAG_NO_CLEAR, not(equalTo(0)))
        }

    @Test
    fun `round uses the configured deck without changing the selected deck`() =
        runTest {
            val selected = addDeck("Selected", setAsSelected = true).withNotes(count = 2)
            val other = addDeck("Other").withNotes(count = 2)
            prefs.deckIds = listOf(other)

            NotifyStudyEngine.runRound(targetContext)

            val decks = shownCards().map { col.getCard(it).did }
            assertThat(decks, equalTo(listOf(other, other)))
            assertThat(col.decks.selected(), equalTo(selected))
        }

    @Test
    fun `several decks - per deck count`() =
        runTest {
            val selected = addDeck("Selected", setAsSelected = true).withNotes(count = 1)
            val first = addDeck("First").withNotes(count = 4)
            val second = addDeck("Second").withNotes(count = 4)
            val third = addDeck("Third").withNotes(count = 1)
            prefs.deckIds = listOf(first, second, third)
            prefs.countMode = CountMode.PER_DECK
            prefs.cardCount = 2

            NotifyStudyEngine.runRound(targetContext)

            val decks = shownCards().map { col.getCard(it).did }
            assertThat(decks.count { it == first }, equalTo(2))
            assertThat(decks.count { it == second }, equalTo(2))
            assertThat(decks.count { it == third }, equalTo(1))
            assertThat(decks, hasSize(5))
            assertThat("user's deck is unchanged", col.decks.selected(), equalTo(selected))
        }

    @Test
    fun `several decks - total count shared between the decks`() =
        runTest {
            addDeck("Selected", setAsSelected = true).withNotes(count = 1)
            val first = addDeck("First").withNotes(count = 4)
            val second = addDeck("Second").withNotes(count = 4)
            prefs.deckIds = listOf(first, second)
            prefs.countMode = CountMode.TOTAL
            prefs.cardCount = 3

            NotifyStudyEngine.runRound(targetContext)

            val decks = shownCards().map { col.getCard(it).did }
            assertThat(decks, hasSize(3))
            assertThat(decks.count { it == first }, equalTo(2))
            assertThat(decks.count { it == second }, equalTo(1))
        }

    @Test
    fun `several decks - unanswered cards are carried over per deck without piling up`() =
        runTest {
            addDeck("Selected", setAsSelected = true)
            val first = addDeck("First").withNotes(count = 4)
            val second = addDeck("Second").withNotes(count = 4)
            prefs.deckIds = listOf(first, second)
            prefs.countMode = CountMode.PER_DECK
            prefs.cardCount = 2
            NotifyStudyEngine.runRound(targetContext)
            val firstRound = shownCards()
            val answered = firstRound.first { col.getCard(it).did == first }

            NotifyStudyEngine.answer(targetContext, answered, Rating.EASY)
            NotifyStudyEngine.runRound(targetContext)

            val second2 = shownCards()
            assertThat(second2, hasSize(4))
            assertThat("unanswered cards shown again", second2.containsAll(firstRound - answered), equalTo(true))
            assertThat(second2.count { col.getCard(it).did == first }, equalTo(2))
            assertThat(second2.count { col.getCard(it).did == second }, equalTo(2))
        }

    @Test
    fun `a deleted deck falls back to the current deck`() =
        runTest {
            addDeck("Selected", setAsSelected = true).withNotes(count = 2)
            val removed = addDeck("Removed").withNotes(count = 2)
            prefs.deckIds = listOf(removed)
            col.decks.remove(listOf(removed))

            assertThat(NotifyStudyEngine.runRound(targetContext), equalTo(RoundResult.SHOWN))
            assertThat(shownCards(), hasSize(2))
        }

    @Test
    fun `suspended pending card is dropped`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 4)
            NotifyStudyEngine.runRound(targetContext)
            val suspended = shownCards().first()
            col.sched.suspendCards(listOf(suspended))

            NotifyStudyEngine.runRound(targetContext)

            assertThat(shownCards(), not(hasItem(suspended)))
            assertThat(shownCards(), hasSize(2))
        }

    @Test
    fun `receiver answer action records the answer`() =
        runTest {
            addDeck("Deck", setAsSelected = true).withNotes(count = 2)
            NotifyStudyEngine.runRound(targetContext)
            val cid = shownCards().first()

            NotifyStudyReceiver.handle(targetContext, NotifyStudyReceiver.ACTION_ANSWER, cid, Rating.HARD.number)

            assertThat(col.db.queryScalar("select count() from revlog where cid = ?", cid), equalTo(1))
            assertThat(notificationOf(cid), nullValue())
        }

    @Test
    fun `no cards to study`() =
        runTest {
            addDeck("Empty", setAsSelected = true)

            assertThat(NotifyStudyEngine.runRound(targetContext), equalTo(RoundResult.NO_CARDS))
            assertThat(shownCards(), empty())
        }
}
