package one.meshcore.shared.chat.text

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pure mention- and hashtag-tap resolution seams. */
class ChatLinkTapTest {
    @Test fun `a single contact match navigates directly`() {
        val alice = contact("Alice")
        val result = MentionTapEvaluator.evaluate("alice", listOf(alice, contact("Bob")), connectedDeviceName = null)
        assertEquals(MentionTapResult.NavigateToContact(alice), result)
    }

    @Test fun `an unknown name routes to an empty picker`() {
        val result = MentionTapEvaluator.evaluate("Nobody", listOf(contact("Alice")), connectedDeviceName = null)
        assertEquals(MentionTapResult.Picker(name = "Nobody", matches = emptyList(), isSelfMention = false), result)
    }

    @Test fun `an ambiguous name routes to a picker with the candidates`() {
        val contacts = listOf(contact("Alice"), contact("Alice"))
        val result = MentionTapEvaluator.evaluate("alice", contacts, connectedDeviceName = null)
        val picker = assertIs<MentionTapResult.Picker>(result)
        assertEquals("alice", picker.name)
        assertEquals(2, picker.matches.size)
        assertTrue(!picker.isSelfMention)
    }

    @Test fun `the local node name routes to the self picker`() {
        val result = MentionTapEvaluator.evaluate("MyNode", listOf(contact("Alice")), connectedDeviceName = "mynode")
        assertEquals(MentionTapResult.Picker(name = "MyNode", matches = emptyList(), isSelfMention = true), result)
    }

    @Test fun `the picker title uses the sanitized name`() {
        val result = MentionTapEvaluator.evaluate(
            "\u202Egnitset",
            listOf(contact("Alice")),
            connectedDeviceName = null,
        )
        val picker = assertIs<MentionTapResult.Picker>(result)
        assertEquals("gnitset", picker.name)
    }

    @Test fun `a hashtag with no radio stages the join`() {
        assertEquals(
            HashtagTapResult.StageJoin("#general"),
            HashtagTapResolver.resolve("General", hasRadio = false, existingChannels = emptyList()),
        )
    }

    @Test fun `a hashtag matching an existing channel opens it`() {
        val channels = listOf(HashtagChannelRef(id = "chan-1", name = "#general"))
        assertEquals(
            HashtagTapResult.OpenChannel("chan-1"),
            HashtagTapResolver.resolve("general", hasRadio = true, existingChannels = channels),
        )
    }

    @Test fun `a hashtag with no matching channel stages the join`() {
        assertEquals(
            HashtagTapResult.StageJoin("#weather"),
            HashtagTapResolver.resolve("weather", hasRadio = true, existingChannels = emptyList()),
        )
    }

    @Test fun `an invalid hashtag name is rejected`() {
        val result = HashtagTapResolver.resolve("bad name", hasRadio = true, existingChannels = emptyList())
        assertEquals(HashtagTapResult.Invalid, result)
    }

    private fun contact(name: String): ContactDto = ContactDto(
        id = Uuid("id-$name-${counter++}"),
        radioID = Uuid("radio"),
        publicKey = ByteArray(KEY_BYTES),
        name = name,
        typeRawValue = ContactType.CHAT.raw,
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

    private var counter = 0

    private companion object {
        const val KEY_BYTES = 32
    }
}
