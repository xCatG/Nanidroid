package com.cattailsw.nanidroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cattailsw.nanidroid.runtime.DialogueToken

class BalloonLinksTest {
    @Test fun findsLinksAdjacentToJapaneseTextWithoutIncludingTheNextSentence() {
        val text = "詳しくはhttps://example.org/道。次へmailto:hello@example.org！続き"
        val links = BalloonLinks.find(text)
        assertEquals(listOf("https://example.org/道", "mailto:hello@example.org"), links.map { it.url })
        assertEquals(links.map { it.url }, links.map { text.substring(it.start, it.end) })
    }

    @Test fun JapaneseBoundaryDoesNotAdmitMalformedOrLatinPrefixedLinks() {
        assertTrue(BalloonLinks.find("案内https://。続き").isEmpty())
        assertTrue(BalloonLinks.find("prefixhttps://example.org _mailto:hello@example.org").isEmpty())
    }

    @Test fun JapaneseCommaSeparatesLinkFromFollowingText() {
        assertEquals(listOf("https://example.org"),
            BalloonLinks.find("案内https://example.org、続き").map { it.url })
    }

    @Test fun prolongedSoundMarkBeforeSchemeIsJapaneseAdjacentText() {
        assertEquals(listOf("https://example.org"),
            BalloonLinks.find("メニューhttps://example.org。").map { it.url })
    }

    @Test fun isolatedProlongedSoundMarkDoesNotHideLatinPrefix() {
        assertTrue(BalloonLinks.find("prefixーhttps://example.org _ーhttps://example.org").isEmpty())
    }

    @Test fun repeatedProlongedSoundMarksAfterJapaneseTextAllowLink() {
        assertEquals(listOf("https://example.org", "https://example.net"),
            BalloonLinks.find("メニューーhttps://example.org すごーーhttps://example.net")
                .map { it.url })
    }

    @Test fun repeatedProlongedSoundMarksDoNotHideLatinOrUnderscorePrefix() {
        assertTrue(BalloonLinks.find("prefixーーhttps://example.org _ーーhttps://example.org").isEmpty())
    }

    @Test fun findsOnlyExplicitAllowedLinksAndTrimsSentencePunctuation() {
        val text = "案内 https://example.org/道。 mailto:hello@example.org, file:///tmp/a content://x intent://x javascript:alert(1)"
        val links = BalloonLinks.find(text)
        assertEquals(listOf("https://example.org/道", "mailto:hello@example.org"), links.map { it.url })
        assertEquals("https://example.org/道", text.substring(links[0].start, links[0].end))
        assertEquals("mailto:hello@example.org", text.substring(links[1].start, links[1].end))
    }

    @Test fun acceptsHttpAndHttpsButRejectsUnsupportedOrMalformedTargetsAtTapBoundary() {
        assertTrue(BalloonLinks.isAllowed("http://example.org/a"))
        assertTrue(BalloonLinks.isAllowed("https://example.org/a"))
        assertTrue(BalloonLinks.isAllowed("mailto:person@example.org"))
        listOf("file:///tmp/a", "content://x", "intent://x", "javascript:alert(1)",
            "https://", "https://example.org\nX", "mailto:").forEach {
            assertEquals(it, false, BalloonLinks.isAllowed(it))
        }
    }

    @Test fun staleDialogueNeverLaunchesAndMissingHandlerReturnsReadableError() {
        val token = DialogueToken(1, 2)
        val launched = mutableListOf<String>()
        assertEquals(null, BalloonLinks.open(token, "https://example.org", { false }) { launched += it })
        assertTrue(launched.isEmpty())
        assertEquals("No app can open this link.", BalloonLinks.open(token,
            "https://example.org", { true }) { throw IllegalStateException("no handler") })
        assertEquals(null, BalloonLinks.open(token, "mailto:a@example.org", { true }) { launched += it })
        assertEquals(listOf("mailto:a@example.org"), launched)
    }
}
