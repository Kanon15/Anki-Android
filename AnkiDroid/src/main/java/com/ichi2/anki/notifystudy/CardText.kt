// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import androidx.core.text.HtmlCompat

/**
 * Converts rendered card HTML to plain text suitable for a notification.
 */
object CardText {
    private val styleRegex = Regex("(?is)<style[^>]*>.*?</style>")
    private val scriptRegex = Regex("(?is)<script[^>]*>.*?</script>")
    private val avTagRegex = Regex("\\[anki:play:[^\\]]*]")
    private val soundTagRegex = Regex("\\[sound:[^\\]]*]")
    private val answerSeparatorRegex = Regex("(?i)<hr[^>]*id\\s*=\\s*[\"']?answer[\"']?[^>]*>")
    private val imageRegex = Regex("(?i)<img[^>]*>")
    private val blockRegex = Regex("(?i)<br\\s*/?>|</div>|</p>|</li>|</tr>|</h[1-6]>")
    private val blankLinesRegex = Regex("\\n[ \\t\\u00A0]*\\n(\\s*\\n)+")

    const val IMAGE_PLACEHOLDER = "[그림]"
    const val AUDIO_PLACEHOLDER = "[소리]"

    /**
     * Returns the part of the rendered answer after the `<hr id=answer>` separator,
     * or the whole answer if the template has no separator.
     */
    fun answerOnly(answerHtml: String): String {
        val match = answerSeparatorRegex.findAll(answerHtml).lastOrNull() ?: return answerHtml
        return answerHtml.substring(match.range.last + 1)
    }

    /** Removes markup which should never be displayed, and replaces media with placeholders */
    fun stripNonText(html: String): String =
        html
            .replace(styleRegex, "")
            .replace(scriptRegex, "")
            .replace(avTagRegex, " $AUDIO_PLACEHOLDER ")
            .replace(soundTagRegex, " $AUDIO_PLACEHOLDER ")
            .replace(imageRegex, " $IMAGE_PLACEHOLDER ")
            .replace(blockRegex) { it.value + "\n" }

    /** @return the visible text of [html], with collapsed blank lines */
    fun toPlainText(html: String): String {
        val text =
            HtmlCompat
                .fromHtml(stripNonText(html), HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .replace('\uFFFC', ' ')
                .replace('\u00A0', ' ')
        return text
            .replace(blankLinesRegex, "\n\n")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }
}
