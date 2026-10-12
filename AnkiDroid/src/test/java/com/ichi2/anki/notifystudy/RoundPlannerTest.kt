// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.junit.Test

class RoundPlannerTest {
    @Test
    fun `fills the round from the queue`() {
        assertThat(RoundPlanner.plan(emptyList(), listOf(1, 2, 3, 4), limit = 3), contains(1L, 2L, 3L))
    }

    @Test
    fun `unanswered cards come first and are not duplicated`() {
        val round = RoundPlanner.plan(stillPending = listOf(3), queue = listOf(1, 2, 3, 4), limit = 3)
        assertThat(round, contains(3L, 1L, 2L))
    }

    @Test
    fun `unanswered cards never exceed the limit - work does not pile up`() {
        val round = RoundPlanner.plan(stillPending = listOf(10, 11, 12, 13), queue = listOf(1, 2), limit = 3)
        assertThat(round, contains(10L, 11L, 12L))
    }

    @Test
    fun `fewer cards than the limit`() {
        assertThat(RoundPlanner.plan(listOf(5), listOf(5), limit = 3), contains(5L))
    }

    @Test
    fun `empty queue and nothing pending`() {
        assertThat(RoundPlanner.plan(emptyList(), emptyList(), limit = 3), empty())
    }

    @Test
    fun `zero limit`() {
        assertThat(RoundPlanner.plan(listOf(1), listOf(2), limit = 0), empty())
    }

    @Test
    fun `pending list round trips`() {
        val cards = listOf(PendingCard(1, 0, 100), PendingCard(1_700_000_000_123, 7, 200))
        val decoded = NotifyStudyPrefs.decodePending(NotifyStudyPrefs.encodePending(cards))
        assertThat(decoded, contains(*cards.toTypedArray()))
    }

    @Test
    fun `corrupt pending list is reset`() {
        assertThat(NotifyStudyPrefs.decodePending("not json"), empty())
    }
}
