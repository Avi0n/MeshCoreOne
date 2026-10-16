package one.meshcore.shared.chat.text

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports the iOS `MentionUtilitiesTests` — the shared mention detection/extraction/self-mention contract. */
class MentionUtilitiesTest {
    // createMention

    @Test fun `createMention creates correct format`() {
        assertEquals("@[Alice]", MentionUtilities.createMention("Alice"))
    }

    @Test fun `createMention handles names with spaces`() {
        assertEquals("@[My Node]", MentionUtilities.createMention("My Node"))
    }

    @Test fun `createMention handles special characters`() {
        assertEquals("@[Node-123]", MentionUtilities.createMention("Node-123"))
    }

    @Test fun `createMention handles empty name`() {
        assertEquals("@[]", MentionUtilities.createMention(""))
    }

    // appendMention

    @Test fun `appendMention into empty draft yields just the mention`() {
        assertEquals("@[Alice] ", MentionUtilities.appendMention("Alice", ""))
    }

    @Test fun `appendMention preserves draft and adds a separating space`() {
        assertEquals("hello @[Alice] ", MentionUtilities.appendMention("Alice", "hello"))
    }

    @Test fun `appendMention does not double the space when draft ends in whitespace`() {
        assertEquals("hello @[Alice] ", MentionUtilities.appendMention("Alice", "hello "))
    }

    // insertMention — iOS ChatConversationView.insertMention / MentionInsertionTests

    @Test fun `insertMention replaces trailing @query with the mention format`() {
        assertEquals("hey @[Alice] ", MentionUtilities.insertMention("Alice", "hey @ali"))
    }

    @Test fun `insertMention handles query at start of text`() {
        assertEquals("@[Bob] ", MentionUtilities.insertMention("Bob", "@bob"))
    }

    @Test fun `insertMention preserves preceding completed mentions`() {
        assertEquals("Hello @[Alice] and @[John] ", MentionUtilities.insertMention("John", "Hello @[Alice] and @jo"))
    }

    @Test fun `insertMention uses the contact name not the typed query`() {
        assertEquals("@[Bob's Solar Node] ", MentionUtilities.insertMention("Bob's Solar Node", "@bob"))
    }

    @Test fun `insertMention keeps text after the active query`() {
        assertEquals("hey @[Alice]  on channel 3", MentionUtilities.insertMention("Alice", "hey @ali on channel 3"))
    }

    @Test fun `insertMention replaces the mention @ not a later mid-word @`() {
        assertEquals(
            "hi @[Alice]  mail me at foo@bar",
            MentionUtilities.insertMention("Alice", "hi @al mail me at foo@bar"),
        )
    }

    @Test fun `insertMention leaves the draft when there is no active query`() {
        assertEquals("hello @[Alice]", MentionUtilities.insertMention("Bob", "hello @[Alice]"))
    }

    // extractMentions

    @Test fun `extractMentions parses single mention`() {
        assertEquals(listOf("Alice"), MentionUtilities.extractMentions("@[Alice] hello!"))
    }

    @Test fun `extractMentions parses multiple mentions`() {
        assertEquals(listOf("Alice", "Bob"), MentionUtilities.extractMentions("@[Alice] and @[Bob] hello!"))
    }

    @Test fun `extractMentions returns empty for no mentions`() {
        assertTrue(MentionUtilities.extractMentions("Hello world!").isEmpty())
    }

    @Test fun `extractMentions handles names with spaces`() {
        assertEquals(listOf("My Node"), MentionUtilities.extractMentions("@[My Node] says hi"))
    }

    @Test fun `extractMentions handles adjacent mentions`() {
        assertEquals(listOf("Alice", "Bob"), MentionUtilities.extractMentions("@[Alice]@[Bob]"))
    }

    @Test fun `extractMentions ignores malformed patterns`() {
        assertTrue(MentionUtilities.extractMentions("@[Alice hello").isEmpty())
        assertTrue(MentionUtilities.extractMentions("@Alice] hello").isEmpty())
        assertTrue(MentionUtilities.extractMentions("@ hello").isEmpty())
    }

    @Test fun `extractMentions handles empty message`() {
        assertTrue(MentionUtilities.extractMentions("").isEmpty())
    }

    @Test fun `extractMentions handles Unicode names`() {
        assertEquals(listOf("日本語"), MentionUtilities.extractMentions("@[日本語] hello"))
    }

    // detectActiveMention

    @Test fun `detectActiveMention returns null for empty text`() {
        assertNull(MentionUtilities.detectActiveMention(""))
    }

    @Test fun `detectActiveMention returns null for text without at`() {
        assertNull(MentionUtilities.detectActiveMention("hello world"))
    }

    @Test fun `detectActiveMention returns empty string for at alone`() {
        assertEquals("", MentionUtilities.detectActiveMention("@"))
    }

    @Test fun `detectActiveMention returns query after at`() {
        assertEquals("jo", MentionUtilities.detectActiveMention("@jo"))
    }

    @Test fun `detectActiveMention works at start of message`() {
        assertEquals("alice", MentionUtilities.detectActiveMention("@alice"))
    }

    @Test fun `detectActiveMention works after space`() {
        assertEquals("bob", MentionUtilities.detectActiveMention("hey @bob"))
    }

    @Test fun `detectActiveMention returns null for at mid-word`() {
        assertNull(MentionUtilities.detectActiveMention("email@domain"))
    }

    @Test fun `detectActiveMention returns null when space follows at`() {
        assertNull(MentionUtilities.detectActiveMention("@ hello"))
    }

    @Test fun `detectActiveMention returns last active mention`() {
        assertEquals("bo", MentionUtilities.detectActiveMention("@[Alice] hey @bo"))
    }

    @Test fun `detectActiveMention returns null for completed mention`() {
        assertNull(MentionUtilities.detectActiveMention("@[Alice] hello"))
    }

    @Test fun `detectActiveMention handles Unicode`() {
        assertEquals("日本", MentionUtilities.detectActiveMention("@日本"))
    }

    @Test fun `detectActiveMention ignores email addresses`() {
        assertNull(MentionUtilities.detectActiveMention("contact me at test@example.com"))
    }

    @Test fun `detectActiveMention handles double at symbols`() {
        assertNull(MentionUtilities.detectActiveMention("@@alice"))
    }

    @Test fun `detectActiveMention returns null for unclosed bracket`() {
        assertNull(MentionUtilities.detectActiveMention("@[Alice"))
    }

    // filterContacts

    @Test fun `filterContacts matches by case-insensitive contains`() {
        val contacts = listOf(contact("Alice"), contact("Bob"), contact("Amanda"))
        val filtered = MentionUtilities.filterContacts(contacts, "a")
        assertEquals(2, filtered.size)
        assertTrue(filtered.map { it.name }.containsAll(listOf("Alice", "Amanda")))
    }

    @Test fun `filterContacts excludes repeaters`() {
        val contacts = listOf(contact("Alice"), contact("Repeater1", ContactType.REPEATER))
        val filtered = MentionUtilities.filterContacts(contacts, "")
        assertEquals(listOf("Alice"), filtered.map { it.name })
    }

    @Test fun `filterContacts excludes rooms`() {
        val contacts = listOf(contact("Alice"), contact("Room1", ContactType.ROOM))
        assertEquals(1, MentionUtilities.filterContacts(contacts, "").size)
    }

    @Test fun `filterContacts sorts alphabetically`() {
        val contacts = listOf(contact("Zoe"), contact("Alice"), contact("Bob"))
        assertEquals(listOf("Alice", "Bob", "Zoe"), MentionUtilities.filterContacts(contacts, "").map { it.name })
    }

    @Test fun `filterContacts returns empty for no matches`() {
        assertTrue(MentionUtilities.filterContacts(listOf(contact("Alice")), "xyz").isEmpty())
    }

    @Test fun `filterContacts handles empty input`() {
        assertTrue(MentionUtilities.filterContacts(emptyList(), "a").isEmpty())
    }

    @Test fun `filterContacts sorts by sender order when provided`() {
        val contacts = listOf(contact("Alice"), contact("Bob"), contact("Charlie"))
        val order = mapOf("Charlie" to 300u, "Alice" to 200u, "Bob" to 100u)
        val filtered = MentionUtilities.filterContacts(contacts, "", order).map { it.name }
        assertEquals(listOf("Charlie", "Alice", "Bob"), filtered)
    }

    @Test fun `filterContacts sender order partial match ordered first then alphabetical`() {
        val contacts = listOf(contact("Zoe"), contact("Alice"), contact("Bob"), contact("Dan"))
        val order = mapOf("Bob" to 500u, "Alice" to 100u)
        assertEquals(
            listOf("Bob", "Alice", "Dan", "Zoe"),
            MentionUtilities.filterContacts(contacts, "", order).map { it.name },
        )
    }

    // containsSelfMention

    @Test fun `containsSelfMention returns true for exact match`() {
        assertTrue(MentionUtilities.containsSelfMention("Hello @[Alice]!", "Alice"))
    }

    @Test fun `containsSelfMention is case insensitive`() {
        assertTrue(MentionUtilities.containsSelfMention("@[ALICE]", "alice"))
        assertTrue(MentionUtilities.containsSelfMention("@[alice]", "ALICE"))
        assertTrue(MentionUtilities.containsSelfMention("@[Alice]", "aLiCe"))
    }

    @Test fun `containsSelfMention returns false for different name`() {
        assertFalse(MentionUtilities.containsSelfMention("@[Bob] hello", "Alice"))
    }

    @Test fun `containsSelfMention handles multiple mentions`() {
        assertTrue(MentionUtilities.containsSelfMention("@[Bob] @[Alice]", "Alice"))
        assertTrue(MentionUtilities.containsSelfMention("@[Alice] @[Bob]", "Alice"))
    }

    @Test fun `containsSelfMention returns false for empty text`() {
        assertFalse(MentionUtilities.containsSelfMention("", "Alice"))
    }

    @Test fun `containsSelfMention returns false for empty selfName`() {
        assertFalse(MentionUtilities.containsSelfMention("@[Alice]", ""))
    }

    @Test fun `containsSelfMention returns false for partial match`() {
        assertFalse(MentionUtilities.containsSelfMention("@[Ali]", "Alice"))
    }

    // buildReplyText

    @Test fun `buildReplyText quotes a short message`() {
        assertEquals("@[Alice]\n>hi\n", MentionUtilities.buildReplyText("Alice", "hi"))
    }

    @Test fun `buildReplyText elides a long message and strips a leading mention`() {
        assertEquals("@[Alice]\n>0123456789..\n", MentionUtilities.buildReplyText("Alice", "@[Bob] 0123456789X"))
    }

    // sanitizeDisplayName

    @Test fun `sanitizeDisplayName strips bidi and zero-width characters`() {
        // U+202E right-to-left override and U+200B zero-width space (category Format) are stripped.
        assertEquals("Alice", MentionUtilities.sanitizeDisplayName("A\u202Eli\u200Bce"))
    }

    private fun contact(name: String, type: ContactType = ContactType.CHAT): ContactDto = ContactDto(
        id = Uuid("id-$name"),
        radioID = Uuid("radio"),
        publicKey = ByteArray(PUBLIC_KEY_LENGTH),
        name = name,
        typeRawValue = type.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private companion object {
        const val PUBLIC_KEY_LENGTH = 32
    }
}
