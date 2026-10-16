package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format coverage for the `Contact.avatarImageData` backup field: base64 encoding, key omission
 * when null, legacy-decode tolerance, and byte-preserving round-trip. A matching Swift fixture-vector
 * regen under `fixtures/backup/` is still pending.
 */
class ContactDtoAvatarTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun avatarEncodesAsBase64String() {
        val avatar = byteArrayOf(0x00, 0x11, 0x22.toByte(), 0x33)
        val json = backupJson.encodeToString(ContactDto.serializer(), sampleContact(avatar))
        assertContains(json, "\"avatarImageData\":\"${Base64.encode(avatar)}\"")
    }

    @Test
    fun nullAvatarOmitsKey() {
        val json = backupJson.encodeToString(ContactDto.serializer(), sampleContact(null))
        assertFalse(json.contains("avatarImageData"))
    }

    @Test
    fun legacyEnvelopeWithoutAvatarDecodesToNull() {
        val json = backupJson.encodeToString(ContactDto.serializer(), sampleContact(null))
        val decoded = backupJson.decodeFromString(ContactDto.serializer(), json)
        assertNull(decoded.avatarImageData)
    }

    @Test
    fun avatarSurvivesRoundTrip() {
        val avatar = ByteArray(64) { it.toByte() }
        val json = backupJson.encodeToString(ContactDto.serializer(), sampleContact(avatar))
        val decoded = backupJson.decodeFromString(ContactDto.serializer(), json)
        assertTrue(decoded.avatarImageData!!.contentEquals(avatar))
    }

    private fun sampleContact(avatar: ByteArray?): ContactDto = ContactDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { 0x11 },
        name = "Alice",
        typeRawValue = 1,
        flags = 0,
        outPathLength = 255,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
        avatarImageData = avatar,
    )
}
