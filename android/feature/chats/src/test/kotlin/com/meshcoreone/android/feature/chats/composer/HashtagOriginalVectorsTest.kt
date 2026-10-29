// PortedFrom: MC1Services/Tests/MC1ServicesTests/Utilities/HashtagUtilitiesTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.feature.chats.channels.ChannelSheetsStateHolder
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashtagOriginalVectorsTest {
    @Test
    @OriginalCase("HashtagUtilitiesTests::hashtag pattern matches valid hashtags(text : String)")
    fun validPattern() {
        for (text in listOf("#general", "#General", "#test-channel", "#abc123", "#a")) {
            assertEquals(listOf(text), names(text))
        }
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::hashtag pattern rejects invalid hashtags(text : String)")
    fun invalidFullPattern() {
        for (text in listOf("#test_underscore", "#test.dot", "#", "#-bad", "#bad!", "#white space")) {
            assertFalse(Hashtags.extract(text, emptyList()).any { it.start == 0 && it.end == text.length })
        }
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags finds single hashtag()")
    fun single() {
        assertEquals(listOf("#general"), names("Join #general today"))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags accepts uppercase hashtags()")
    fun uppercase() {
        assertEquals(listOf("#General"), names("Join #General today"))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags finds multiple hashtags()")
    fun multiple() {
        assertEquals(listOf("#one", "#two"), names("Try #one and #two"))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags returns empty for no hashtags()")
    fun none() {
        assertTrue(names("No hashtags here").isEmpty())
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags excludes hashtags inside URLs()")
    fun excludeUrls() {
        assertEquals(listOf("#general"), names("See https://example.com#section and #general"))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags handles hashtag at end with punctuation()")
    fun punctuation() {
        assertEquals(listOf("#general"), names("Join #general."))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::extractHashtags handles adjacent hashtags()")
    fun adjacent() {
        assertEquals(listOf("#one", "#two"), names("#one#two"))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::isValidHashtagName accepts valid names()")
    fun validNames() {
        for (name in listOf("general", "General", "TEST", "test-channel", "abc123", "a")) {
            assertTrue(name, ChannelSheetsStateHolder.isValidHashtag(name))
        }
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::isValidHashtagName rejects invalid names()")
    fun invalidNames() {
        for (name in listOf("", "-bad", "test_underscore", "test.dot", "bad!")) {
            assertFalse(name, ChannelSheetsStateHolder.isValidHashtag(name))
        }
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::normalizeHashtagName lowercases and strips prefix()")
    fun normalizeName() {
        assertEquals("general", Hashtags.normalizeName("#General"))
        assertEquals("test", Hashtags.normalizeName("#TEST"))
        assertEquals("general", Hashtags.normalizeName("general"))
    }

    @Test
    @OriginalCase("HashtagUtilitiesTests::sanitizeHashtagNameInput lowercases and strips invalid characters()")
    fun sanitizeName() {
        assertEquals("general", ChannelSheetsStateHolder.sanitizeHashtag("General"))
        assertEquals("general", ChannelSheetsStateHolder.sanitizeHashtag("-General"))
        assertEquals("general", ChannelSheetsStateHolder.sanitizeHashtag("gen_eral"))
    }

    private fun names(text: String): List<String> =
        MessageLinkTokenizer.tokenize(text, emptyList(), MessageLinkTokenizer.StyleContext(isOutgoing = false))
            .tokens.filter { it.kind == LinkKind.HASHTAG }.map { text.substring(it.start, it.end) }
}
