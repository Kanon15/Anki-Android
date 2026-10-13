// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId

/** A card of a round, and the selected deck it counts against */
data class PlannedCard(
    val cardId: CardId,
    val sourceDeckId: DeckId,
)

/**
 * Decides which cards are shown in a notification study round.
 *
 * Cards from earlier rounds which were not answered (skipped, dismissed, ignored, or skipped
 * because Do Not Disturb was active) are shown again first. The round is then topped up with
 * cards from the study queues, so the number of cards on screen never exceeds the configured
 * count: unanswered work is carried over, but never piles up.
 *
 * - [CountMode.PER_DECK]: each selected deck shows up to `count` cards. A carried over card counts
 *   against the deck it was drawn from.
 * - [CountMode.TOTAL]: up to `count` cards in total. New cards are taken from the selected decks
 *   in turn, so each deck gets a fair share.
 *
 * A card can be in several selected decks (e.g. a deck and its subdeck): it is only shown once.
 * Carried over cards of a deck which is no longer selected are dropped.
 */
object RoundPlanner {
    /**
     * @param stillPending unanswered cards from previous rounds, oldest first
     * @param queues the top of the study queue of each selected deck, in deck order
     * @param count cards per deck or in total, depending on [mode]
     * @param maxTotal hard limit of cards in a round
     */
    fun plan(
        stillPending: List<PlannedCard>,
        queues: Map<DeckId, List<CardId>>,
        mode: CountMode,
        count: Int,
        maxTotal: Int = NotifyStudyPrefs.MAX_NOTIFICATIONS,
    ): List<PlannedCard> {
        if (count <= 0 || maxTotal <= 0) return emptyList()
        val decks = queues.keys.toList()
        val pending = stillPending.filter { it.sourceDeckId in queues }
        val chosen = LinkedHashMap<CardId, PlannedCard>()

        fun add(card: PlannedCard): Boolean {
            if (chosen.size >= maxTotal || card.cardId in chosen) return false
            chosen[card.cardId] = card
            return true
        }

        when (mode) {
            CountMode.PER_DECK -> {
                for (deck in decks) {
                    var taken = 0
                    for (card in pending.filter { it.sourceDeckId == deck }) {
                        if (taken >= count) break
                        if (add(card)) taken++
                    }
                    for (cid in queues.getValue(deck)) {
                        if (taken >= count) break
                        if (add(PlannedCard(cid, deck))) taken++
                    }
                }
            }
            CountMode.TOTAL -> {
                val limit = minOf(count, maxTotal)
                for (card in pending) {
                    if (chosen.size >= limit) break
                    add(card)
                }
                // take one card from each deck in turn
                val iterators = decks.associateWith { queues.getValue(it).iterator() }
                var progress = true
                while (chosen.size < limit && progress) {
                    progress = false
                    for (deck in decks) {
                        if (chosen.size >= limit) break
                        val iterator = iterators.getValue(deck)
                        while (iterator.hasNext()) {
                            progress = true
                            if (add(PlannedCard(iterator.next(), deck))) break
                        }
                    }
                }
            }
        }
        return chosen.values.toList()
    }
}
