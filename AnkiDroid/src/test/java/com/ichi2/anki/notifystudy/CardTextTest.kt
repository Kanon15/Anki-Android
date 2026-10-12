// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.notifystudy

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardTextTest {
    @Test
    fun `answer part is after the answer separator`() {
        val html = "Front<hr id=answer>Back"
        assertThat(CardText.answerOnly(html), equalTo("Back"))
    }

    @Test
    fun `answer with quoted separator`() {
        val html = "<div>Q</div>\n\n<hr id=\"answer\">\n\n<b>A</b>"
        assertThat(CardText.toPlainText(CardText.answerOnly(html)), equalTo("A"))
    }

    @Test
    fun `answer without separator is the whole answer`() {
        assertThat(CardText.answerOnly("Only back"), equalTo("Only back"))
    }

    @Test
    fun `styles scripts and media are not shown`() {
        val html = "<style>.card{color:red}</style><script>alert(1)</script>Hello<br>World<img src=\"a.png\">[anki:play:q:0]"
        assertThat(
            CardText.toPlainText(html),
            equalTo("Hello\nWorld ${CardText.IMAGE_PLACEHOLDER} ${CardText.AUDIO_PLACEHOLDER}"),
        )
    }

    @Test
    fun `entities are decoded`() {
        assertThat(CardText.toPlainText("a &lt; b &amp;&nbsp;c"), equalTo("a < b & c"))
    }
}
