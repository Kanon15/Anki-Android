// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.databinding.ActivityNotifyStudySettingsBinding
import com.ichi2.anki.libanki.DeckId
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings of notification study, opened from its own launcher icon.
 */
class NotifyStudySettingsActivity : AnkiActivity(R.layout.activity_notify_study_settings) {
    private val binding by viewBinding(ActivityNotifyStudySettingsBinding::bind)
    private val prefs by lazy { NotifyStudyPrefs(this) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showThemedToast(this, R.string.notify_study_permission_denied, false)
                binding.enabled.isChecked = false
            } else {
                setEnabled(true)
            }
        }

    private var decks: List<Pair<DeckId, String>> = emptyList()
    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        setViewBinding(binding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.enabled.isChecked = prefs.enabled
        binding.enabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && needsNotificationPermission()) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                setEnabled(isChecked)
            }
        }

        val intervals = NotifyStudyPrefs.INTERVAL_CHOICES
        binding.interval.setChoices(
            intervals.map { formatInterval(it) },
            intervals.indexOf(prefs.intervalMinutes).takeIf { it >= 0 } ?: intervals.indexOf(NotifyStudyPrefs.DEFAULT_INTERVAL_MINUTES),
        ) { index ->
            prefs.intervalMinutes = intervals[index]
            NotifyStudyAlarm.scheduleNext(this)
            updateStatus()
        }

        val counts = (NotifyStudyPrefs.MIN_CARDS..NotifyStudyPrefs.MAX_CARDS).toList()
        binding.count.setChoices(
            counts.map { getString(R.string.notify_study_count_value, it) },
            counts.indexOf(prefs.cardCount),
        ) { index -> prefs.cardCount = counts[index] }

        binding.countModeGroup.check(
            when (prefs.countMode) {
                CountMode.PER_DECK -> R.id.count_mode_per_deck
                CountMode.TOTAL -> R.id.count_mode_total
            },
        )
        binding.countModeGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.countMode = if (checkedId == R.id.count_mode_per_deck) CountMode.PER_DECK else CountMode.TOTAL
            updateCountLabel()
        }
        updateCountLabel()

        binding.deck.setOnClickListener { showDeckSelection() }

        binding.showNow.setOnClickListener { showNow() }
        binding.batteryOptimization.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.notificationSettings.setOnClickListener { openChannelSettings() }

        loadDecks()
        updateStatus()
        initialized = true
    }

    override fun onResume() {
        super.onResume()
        if (initialized) updateStatus()
    }

    private fun setEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        if (enabled) {
            NotifyStudyNotifications.ensureChannel(this)
            NotifyStudyAlarm.scheduleNext(this)
        } else {
            NotifyStudyAlarm.cancel(this)
            lifecycleScope.launch { NotifyStudyEngine.clear(this@NotifyStudySettingsActivity) }
        }
        updateStatus()
    }

    private fun showNow() {
        if (needsNotificationPermission()) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        lifecycleScope.launch {
            val message =
                when (NotifyStudyEngine.runRound(this@NotifyStudySettingsActivity, manual = true)) {
                    RoundResult.SHOWN -> R.string.notify_study_shown
                    RoundResult.NO_CARDS -> R.string.notify_study_no_cards
                    RoundResult.NOTIFICATIONS_BLOCKED -> R.string.notify_study_permission_denied
                    RoundResult.FAILED -> R.string.notify_study_collection_unavailable
                    RoundResult.DISABLED, RoundResult.DO_NOT_DISTURB -> null
                }
            message?.let { showThemedToast(this@NotifyStudySettingsActivity, it, true) }
        }
    }

    private fun loadDecks() {
        lifecycleScope.launch {
            decks =
                try {
                    withCol {
                        decks.allNamesAndIds(skipEmptyDefault = true, includeFiltered = true).map { it.id to it.name }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "notify study: failed to load decks")
                    binding.collectionWarning.isVisible = true
                    emptyList()
                }
            updateDeckSummary()
        }
    }

    /** Selects several decks. Selecting none uses the deck currently selected in AnkiDroid. */
    private fun showDeckSelection() {
        if (decks.isEmpty()) return
        val selected = prefs.deckIds.toMutableSet()
        val checked = decks.map { it.first in selected }.toBooleanArray()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.notify_study_deck)
            .setMultiChoiceItems(decks.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.deckIds = decks.filterIndexed { index, _ -> checked[index] }.map { it.first }
                updateDeckSummary()
            }.setNeutralButton(R.string.notify_study_deck_current) { _, _ ->
                prefs.deckIds = emptyList()
                updateDeckSummary()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateDeckSummary() {
        val names = decks.toMap()
        val selected = prefs.deckIds.mapNotNull { names[it] }
        binding.deck.text =
            when {
                selected.isEmpty() -> getString(R.string.notify_study_deck_current)
                selected.size == 1 -> selected.single()
                else -> getString(R.string.notify_study_deck_count, selected.size, selected.joinToString(", "))
            }
        binding.countModeGroup.isVisible = selected.size > 1
        updateCountLabel()
    }

    private fun updateCountLabel() {
        val multipleDecks = prefs.deckIds.size > 1
        binding.countLabel.setText(
            if (multipleDecks &&
                prefs.countMode == CountMode.PER_DECK
            ) {
                R.string.notify_study_count_per_deck
            } else {
                R.string.notify_study_count
            },
        )
    }

    private fun updateStatus() {
        val next = prefs.nextRoundAtMs
        binding.status.text =
            when {
                !prefs.enabled -> getString(R.string.notify_study_status_off)
                next > 0 ->
                    getString(
                        R.string.notify_study_status_next,
                        android.text.format.DateFormat
                            .getTimeFormat(this)
                            .format(next),
                    )
                else -> getString(R.string.notify_study_status_on)
            }
        binding.batteryOptimization.isVisible = !isIgnoringBatteryOptimizations()
    }

    private fun formatInterval(minutes: Int): String =
        when {
            minutes < 60 -> getString(R.string.notify_study_minutes, minutes)
            minutes % 60 == 0 -> getString(R.string.notify_study_hours, minutes / 60)
            else -> getString(R.string.notify_study_hours_minutes, minutes / 60, minutes % 60)
        }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) ?: true

    @SuppressLint("BatteryLife") // the user explicitly asks for it: timely notifications are the point of the feature
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
        } catch (e: Exception) {
            Timber.w(e, "notify study: battery optimization screen unavailable")
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openChannelSettings() {
        NotifyStudyNotifications.ensureChannel(this)
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, NotifyStudyNotifications.CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "notify study: notification settings unavailable")
        }
    }
}

/** Fills a spinner and listens for selections made by the user (not the initial selection) */
private fun Spinner.setChoices(
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    adapter =
        ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    setSelection(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)), false)
    onItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            private var current = selectedItemPosition

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (position == current) return
                current = position
                onSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
}
