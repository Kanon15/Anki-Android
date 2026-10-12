// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import com.ichi2.anki.libanki.CardId

/**
 * Decides which cards are shown in a notification study round.
 *
 * Cards from earlier rounds which were not answered (skipped, dismissed, ignored, or skipped
 * because Do Not Disturb was active) are shown again first. The round is then topped up with
 * new cards from the study queue, so that the number of cards on screen never exceeds [limit]:
 * unanswered work is carried over, but never piles up.
 */
object RoundPlanner {
    /**
     * @param stillPending cards from previous rounds that have not been answered yet, oldest first
     * @param queue cards at the top of the study queue, in study order
     * @param limit maximum number of cards in the round
     * @return the cards of the round, in display order, without duplicates
     */
    fun plan(
        stillPending: List<CardId>,
        queue: List<CardId>,
        limit: Int,
    ): List<CardId> {
        if (limit <= 0) return emptyList()
        val result = LinkedHashSet<CardId>()
        for (cid in stillPending) {
            if (result.size >= limit) break
            result.add(cid)
        }
        for (cid in queue) {
            if (result.size >= limit) break
            result.add(cid)
        }
        return result.toList()
    }
}
