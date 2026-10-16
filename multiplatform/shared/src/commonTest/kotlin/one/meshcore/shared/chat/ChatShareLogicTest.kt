package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactShareToken
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatShareLogicTest {
    @Test
    fun appendSharedTextInsertsSpaceOnlyWhenNeeded() {
        assertEquals("token", ChatShareLogic.appendSharedText("", "token"))
        assertEquals("hi token", ChatShareLogic.appendSharedText("hi", "token"))
        assertEquals("hi token", ChatShareLogic.appendSharedText("hi ", "token"))
        assertEquals("hi\ntoken", ChatShareLogic.appendSharedText("hi\n", "token"))
    }

    @Test
    fun canShareMyInfoRequiresFullKeyAndNonBlankName() {
        val key = ByteArray(ChatShareLogic.PUBLIC_KEY_SIZE) { 0x11 }
        assertFalse(ChatShareLogic.canShareMyInfo(null, "Alice"))
        assertFalse(ChatShareLogic.canShareMyInfo(ByteArray(16), "Alice"))
        assertFalse(ChatShareLogic.canShareMyInfo(key, null))
        assertFalse(ChatShareLogic.canShareMyInfo(key, "  "))
        assertTrue(ChatShareLogic.canShareMyInfo(key, "Alice"))
    }

    @Test
    fun canShareLocationWhenAnySourceAvailable() {
        val phone = LatLng(1.0, 2.0)
        val node = LatLng(3.0, 4.0)
        assertFalse(ChatShareLogic.canShareLocation(null, null, locationAuthorized = false))
        assertTrue(ChatShareLogic.canShareLocation(phone, null, locationAuthorized = false))
        assertTrue(ChatShareLogic.canShareLocation(null, node, locationAuthorized = false))
        assertTrue(ChatShareLogic.canShareLocation(null, null, locationAuthorized = true))
    }

    @Test
    fun resolveShareCoordinatePrefersFreshThenPhoneThenNode() {
        val fresh = LatLng(1.0, 1.0)
        val phone = LatLng(2.0, 2.0)
        val node = LatLng(3.0, 3.0)
        assertEquals(fresh, ChatShareLogic.resolveShareCoordinate(fresh, phone, node))
        assertEquals(phone, ChatShareLogic.resolveShareCoordinate(null, phone, node))
        assertEquals(node, ChatShareLogic.resolveShareCoordinate(null, null, node))
        assertNull(ChatShareLogic.resolveShareCoordinate(null, null, null))
    }

    @Test
    fun formatShareCoordinateUsesSixFractionalDigits() {
        assertEquals(
            "37.774900, -122.419400",
            ChatShareLogic.formatShareCoordinate(37.7749, -122.4194),
        )
    }

    @Test
    fun shareableContactsDropBlankNames() {
        val a = sampleContact(name = "Alice")
        val blank = sampleContact(name = "  ", keyByte = 0x02)
        assertEquals(listOf(a), ChatShareLogic.shareableContacts(listOf(a, blank)))
    }

    @Test
    fun filterShareContactsMatchesNameNicknameAndKeyPrefix() {
        val alice = sampleContact(name = "Alice", nickname = "Ali", keyByte = 0xAB)
        val bob = sampleContact(name = "Bob", keyByte = 0xCD)
        assertEquals(listOf(alice, bob), ChatShareLogic.filterShareContacts(listOf(alice, bob), ""))
        assertEquals(listOf(alice), ChatShareLogic.filterShareContacts(listOf(alice, bob), "ali"))
        assertEquals(listOf(alice), ChatShareLogic.filterShareContacts(listOf(alice, bob), "AB"))
        val jose = sampleContact(name = "José", keyByte = 0x11)
        assertEquals(listOf(jose), ChatShareLogic.filterShareContacts(listOf(jose, bob), "jose"))
        assertEquals(emptyList(), ChatShareLogic.filterShareContacts(listOf(alice, bob), "zzz"))
    }

    @Test
    fun sharePickerTitleUsesRadioHashSize() {
        val key = ByteArray(32) { index ->
            if (index == 0) {
                0xAB.toByte()
            } else if (index == 1) {
                0xCD.toByte()
            } else {
                0
            }
        }
        val contact = sampleContact(name = "Alice").copy(publicKey = key)
        assertEquals("AB Alice", contact.sharePickerTitle(hashSize = 1))
        assertEquals("ABCD Alice", contact.sharePickerTitle(hashSize = 2))
    }

    @Test
    fun formatTokensMatchContactShareToken() {
        val contact = sampleContact(name = "Node>", typeRawValue = ContactType.REPEATER.raw, keyByte = 0x42)
        val token = ChatShareLogic.formatContactToken(contact)
        assertEquals(
            ContactShareToken.format(contact.publicKey, ContactType.REPEATER, "Node"),
            token,
        )
        val key = ByteArray(32) { 0x07 }
        assertEquals(
            ContactShareToken.format(key, ContactType.CHAT, "Me"),
            ChatShareLogic.formatMyInfoToken(key, "Me"),
        )
    }

    private fun sampleContact(
        name: String,
        keyByte: Int = 0x01,
        nickname: String? = null,
        typeRawValue: Int = ContactType.CHAT.raw,
    ): ContactDto = ContactDto(
        id = Uuid.normalized("00000000-0000-4000-8000-0000000000${byteArrayOf(keyByte.toByte()).toLowercaseHex()}"),
        radioID = Uuid.normalized("00000000-0000-4000-8000-000000000099"),
        publicKey = ByteArray(32) { keyByte.toByte() },
        name = name,
        typeRawValue = typeRawValue,
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
        nickname = nickname,
    )
}
