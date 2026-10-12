// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * A card which is currently part of the notification study round.
 *
 * @param reps the card's review count when it was first shown. If the card's review count changes,
 * the card has been studied elsewhere (e.g. in the reviewer) and is no longer pending.
 * @param shownAtMs when the card was (re)posted as a notification; used to estimate time taken.
 */
data class PendingCard(
    val cardId: CardId,
    val reps: Int,
    val shownAtMs: Long,
)

/**
 * Settings and state of the notification study feature.
 * Stored in a separate preference file so it never interferes with the regular AnkiDroid settings.
 */
class NotifyStudyPrefs(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Minutes between two rounds */
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        set(value) = prefs.edit { putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)) }

    /** Maximum number of cards shown at the same time */
    var cardsPerRound: Int
        get() = prefs.getInt(KEY_COUNT, DEFAULT_CARDS_PER_ROUND).coerceIn(MIN_CARDS, MAX_CARDS)
        set(value) = prefs.edit { putInt(KEY_COUNT, value.coerceIn(MIN_CARDS, MAX_CARDS)) }

    /** Deck to study, or [CURRENT_DECK] to use the deck currently selected in AnkiDroid */
    var deckId: DeckId
        get() = prefs.getLong(KEY_DECK, CURRENT_DECK)
        set(value) = prefs.edit { putLong(KEY_DECK, value) }

    /** Time of the next scheduled round, 0 if none */
    var nextRoundAtMs: Long
        get() = prefs.getLong(KEY_NEXT_ROUND, 0)
        set(value) = prefs.edit { putLong(KEY_NEXT_ROUND, value) }

    var pending: List<PendingCard>
        get() = decodePending(prefs.getString(KEY_PENDING, null))
        set(value) = prefs.edit { putString(KEY_PENDING, encodePending(value)) }

    fun removePending(cardId: CardId) {
        pending = pending.filterNot { it.cardId == cardId }
    }

    companion object {
        private const val FILE_NAME = "notify_study"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_COUNT = "cards_per_round"
        private const val KEY_DECK = "deck_id"
        private const val KEY_NEXT_ROUND = "next_round_at"
        private const val KEY_PENDING = "pending_cards"

        const val CURRENT_DECK: DeckId = 0L
        const val DEFAULT_INTERVAL_MINUTES = 60
        const val MIN_INTERVAL_MINUTES = 10
        const val MAX_INTERVAL_MINUTES = 24 * 60
        const val DEFAULT_CARDS_PER_ROUND = 3
        const val MIN_CARDS = 1
        const val MAX_CARDS = 10

        val INTERVAL_CHOICES = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360)

        internal fun encodePending(cards: List<PendingCard>): String =
            JSONArray()
                .apply {
                    for (card in cards) {
                        put(
                            JSONObject()
                                .put("cid", card.cardId)
                                .put("reps", card.reps)
                                .put("shown", card.shownAtMs),
                        )
                    }
                }.toString()

        internal fun decodePending(json: String?): List<PendingCard> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    PendingCard(
                        cardId = obj.getLong("cid"),
                        reps = obj.getInt("reps"),
                        shownAtMs = obj.optLong("shown", 0),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "notify study: corrupt pending list, resetting")
                emptyList()
            }
        }
    }
}
