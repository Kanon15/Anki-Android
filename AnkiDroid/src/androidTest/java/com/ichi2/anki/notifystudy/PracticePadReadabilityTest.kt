// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ichi2.anki.R
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.anki.tests.InstrumentedTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The card shown on the practice pad must be readable: its text is measured on screen and must reach
 * the WCAG AA contrast ratio (4.5:1) against its background.
 *
 * Regression: in night mode, cards whose CSS sets a light night-mode text colour (relying on the
 * reviewer's dark background) were displayed light grey on white.
 */
@RunWith(AndroidJUnit4::class)
class PracticePadReadabilityTest : InstrumentedTest() {
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var originalTheme: AppTheme

    @Before
    fun saveTheme() {
        originalTheme = Prefs.appTheme
    }

    @After
    fun restoreTheme() {
        Prefs.appTheme = originalTheme
    }

    @Test
    fun nightModeCardWithNightModeTextColourIsReadable() {
        assertReadable(AppTheme.NIGHT, NIGHT_MODE_STYLED_FRONT, "night_nightmode_css")
    }

    @Test
    fun nightModeStockCardIsReadable() {
        assertReadable(AppTheme.NIGHT, STOCK_FRONT, "night_stock")
    }

    @Test
    fun dayModeStockCardIsReadable() {
        assertReadable(AppTheme.DAY, STOCK_FRONT, "day_stock")
    }

    @Test
    fun dayModeCardWithNightModeTextColourIsReadable() {
        assertReadable(AppTheme.DAY, NIGHT_MODE_STYLED_FRONT, "day_nightmode_css")
    }

    private fun assertReadable(
        theme: AppTheme,
        front: String,
        name: String,
    ) {
        Prefs.appTheme = theme
        val deckId = col.decks.id("PracticePadReadability")
        val note = addNoteUsingBasicNoteType(front, "Back")
        val cardId: CardId =
            note
                .cards(col)
                .single()
                .update { did = deckId }
                .id

        ActivityScenario.launch<PracticePadActivity>(PracticePadActivity.getIntent(testContext, cardId)).use { scenario ->
            device.wait(Until.hasObject(By.res(testContext.packageName, "show_answer")), 10_000)
            // let the WebView render
            Thread.sleep(2500)
            val bounds = Rect()
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.card_view)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            save(screenshot, "readability_$name")
            // stay away from the edges (scrollbars, divider)
            bounds.inset(8, 8)
            val contrast = textContrast(screenshot, bounds)
            Timber.i("practice pad contrast [%s]: %.2f", name, contrast)
            assertThat("text contrast of the card on the practice pad ($name)", contrast, greaterThanOrEqualTo(MIN_CONTRAST))
        }
    }

    private fun save(
        bitmap: Bitmap,
        name: String,
    ) {
        runCatching {
            File(NotifyStudyDeviceTest.SCREENSHOT_DIR).mkdirs()
            FileOutputStream(File(NotifyStudyDeviceTest.SCREENSHOT_DIR, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.onFailure { Timber.w(it, "failed to save screenshot") }
    }

    companion object {
        private const val MIN_CONTRAST = 4.5

        /** Large text, plain stock styling */
        private const val STOCK_FRONT = "<span style=\"font-size:56px\">가나다 ABC</span>"

        /**
         * Common in shared decks: a light text colour for night mode, relying on the reviewer
         * providing a dark background.
         */
        private const val NIGHT_MODE_STYLED_FRONT =
            "<style>.nightMode, .night_mode { color: #cccccc; }</style>" +
                "<span style=\"font-size:56px\">가나다 ABC</span>"

        /** The contrast between the background (most common colour) and the most contrasting pixel */
        fun textContrast(
            bitmap: Bitmap,
            area: Rect,
        ): Double {
            val counts = HashMap<Int, Int>()
            val pixels = ArrayList<Int>()
            val left = area.left.coerceIn(0, bitmap.width - 1)
            val right = area.right.coerceIn(left + 1, bitmap.width)
            val top = area.top.coerceIn(0, bitmap.height - 1)
            val bottom = area.bottom.coerceIn(top + 1, bitmap.height)
            for (y in top until bottom step 2) {
                for (x in left until right step 2) {
                    val pixel = bitmap.getPixel(x, y) or 0xFF000000.toInt()
                    pixels.add(pixel)
                    counts[pixel] = (counts[pixel] ?: 0) + 1
                }
            }
            val background = counts.maxByOrNull { it.value }!!.key
            val backgroundLuminance = luminance(background)
            return pixels.maxOf { ratio(luminance(it), backgroundLuminance) }
        }

        private fun ratio(
            a: Double,
            b: Double,
        ) = (max(a, b) + 0.05) / (min(a, b) + 0.05)

        private fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val c = value / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
        }
    }
}
