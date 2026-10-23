// PortedFrom: MC1Tests/Views/Chats/MessageBubbleConfigurationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS")
class MessageBubbleConfigurationTest {
    private val radio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @OriginalCase("MessageBubbleConfigurationTests::directMessage factory disables incoming avatars()")
    @Test fun directMessageFactoryDisablesIncomingAvatars() {
        assertFalse(MessageBubbleConfiguration.directMessage.showsIncomingAvatars)
        assertTrue(MessageBubbleConfiguration.channel.showsIncomingAvatars)
    }

    @OriginalCase("MessageBubbleConfigurationTests::channel sender resolver marks short prefix match as fallback()")
    @Test fun channelSenderResolverMarksShortPrefixMatchAsFallback() {
        val older = contact(listOf(0xAA, 0x01), "Older", 100u)
        val newer = contact(listOf(0xAA, 0x02), "Newer", 200u)
        val result = bubbleSenderResolution(message(Bytes.of(0xAA)), listOf(older, newer), emptyMap(), resources)
        assertEquals("Newer", result.displayName)
        assertEquals(NodeNameMatchKind.FALLBACK, result.matchKind)
    }

    @OriginalCase("MessageBubbleConfigurationTests::channel sender resolver marks unique short prefix match as exact()")
    @Test fun channelSenderResolverMarksUniqueShortPrefixMatchAsExact() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha")
        val result = bubbleSenderResolution(message(Bytes.of(0xAA)), listOf(contact), emptyMap(), resources)
        assertEquals("Alpha", result.displayName)
        assertEquals(NodeNameMatchKind.EXACT, result.matchKind)
    }

    @OriginalCase("MessageBubbleConfigurationTests::buildNicknameLookup maps a unique name to its nickname()")
    @Test fun nicknameLookupMapsUniqueNameToNickname() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha", nickname = "Rico")
        assertEquals("Rico", MessageBubbleConfiguration.buildNicknameLookup(listOf(contact))["alpha"])
    }

    @OriginalCase("MessageBubbleConfigurationTests::buildNicknameLookup drops ambiguous colliding names()")
    @Test fun nicknameLookupDropsAmbiguousCollidingNames() {
        val first = contact(listOf(0xAA, 0x01), "Bob", nickname = "First")
        val second = contact(listOf(0xAA, 0x02), "bob", 200u, nickname = "Second")
        assertNull(MessageBubbleConfiguration.buildNicknameLookup(listOf(first, second))["bob"])
    }

    @OriginalCase("MessageBubbleConfigurationTests::buildNicknameLookup ignores contacts without a nickname()")
    @Test fun nicknameLookupIgnoresContactsWithoutNickname() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha")
        assertTrue(MessageBubbleConfiguration.buildNicknameLookup(listOf(contact)).isEmpty())
    }

    @OriginalCase("MessageBubbleConfigurationTests::channel sender resolver attaches unverified nickname on name match()")
    @Test fun channelSenderResolverAttachesUnverifiedNicknameOnNameMatch() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha", nickname = "Rico")
        val lookup = MessageBubbleConfiguration.buildNicknameLookup(listOf(contact))
        val result = bubbleSenderResolution(message(name = "Alpha"), listOf(contact), lookup, resources)
        assertEquals("Alpha", result.displayName)
        assertEquals(NodeNameMatchKind.EXACT, result.matchKind)
        assertEquals("Rico", result.unverifiedNickname)
    }

    @OriginalCase("MessageBubbleConfigurationTests::channel sender resolver has no nickname when name does not match()")
    @Test fun channelSenderResolverHasNoNicknameWhenNameDoesNotMatch() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha", nickname = "Rico")
        val lookup = MessageBubbleConfiguration.buildNicknameLookup(listOf(contact))
        val result = bubbleSenderResolution(message(name = "Charlie"), listOf(contact), lookup, resources)
        assertNull(result.unverifiedNickname)
    }

    @OriginalCase("MessageBubbleConfigurationTests::incomingAvatarIdentities maps a unique name without a nickname()")
    @Test fun incomingAvatarIdentitiesMapsUniqueNameWithoutNickname() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha")
        val identity = MessageBubbleConfiguration.incomingAvatarIdentities(listOf(contact))["alpha"]
        assertNotNull(identity)
        assertEquals(contact.id, identity?.matchedContactID)
        assertEquals("Alpha", identity?.name)
        assertNull(identity?.imageRevision)
    }

    @OriginalCase("MessageBubbleConfigurationTests::local avatar replace changes imageRevision when lastModified is unchanged()")
    @Test fun localAvatarReplacementChangesRevisionWithoutChangingLastModified() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha", image = Bytes(ByteArray(64) { 1 }))
        val replaced = contact.copy(avatarImageData = Bytes(ByteArray(64) { 2 }))
        assertEquals(contact.lastModified, replaced.lastModified)
        val first = MessageBubbleConfiguration.incomingAvatarIdentities(listOf(contact))["alpha"]
        val second = MessageBubbleConfiguration.incomingAvatarIdentities(listOf(replaced))["alpha"]
        assertNotNull(first?.imageRevision)
        assertNotNull(second?.imageRevision)
        assertNotEquals(first?.imageRevision, second?.imageRevision)
    }

    @OriginalCase("MessageBubbleConfigurationTests::incomingAvatarIdentities keeps a unique name when another name collides()")
    @Test fun incomingAvatarIdentitiesKeepsUniqueNameWhenAnotherNameCollides() {
        val alice = contact(listOf(0xAA, 0x01), "Alice")
        val bob1 = contact(listOf(0xAA, 0x02), "Bob")
        val bob2 = contact(listOf(0xAA, 0x03), "bob", 200u)
        val lookup = MessageBubbleConfiguration.incomingAvatarIdentities(listOf(alice, bob1, bob2))
        assertEquals(alice.id, lookup["alice"]?.matchedContactID)
        assertNull(lookup["bob"])
    }

    @OriginalCase("MessageBubbleConfigurationTests::incomingAvatarIdentities keys on contact name not nickname()")
    @Test fun incomingAvatarIdentitiesKeysOnContactNameNotNickname() {
        val contact = contact(listOf(0xAA, 0x01), "Alpha", nickname = "Rico")
        val lookup = MessageBubbleConfiguration.incomingAvatarIdentities(listOf(contact))
        assertEquals(contact.id, lookup["alpha"]?.matchedContactID)
        assertNull(lookup["rico"])
    }

    @Test fun nativeConversationConfigurationCoversDirectPublicAndPrivateChannels() {
        val direct = MessageBubbleConfiguration.forConversation(
            TimelineConversation.Direct(contact(listOf(1), "Direct")),
        )
        assertFalse(direct.showsIncomingAvatars)
        assertFalse(direct.showSenderName)
        for (index in listOf(0u.toUByte(), 1u.toUByte())) {
            val channel = MessageBubbleConfiguration.forConversation(
                TimelineConversation.Channel(ChannelDTO(radioId = radio, index = index, name = "Channel")),
            )
            assertTrue(channel.showsIncomingAvatars)
            assertTrue(channel.showSenderName)
        }
    }

    @Test fun supplementalNameTablesUseSwiftScalarLowercaseAndCanonicalCollisions() {
        val contacts = listOf(
            contact(listOf(1), "\u03a3\u039f\u03a3", nickname = "First"),
            contact(listOf(2), "\u03c3\u03bf\u03c3", nickname = "Second"),
            contact(listOf(3), "\u00c9", nickname = "Third"),
            contact(listOf(4), "E\u0301", nickname = "Fourth"),
            contact(listOf(5), "\u0130", nickname = "Dotted"),
        )
        assertEquals(mapOf("i\u0307" to "Dotted"), MessageBubbleConfiguration.buildNicknameLookup(contacts))
        assertEquals(setOf("i\u0307"), MessageBubbleConfiguration.incomingAvatarIdentities(contacts).keys)
    }

    @Test fun supplementalPhotoTableKeepsOnlyUniqueNonemptyContactImages() {
        val alice = contact(listOf(1), "Alice", image = Bytes.of(1))
        val bob1 = contact(listOf(2), "Bob", image = Bytes.of(2))
        val bob2 = contact(listOf(3), "bob", image = Bytes.of(3))
        val empty = contact(listOf(4), "Empty", image = Bytes.EMPTY)
        val table = ChannelAvatarTable.fromContacts(listOf(alice, bob1, bob2, empty))
        assertEquals(mapOf(alice.id to alice.avatarImageData), table.images)
        assertEquals(setOf("alice", "empty"), table.identities.keys)
        assertNull(table.identities["empty"]?.imageRevision)
    }

    private fun contact(
        prefix: List<Int>,
        name: String,
        advertised: UInt = 100u,
        nickname: String? = null,
        image: Bytes? = null,
    ) = ContactDTO(
        radioId = radio, publicKey = Bytes(ByteArray(32) { prefix.getOrNull(it)?.toByte() ?: 0.toByte() }),
        name = name, typeRawValue = 1u, outPathLength = 0u, lastAdvertTimestamp = advertised,
        lastHeardTimestamp = null, nickname = nickname, avatarImageData = image,
    )

    private fun message(prefix: Bytes? = null, name: String? = null) = MessageDTO(
        id = UUID.randomUUID(), radioId = radio, channelIndex = 0u, text = "Test",
        timestamp = 0u, createdAt = Instant.EPOCH, direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, senderKeyPrefix = prefix, senderNodeName = name,
        pathLength = 0u, isRead = true,
    )
}
