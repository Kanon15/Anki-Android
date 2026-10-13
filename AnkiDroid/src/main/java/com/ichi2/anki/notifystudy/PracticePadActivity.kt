// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.databinding.ActivityPracticePadBinding
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardFragment
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.reflect.jvm.jvmName

/**
 * Practice pad for a card shown by notification study: the card on top, a whiteboard
 * (pen, stylus, eraser, undo) below, and the answer buttons at the bottom.
 */
class PracticePadActivity : AnkiActivity(R.layout.activity_practice_pad) {
    private val binding by viewBinding(ActivityPracticePadBinding::bind)

    private var cardId: CardId = 0
    private var card: CardHtml? = null
    private var answerShown = false
    private val openedAtMs = TimeManager.time.intTimeMS()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        setViewBinding(binding)
        applyPadInsets()

        cardId = intent.getLongExtra(EXTRA_CARD_ID, 0)
        answerShown = savedInstanceState?.getBoolean(STATE_ANSWER_SHOWN) ?: false

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                add(R.id.whiteboard_container, WhiteboardFragment(), WhiteboardFragment::class.jvmName)
            }
        }
        supportFragmentManager.executePendingTransactions()
        // the host handles 'back': no 'press back again to exit' prompt
        (supportFragmentManager.findFragmentByTag(WhiteboardFragment::class.jvmName) as? WhiteboardFragment)
            ?.setDrawingMode(true)

        setupWebView(binding.cardView)
        binding.close.setOnClickListener { finish() }
        binding.showAnswer.setOnClickListener {
            answerShown = true
            render()
        }
        binding.skip.setOnClickListener {
            lifecycleScope.launch {
                NotifyStudyEngine.skipRound(this@PracticePadActivity)
                finish()
            }
        }
        binding.again.setOnClickListener { answer(Rating.AGAIN) }
        binding.hard.setOnClickListener { answer(Rating.HARD) }
        binding.good.setOnClickListener { answer(Rating.GOOD) }
        binding.easy.setOnClickListener { answer(Rating.EASY) }

        lifecycleScope.launch {
            card =
                try {
                    NotifyStudyEngine.cardHtml(this@PracticePadActivity, cardId)
                } catch (e: Exception) {
                    Timber.w(e, "notify study: failed to load card %d", cardId)
                    showThemedToast(this@PracticePadActivity, R.string.notify_study_card_missing, false)
                    NotifyStudyNotifications.cancel(this@PracticePadActivity, cardId)
                    finish()
                    return@launch
                }
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANSWER_SHOWN, answerShown)
    }

    private fun applyPadInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            // card templates frequently use JavaScript
            javaScriptEnabled = true
            // images and fonts are loaded from the collection media folder
            allowFileAccess = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    // like the reviewer: resizes images and renders MathJax (content stays hidden until then)
                    view.loadUrl("javascript:onPageFinished();")
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // the card template signals the reviewer through URLs ("signal:show_answer", ...),
                    // which the practice pad handles with its own buttons: only allow local pages
                    return request.url.scheme != "file"
                }
            }
    }

    private fun render() {
        val card = card ?: return
        binding.title.text = getString(R.string.notify_study_practice_title, card.deckName)
        binding.revealRow.isVisible = !answerShown
        binding.answerRow.isVisible = answerShown
        val page = if (answerShown) card.answerPage else card.questionPage
        binding.cardView.loadDataWithBaseURL(
            java.io
                .File(card.mediaDir)
                .toUri()
                .toString() + "/",
            page,
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun answer(rating: Rating) {
        setButtonsEnabled(false)
        lifecycleScope.launch {
            val taken = TimeManager.time.intTimeMS() - openedAtMs
            when (NotifyStudyEngine.answer(this@PracticePadActivity, cardId, rating, timeTakenMs = taken)) {
                AnswerResult.ANSWERED -> finish()
                AnswerResult.ALREADY_STUDIED -> {
                    showThemedToast(this@PracticePadActivity, R.string.notify_study_already_studied, false)
                    finish()
                }
                AnswerResult.FAILED -> {
                    showThemedToast(this@PracticePadActivity, R.string.notify_study_answer_failed, false)
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(binding.again, binding.hard, binding.good, binding.easy, binding.skip).forEach { it.isEnabled = enabled }
    }

    companion object {
        private const val EXTRA_CARD_ID = "card_id"
        private const val STATE_ANSWER_SHOWN = "answer_shown"

        fun getIntent(
            context: Context,
            cardId: CardId,
        ): Intent =
            Intent(context, PracticePadActivity::class.java).apply {
                putExtra(EXTRA_CARD_ID, cardId)
                // one PendingIntent per card
                data = "ankidroid-notify-study://practice/$cardId".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
