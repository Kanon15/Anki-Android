// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.Test

class RoundPlannerTest {
    private val single = NotifyStudyPrefs.CURRENT_DECK

    private fun ids(round: List<PlannedCard>) = round.map { it.cardId }

    private fun pending(vararg cards: Pair<CardId, DeckId>) = cards.map { PlannedCard(it.first, it.second) }

    // single deck: both modes behave the same

    @Test
    fun `fills the round from the queue`() {
        for (mode in CountMode.entries) {
            assertThat(ids(RoundPlanner.plan(emptyList(), mapOf(single to listOf(1, 2, 3, 4)), mode, 3)), contains(1L, 2L, 3L))
        }
    }

    @Test
    fun `unanswered cards come first and are not duplicated`() {
        for (mode in CountMode.entries) {
            val round = RoundPlanner.plan(pending(3L to single), mapOf(single to listOf(1, 2, 3, 4)), mode, 3)
            assertThat(ids(round), contains(3L, 1L, 2L))
        }
    }

    @Test
    fun `unanswered cards never exceed the count - work does not pile up`() {
        for (mode in CountMode.entries) {
            val stillPending = pending(10L to single, 11L to single, 12L to single, 13L to single)
            val round = RoundPlanner.plan(stillPending, mapOf(single to listOf(1, 2)), mode, 3)
            assertThat(ids(round), contains(10L, 11L, 12L))
        }
    }

    @Test
    fun `empty queue and nothing pending`() {
        assertThat(RoundPlanner.plan(emptyList(), mapOf(single to emptyList()), CountMode.TOTAL, 3), empty())
    }

    @Test
    fun `zero count`() {
        assertThat(RoundPlanner.plan(pending(1L to single), mapOf(single to listOf(2)), CountMode.TOTAL, 0), empty())
    }

    // several decks

    private val a = 100L
    private val b = 200L
    private val c = 300L
    private val queues = mapOf(a to listOf(1L, 2L, 3L, 4L), b to listOf(11L, 12L, 13L), c to listOf(21L))

    @Test
    fun `per deck - each deck shows its count`() {
        val round = RoundPlanner.plan(emptyList(), queues, CountMode.PER_DECK, 2)
        assertThat(ids(round), contains(1L, 2L, 11L, 12L, 21L))
        assertThat(round.map { it.sourceDeckId }, contains(a, a, b, b, c))
    }

    @Test
    fun `per deck - carried over cards count against their own deck`() {
        val round = RoundPlanner.plan(pending(4L to a, 13L to b), queues, CountMode.PER_DECK, 2)
        assertThat(ids(round), contains(4L, 1L, 13L, 11L, 21L))
    }

    @Test
    fun `per deck - a deck full of carried over cards does not take more`() {
        val round = RoundPlanner.plan(pending(1L to a, 2L to a, 3L to a), queues, CountMode.PER_DECK, 2)
        assertThat(ids(round), contains(1L, 2L, 11L, 12L, 21L))
    }

    @Test
    fun `total - decks take turns`() {
        val round = RoundPlanner.plan(emptyList(), queues, CountMode.TOTAL, 5)
        assertThat(ids(round), contains(1L, 11L, 21L, 2L, 12L))
    }

    @Test
    fun `total - an exhausted deck leaves its share to the others`() {
        val round = RoundPlanner.plan(emptyList(), queues, CountMode.TOTAL, 7)
        assertThat(ids(round), contains(1L, 11L, 21L, 2L, 12L, 3L, 13L))
    }

    @Test
    fun `total - carried over cards come first and count against the total`() {
        val round = RoundPlanner.plan(pending(4L to a, 13L to b), queues, CountMode.TOTAL, 3)
        assertThat(ids(round), contains(4L, 13L, 1L))
    }

    @Test
    fun `a card found in two decks is shown once`() {
        val overlapping = mapOf(a to listOf(1L, 2L), b to listOf(1L, 2L, 3L))
        assertThat(ids(RoundPlanner.plan(emptyList(), overlapping, CountMode.PER_DECK, 2)), contains(1L, 2L, 3L))
        assertThat(ids(RoundPlanner.plan(emptyList(), overlapping, CountMode.TOTAL, 3)), contains(1L, 2L, 3L))
    }

    @Test
    fun `carried over cards of a deck which is no longer selected are dropped`() {
        val round = RoundPlanner.plan(pending(99L to 999L), mapOf(a to listOf(1L)), CountMode.TOTAL, 3)
        assertThat(ids(round), contains(1L))
    }

    @Test
    fun `round never exceeds the notification limit`() {
        val many = (1L..10L).associate { deck -> deck to (1L..10L).map { deck * 100 + it } }
        val round = RoundPlanner.plan(emptyList(), many, CountMode.PER_DECK, 10)
        assertThat(round, hasSize(NotifyStudyPrefs.MAX_NOTIFICATIONS))
    }

    // persistence

    @Test
    fun `pending list round trips`() {
        val cards = listOf(PendingCard(1, 0, 100), PendingCard(1_700_000_000_123, 7, 200, sourceDeckId = 42))
        val decoded = NotifyStudyPrefs.decodePending(NotifyStudyPrefs.encodePending(cards))
        assertThat(decoded, contains(*cards.toTypedArray()))
    }

    @Test
    fun `pending list of the first version is read with the current deck`() {
        val decoded = NotifyStudyPrefs.decodePending("""[{"cid":5,"reps":1,"shown":10}]""")
        assertThat(decoded.single().sourceDeckId, equalTo(NotifyStudyPrefs.CURRENT_DECK))
    }

    @Test
    fun `corrupt pending list is reset`() {
        assertThat(NotifyStudyPrefs.decodePending("not json"), empty())
    }

    @Test
    fun `deck list round trips without duplicates`() {
        assertThat(NotifyStudyPrefs.decodeDeckIds(NotifyStudyPrefs.encodeDeckIds(listOf(3, 1, 3))), contains(3L, 1L))
    }
}
