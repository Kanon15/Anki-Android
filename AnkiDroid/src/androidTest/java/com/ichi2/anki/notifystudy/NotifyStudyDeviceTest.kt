// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ichi2.anki.R
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.tests.InstrumentedTest
import com.ichi2.anki.testutil.grantPermissions
import com.ichi2.anki.testutil.notificationPermission
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import java.util.regex.Pattern

/**
 * End-to-end test of notification study on a real Android system: real notifications,
 * real system UI (notification shade, "Clear all", Do Not Disturb) and the practice pad.
 *
 * Screenshots are written to [SCREENSHOT_DIR] so CI can publish them.
 */
@RunWith(AndroidJUnit4::class)
class NotifyStudyDeviceTest : InstrumentedTest() {
    @get:Rule
    val permissionRule = grantPermissions(notificationPermission)

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val notificationManager get() = testContext.getSystemService<NotificationManager>()!!
    private val prefs get() = NotifyStudyPrefs(testContext)
    private var deckId: DeckId = 0

    @Before
    fun setUpNotifyStudy() {
        shell("cmd notification set_dnd off")
        runBlocking { NotifyStudyEngine.clear(testContext) }
        deckId = col.decks.id("NotifyStudyDeviceTest")
        for (i in 1..4) {
            val note = addNoteUsingBasicNoteType("Question-$i", "Answer-$i")
            note.cards(col).forEach { it.update { did = deckId } }
        }
        prefs.apply {
            enabled = true
            intervalMinutes = 60
            cardCount = 2
            countMode = CountMode.TOTAL
            deckIds = listOf(this@NotifyStudyDeviceTest.deckId)
            pending = emptyList()
        }
        File(SCREENSHOT_DIR).mkdirs()
    }

    @After
    fun tearDownNotifyStudy() {
        shell("cmd notification set_dnd off")
        shell("cmd statusbar collapse")
        notificationManager.cancel(CLEARABLE_TAG, 1)
        device.pressHome()
        runBlocking { NotifyStudyEngine.clear(testContext) }
        NotifyStudyAlarm.cancel(testContext)
        prefs.enabled = false
    }

    @Test
    fun answerFromTheNotificationShade() {
        assertThat(runBlocking { NotifyStudyEngine.runRound(testContext) }, equalTo(RoundResult.SHOWN))
        waitUntil("two cards shown") { ourNotifications().size == 2 }

        device.openNotification()
        assertThat("question shown", device.wait(Until.hasObject(By.textStartsWith("Question-")), TIMEOUT), equalTo(true))
        screenshot("01_round_in_shade")

        val revealButton = device.wait(Until.findObject(By.text("답 보기")), TIMEOUT)
        checkNotNull(revealButton) { "'show answer' action not found" }
        revealButton.click()
        val goodButton = device.wait(Until.findObject(By.textStartsWith("알맞음")), TIMEOUT)
        checkNotNull(goodButton) { "answer buttons not shown" }
        screenshot("02_answer_revealed")

        goodButton.click()
        waitUntil("answer recorded") { reviewCount() == 1 }
        waitUntil("answered notification removed") { ourNotifications().size == 1 }
        screenshot("03_after_answer")
    }

    @Test
    fun clearAllDoesNotRemoveTheCards() {
        runBlocking { NotifyStudyEngine.runRound(testContext) }
        waitUntil("two cards shown") { ourNotifications().size == 2 }
        val before = ourNotifications()
        before.forEach { assertThat(it.notification.flags and Notification.FLAG_NO_CLEAR, not(equalTo(0))) }

        // "Clear all" is only displayed when a clearable notification exists: post one, as other apps would
        NotifyStudyNotifications.ensureChannel(testContext)
        notificationManager.notify(
            CLEARABLE_TAG,
            1,
            NotificationCompat
                .Builder(testContext, NotifyStudyNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_star_notify)
                .setContentTitle("Clearable notification")
                .build(),
        )
        device.openNotification()
        device.wait(Until.hasObject(By.textStartsWith("Question-")), TIMEOUT)
        val clearAllPattern = Pattern.compile("(?i)clear all|모두 지우기")
        val clearAll =
            device.wait(Until.findObject(By.text(clearAllPattern)), TIMEOUT)
                ?: device.findObject(By.desc(clearAllPattern))
                ?: device.findObject(By.res("com.android.systemui", "clear_all"))
                ?: device.findObject(By.res("com.android.systemui", "dismiss_text"))
        if (clearAll == null) screenshot("failure_clear_all_not_found")
        checkNotNull(clearAll) { "'Clear all' button not found in the notification shade" }
        clearAll.click()
        waitUntil("clearable notification removed") { notificationManager.activeNotifications.none { it.tag == CLEARABLE_TAG } }
        screenshot("04_after_clear_all")
        assertThat("cards survive 'Clear all'", ourNotifications().map { it.tag }.sorted(), equalTo(before.map { it.tag }.sorted()))
    }

    @Test
    fun doNotDisturbSkipsTheRound() {
        shell("cmd notification set_dnd priority")
        waitUntil("DND active") { NotifyStudyEngine.isDoNotDisturbActive(testContext) }

        assertThat(runBlocking { NotifyStudyEngine.runRound(testContext) }, equalTo(RoundResult.DO_NOT_DISTURB))
        assertThat(ourNotifications(), hasSize(0))

        shell("cmd notification set_dnd off")
        waitUntil("DND inactive") { !NotifyStudyEngine.isDoNotDisturbActive(testContext) }
        assertThat(runBlocking { NotifyStudyEngine.runRound(testContext) }, equalTo(RoundResult.SHOWN))
        waitUntil("two cards shown") { ourNotifications().size == 2 }
        Thread.sleep(1000)
        assertThat("no extra cards after the skipped round", ourNotifications(), hasSize(2))
    }

    @Test
    fun skipFromTheNotificationShowsTheSameCardsNextRound() {
        runBlocking { NotifyStudyEngine.runRound(testContext) }
        waitUntil("two cards shown") { ourNotifications().size == 2 }
        val before = ourNotifications().map { it.tag }.sorted()

        device.openNotification()
        val skip = device.wait(Until.findObject(By.text("넘기기")), TIMEOUT)
        checkNotNull(skip) { "'skip' action not found" }
        skip.click()
        waitUntil("round hidden") { ourNotifications().isEmpty() }

        runBlocking { NotifyStudyEngine.runRound(testContext) }
        waitUntil("the same cards are shown again") { ourNotifications().map { it.tag }.sorted() == before }
    }

    @Test
    fun practicePadDrawAndAnswer() {
        runBlocking { NotifyStudyEngine.runRound(testContext) }
        val cardId = prefs.pending.first().cardId

        ActivityScenario.launch<PracticePadActivity>(PracticePadActivity.getIntent(testContext, cardId)).use {
            device.wait(Until.hasObject(By.res(testContext.packageName, "show_answer")), TIMEOUT)
            Thread.sleep(1500) // let the WebView render
            // draw on the whiteboard, in the middle of the screen
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 4, h / 2, w * 3 / 4, h * 6 / 10, 30)
            device.swipe(w / 4, h * 6 / 10, w * 3 / 4, h / 2, 30)
            screenshot("05_practice_pad_question")

            onView(withId(R.id.show_answer)).perform(click())
            Thread.sleep(1000)
            screenshot("06_practice_pad_answer")
            onView(withId(R.id.good)).perform(click())
        }
        waitUntil("answer recorded from the practice pad") { reviewCount(cardId) == 1 }
        waitUntil("notification removed") { ourNotifications().none { it.tag == NotifyStudyNotifications.tag(cardId) } }
    }

    @Test
    fun settingsScreen() {
        prefs.enabled = false
        ActivityScenario.launch(NotifyStudySettingsActivity::class.java).use {
            device.wait(Until.hasObject(By.res(testContext.packageName, "enabled")), TIMEOUT)
            onView(withId(R.id.enabled)).perform(click())
            waitUntil("enabled") { prefs.enabled }
            assertThat("next round scheduled", prefs.nextRoundAtMs, greaterThan(0L))
            Thread.sleep(500)
            screenshot("07_settings")
        }
    }

    private fun ourNotifications() =
        notificationManager.activeNotifications.filter { it.tag?.startsWith("com.ichi2.anki.notify_study:") == true }

    private fun reviewCount(cardId: CardId? = null): Int =
        if (cardId == null) {
            col.db.queryScalar("select count() from revlog r join cards c on r.cid = c.id where c.did = ?", deckId)
        } else {
            col.db.queryScalar("select count() from revlog where cid = ?", cardId)
        }

    private fun waitUntil(
        description: String,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(200)
        }
        screenshot("failure_${description.replace(' ', '_')}")
        throw AssertionError("timed out waiting for: $description")
    }

    private fun screenshot(name: String) {
        val file = File(SCREENSHOT_DIR, "$name.png")
        if (!device.takeScreenshot(file)) Timber.w("failed to save screenshot %s", file)
    }

    private fun shell(command: String) {
        device.executeShellCommand(command)
    }

    companion object {
        private const val TIMEOUT = 10_000L
        const val SCREENSHOT_DIR = "/sdcard/Download/notifystudy"
        private const val CLEARABLE_TAG = "notify_study_test_clearable"
    }
}
