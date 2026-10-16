package one.meshcore.shared.contact.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

internal object ContactClipboardFixtures {
    const val COPIED_ROUTE = "A3FF,0012"
    const val COPIED_KEY = "A30102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1EFF"
    private const val KEY_SIZE = 32
    private const val TWO_HOPS_TWO_BYTES = 0x42
    private val publicKey = ByteArray(KEY_SIZE) { it.toByte() }.apply {
        this[0] = 0xA3.toByte()
        this[lastIndex] = 0xFF.toByte()
    }
    val contact = ContactDto(
        id = Uuid.normalized("10000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("10000000-0000-0000-0000-000000000002"),
        publicKey = publicKey,
        name = "Contact",
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = TWO_HOPS_TWO_BYTES,
        outPath = byteArrayOf(0xA3.toByte(), 0xFF.toByte(), 0x00, 0x12),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
    val disabledActions = ContactDetailLogic.ActionEnablement(
        sendMessage = false,
        joinRoom = false,
        telemetry = false,
        management = false,
        ping = false,
        shareViaAdvert = false,
        discoverPath = false,
        editPath = false,
        resetPath = false,
        favorite = false,
        block = false,
        delete = false,
    )
}
