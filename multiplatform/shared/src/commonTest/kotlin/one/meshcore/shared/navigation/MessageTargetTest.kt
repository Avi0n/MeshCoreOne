package one.meshcore.shared.navigation

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTargetTest {
    @Test
    fun compositeIdRoundTrips() {
        val keyHex = ByteArray(PUBLIC_KEY_BYTES) { 0xAB.toByte() }.let { bytes ->
            MessageTarget(contact(ContactSeed(publicKey = bytes))).id
        }
        val parsed = parseCompositeId(keyHex)
        assertEquals(RADIO, parsed?.first)
        assertEquals(MessageTargetKind.CONTACT, parsed?.second)
    }

    @Test
    fun malformedCompositeIdFailsSafe() {
        val bad = listOf(
            "",
            "not-a-uuid/contact/abc",
            "11111111-1111-1111-1111-111111111111",
            "11111111-1111-1111-1111-111111111111/contact",
            "11111111-1111-1111-1111-111111111111/contact/",
            "11111111-1111-1111-1111-111111111111/bogus/abc",
        )
        bad.forEach { id -> assertNull(parseCompositeId(id), id) }
    }

    @Test
    fun contactIdDerivesFromPublicKeyNotRowId() {
        val publicKey = ByteArray(PUBLIC_KEY_BYTES) { 0xCD.toByte() }
        val first = MessageTarget(contact(ContactSeed(id = Uuid.normalized(ROW_A), publicKey = publicKey)))
        val second = MessageTarget(contact(ContactSeed(id = Uuid.normalized(ROW_B), publicKey = publicKey)))
        assertEquals(first.id, second.id)
        assertEquals(MessageTargetKind.CONTACT, first.kind)
        assertEquals(
            formatCompositeId(RADIO, MessageTargetKind.CONTACT, publicKey.toLowercaseHex()),
            first.id,
        )
    }

    @Test
    fun contactIdIsRadioScoped() {
        val publicKey = ByteArray(PUBLIC_KEY_BYTES) { 0xCD.toByte() }
        val here = MessageTarget(contact(ContactSeed(radio = RADIO, publicKey = publicKey)))
        val elsewhere = MessageTarget(contact(ContactSeed(radio = OTHER, publicKey = publicKey)))
        assertNotEquals(here.id, elsewhere.id)
    }

    @Test
    fun channelIdDerivesFromSecretDigestNotRawSecretOrIndex() {
        val secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x5A }
        val first = MessageTarget(channel(id = Uuid.normalized(ROW_A), index = 0, secret = secret))
        val second = MessageTarget(channel(id = Uuid.normalized(ROW_B), index = 7, secret = secret))
        assertEquals(first.id, second.id)
        assertEquals(MessageTargetKind.CHANNEL, first.kind)
        assertFalse(first.id.contains(secret.toLowercaseHex()))
        val parsed = parseCompositeId(first.id)
        val digest = channelSecretDigestHex(RADIO, secret)
        assertEquals(digest, parsed?.third)
        assertNotEquals(secret.toLowercaseHex(), parsed?.third)
    }

    @Test
    fun channelDigestIsRadioScopedAndFixedWidth() {
        val secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x5A }
        val here = channelSecretDigestHex(RADIO, secret)
        val elsewhere = channelSecretDigestHex(OTHER, secret)
        assertNotEquals(here, elsewhere)
        assertEquals(DIGEST_HEX_LENGTH, here.length)
    }

    @Test
    fun kindRawValuesArePinned() {
        assertEquals("contact", MessageTargetKind.CONTACT.raw)
        assertEquals("channel", MessageTargetKind.CHANNEL.raw)
        val contactId = formatCompositeId(RADIO, MessageTargetKind.CONTACT, "ab")
        val channelId = formatCompositeId(RADIO, MessageTargetKind.CHANNEL, "ab")
        assertTrue(contactId.contains("/contact/"))
        assertTrue(channelId.contains("/channel/"))
    }

    @Test
    fun pickerListExcludesRepeaterAndRoomAndDuplicateChannels() {
        val contacts = listOf(
            contact(ContactSeed(name = "Person", type = ContactType.CHAT.raw, keyByte = 0x01)),
            contact(ContactSeed(name = "Repeater", type = ContactType.REPEATER.raw, keyByte = 0x02)),
            contact(ContactSeed(name = "Room", type = ContactType.ROOM.raw, keyByte = 0x03)),
        )
        val shared = ByteArray(CHANNEL_SECRET_BYTES) { 0x0B }
        val channels = listOf(
            channel(index = 0, name = "DupA", secret = shared),
            channel(index = 1, name = "DupB", secret = shared),
            channel(index = 2, name = "Unique", secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0C }),
        )
        val targets = buildMessageTargets(chatContacts(contacts), channels)
        assertEquals(listOf("Person"), targets.filter { it.kind == MessageTargetKind.CONTACT }.map { it.displayName })
        assertEquals(listOf("Unique"), targets.filter { it.kind == MessageTargetKind.CHANNEL }.map { it.displayName })
    }

    @Test
    fun channelResolveFailsSafeOnDuplicateOrMissingDigest() {
        val secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x09 }
        val channels = listOf(
            channel(index = 0, name = "Dup0", secret = secret),
            channel(index = 1, name = "Dup1", secret = secret),
        )
        val id = MessageTarget(channels[0]).id
        assertTrue(resolveUniqueChannels(listOf(id), channels).isEmpty())
        assertTrue(resolveMessageTargets(listOf(id), emptyList(), channels).isEmpty())
        val unknown = channelSecretDigestHex(RADIO, ByteArray(CHANNEL_SECRET_BYTES) { 0xFE.toByte() })
        val unknownId = formatCompositeId(RADIO, MessageTargetKind.CHANNEL, unknown)
        assertTrue(resolveUniqueChannels(listOf(unknownId), channels).isEmpty())
    }

    @Test
    fun matchMessageTargetsFiltersByDisplayName() {
        val targets = buildMessageTargets(
            contacts = listOf(contact(ContactSeed(name = "Alice")), contact(ContactSeed(name = "Bob", keyByte = 0x22))),
            channels = listOf(channel(name = "Ops", secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0A })),
        )
        assertEquals(listOf("Alice"), matchMessageTargets("ali", targets).map { it.displayName })
        assertEquals(targets.map { it.displayName }, matchMessageTargets("  ", targets).map { it.displayName })
    }

    @Test
    fun resolveContactTargetAcceptsNameUuidAndComposite() {
        val alice = contact(ContactSeed(name = "Alice"))
        val bob = contact(ContactSeed(id = Uuid.normalized(ROW_B), name = "Bob", keyByte = 0x22))
        val contacts = listOf(alice, bob)
        assertEquals(alice.id, resolveContactTarget("Alice", contacts)?.id)
        assertEquals(alice.id, resolveContactTarget(alice.id.value, contacts)?.id)
        assertEquals(alice.id, resolveContactTarget(MessageTarget(alice).id, contacts)?.id)
        assertNull(
            resolveContactTarget(
                "Repeater",
                contacts + contact(ContactSeed(name = "Repeater", type = ContactType.REPEATER.raw, keyByte = 0x33)),
            ),
        )
        assertNull(resolveContactTarget("nobody", contacts))
    }

    @Test
    fun resolveContactTargetFailsSafeOnADuplicateName() {
        val contacts = listOf(
            contact(ContactSeed(name = "Alice")),
            contact(ContactSeed(id = Uuid.normalized(ROW_B), name = "Alice", keyByte = 0x22)),
        )
        assertNull(resolveContactTarget("Alice", contacts))
    }

    @Test
    fun resolveChannelTargetAcceptsNameUuidAndComposite() {
        val ops = channel(name = "Ops", secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0A })
        val other = channel(
            id = Uuid.normalized(ROW_B),
            name = "Other",
            secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0B },
        )
        val channels = listOf(ops, other)
        assertNull(resolveChannelTarget("  ", channels))
        assertEquals(ops.id, resolveChannelTarget("Ops", channels)?.id)
        assertEquals(ops.id, resolveChannelTarget(ops.id.value, channels)?.id)
        assertEquals(ops.id, resolveChannelTarget(MessageTarget(ops).id, channels)?.id)
        assertNull(resolveChannelTarget("nobody", channels))
        assertNull(resolveChannelTarget(MessageTarget(contact()).id, channels))
    }

    @Test
    fun resolveChannelTargetFailsSafeOnADuplicateName() {
        val channels = listOf(
            channel(name = "Ops", secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0A }),
            channel(
                id = Uuid.normalized(ROW_B),
                name = "Ops",
                secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0B },
            ),
        )
        assertNull(resolveChannelTarget("Ops", channels))
    }

    @Test
    fun resolveContactTargetRejectsNonChatContactsForEveryTokenForm() {
        for (type in listOf(ContactType.REPEATER, ContactType.ROOM)) {
            val nonChat = contact(ContactSeed(name = type.name, type = type.raw))
            val contacts = listOf(nonChat)
            assertNull(resolveContactTarget(nonChat.name, contacts))
            assertNull(resolveContactTarget(nonChat.id.value, contacts))
            assertNull(resolveContactTarget(MessageTarget(nonChat).id, contacts))
        }
    }

    @Test
    fun sendMessageShortcutSpecsUseRowIdsAndCapTheList() {
        val contacts = listOf(contact(ContactSeed(name = "Alice")))
        val channels = listOf(channel(name = "Ops", secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x0A }))
        val specs = sendMessageShortcutSpecs(contacts, channels)
        assertEquals(listOf("Alice", "Ops"), specs.map { it.displayName })
        assertEquals(contacts[0].id.value, specs[0].rowId)
        assertTrue(specs[0].url.contains("kind=contact"))
        assertTrue(specs[0].url.contains("name=Alice"))
        assertEquals(1, sendMessageShortcutSpecs(contacts, channels, maxCount = 1).size)
    }

    private data class ContactSeed(
        val id: Uuid = Uuid.normalized(ROW_A),
        val radio: Uuid = RADIO,
        val name: String = "Alice",
        val type: Int = ContactType.CHAT.raw,
        val publicKey: ByteArray? = null,
        val keyByte: Int = 0x11,
    )

    private fun contact(seed: ContactSeed = ContactSeed()): ContactDto = ContactDto(
        id = seed.id,
        radioID = seed.radio,
        publicKey = seed.publicKey ?: ByteArray(PUBLIC_KEY_BYTES) { seed.keyByte.toByte() },
        name = seed.name,
        typeRawValue = seed.type,
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

    private fun channel(
        id: Uuid = Uuid.normalized(ROW_A),
        index: Int = 0,
        name: String = "Ops",
        secret: ByteArray,
    ): ChannelDto = ChannelDto(
        id = id,
        radioID = RADIO,
        index = index,
        name = name,
        secret = secret,
        isEnabled = true,
        unreadCount = 0,
    )

    private companion object {
        val RADIO = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        val OTHER = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        const val ROW_A = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        const val ROW_B = "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"
        const val PUBLIC_KEY_BYTES = 32
        const val CHANNEL_SECRET_BYTES = 16
        const val DIGEST_HEX_LENGTH = 32
    }
}
