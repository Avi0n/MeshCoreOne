package one.meshcore.shared.simulator

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.ContactEntity

internal fun MockDataProvider.contacts(now: Double): List<ContactEntity> = buildList {
    addAll(contactsThroughCharlie(now))
    addAll(contactsDianaThroughFrank(now))
    addAll(contactsGhostAndHannah(now))
}

private fun MockDataProvider.contactsThroughCharlie(now: Double): List<ContactEntity> = listOf(
    contact(
        ContactDraft(
            id = aliceChenID,
            seed = 10,
            name = "Alice Chen",
            type = ContactType.CHAT,
            outPath = ALICE_CONTACT_PATH,
            lastAdvertAge = 300,
            latitude = 37.7849,
            longitude = -122.4094,
            lastMessageAge = 1800,
            unreadCount = 3,
            now = now,
        ),
    ),
    contact(
        ContactDraft(
            id = bobMartinezID,
            seed = 20,
            name = "Bob Martinez",
            type = ContactType.CHAT,
            outPath = BOB_CONTACT_PATH,
            lastAdvertAge = 60,
            latitude = 37.7649,
            longitude = -122.4294,
            lastMessageAge = 900,
            now = now,
        ),
    ),
    contact(
        ContactDraft(
            id = charlieNodeID,
            seed = 30,
            name = "Charlie Node",
            type = ContactType.REPEATER,
            outPath = byteArrayOf(),
            lastAdvertAge = 120,
            latitude = 37.7549,
            longitude = -122.4394,
            now = now,
        ),
    ),
)

private fun MockDataProvider.contactsDianaThroughFrank(now: Double): List<ContactEntity> = listOf(
    contact(
        ContactDraft(
            id = dianasRoomID,
            seed = 40,
            name = "Diana's Room",
            type = ContactType.ROOM,
            outPath = DIANA_CONTACT_PATH,
            lastAdvertAge = 600,
            latitude = 37.7449,
            longitude = -122.4494,
            now = now,
        ),
    ),
    contact(
        ContactDraft(
            id = eveThompsonID,
            seed = 50,
            name = "Eve Thompson",
            type = ContactType.CHAT,
            outPath = EVE_CONTACT_PATH,
            lastAdvertAge = 1800,
            latitude = 37.7349,
            longitude = -122.4594,
            isBlocked = true,
            now = now,
        ),
    ),
    contact(
        ContactDraft(
            id = frankWilsonID,
            seed = 60,
            name = "Frank Wilson",
            type = ContactType.CHAT,
            outPath = FRANK_CONTACT_PATH,
            lastAdvertAge = 3600,
            latitude = 37.7249,
            longitude = -122.4694,
            nickname = "Dad",
            lastMessageAge = 1800,
            now = now,
        ),
    ),
)

private fun MockDataProvider.contactsGhostAndHannah(now: Double): List<ContactEntity> = listOf(
    contact(
        ContactDraft(
            id = ghostNodeID,
            seed = 70,
            name = "Ghost Node",
            type = ContactType.REPEATER,
            outPath = GHOST_CONTACT_PATH,
            lastAdvertAge = 86400,
            latitude = 0.0,
            longitude = 0.0,
            lastModifiedAge = 86400,
            now = now,
        ),
    ),
    contact(
        ContactDraft(
            id = hannahLeeID,
            seed = 80,
            name = "Hannah Lee",
            type = ContactType.CHAT,
            outPath = byteArrayOf(0x80.toByte()),
            lastAdvertAge = 30,
            latitude = 37.7149,
            longitude = -122.4794,
            isFavorite = true,
            lastMessageAge = 600,
            now = now,
        ),
    ),
)

internal fun MockDataProvider.channels(now: Double): List<ChannelEntity> = listOf(
    ChannelEntity(
        id = publicChannelID,
        radioID = simulatorDeviceID,
        index = PUBLIC_CHANNEL_INDEX,
        name = "Public",
        secret = channelSecret(seed = 0xA0),
        isEnabled = true,
        unreadCount = 2,
        lastMessageDate = now - 600,
    ),
    ChannelEntity(
        id = bayAreaChannelID,
        radioID = simulatorDeviceID,
        index = BAY_AREA_CHANNEL_INDEX,
        name = "Bay Area",
        secret = channelSecret(seed = 0xB0),
        isEnabled = true,
        unreadCount = 1,
        unreadMentionCount = 1,
        isPinned = true,
        lastMessageDate = now - 3600,
    ),
    ChannelEntity(
        id = trailCrewChannelID,
        radioID = simulatorDeviceID,
        index = TRAIL_CREW_CHANNEL_INDEX,
        name = "Trail Crew",
        secret = channelSecret(seed = 0xC0),
        isEnabled = true,
        unreadCount = 0,
        notificationLevelRawValue = NotificationLevel.MUTED.raw,
        lastMessageDate = now - 7200,
    ),
    ChannelEntity(
        id = meshHQChannelID,
        radioID = simulatorDeviceID,
        index = MESH_HQ_CHANNEL_INDEX,
        name = "Mesh HQ",
        secret = channelSecret(seed = 0xD0),
        isEnabled = true,
        unreadCount = MESH_HQ_UNREAD_COUNT,
        lastMessageDate = now - 90,
    ),
)

private fun MockDataProvider.contact(draft: ContactDraft): ContactEntity = ContactEntity(
    id = draft.id,
    radioID = simulatorDeviceID,
    publicKey = mockPublicKey(draft.seed),
    name = draft.name,
    typeRawValue = draft.type.raw,
    flags = 0,
    outPathLength = draft.outPath.size,
    outPath = draft.outPath,
    lastAdvertTimestamp = (draft.now - draft.lastAdvertAge).toLong(),
    latitude = draft.latitude,
    longitude = draft.longitude,
    lastModified = (draft.now - draft.lastModifiedAge).toLong(),
    nickname = draft.nickname,
    isBlocked = draft.isBlocked,
    isMuted = false,
    isFavorite = draft.isFavorite,
    unreadCount = draft.unreadCount,
    lastMessageDate = draft.lastMessageAge?.let { draft.now - it },
)

private data class ContactDraft(
    val id: Uuid,
    val seed: Int,
    val name: String,
    val type: ContactType,
    val outPath: ByteArray,
    val lastAdvertAge: Long,
    val latitude: Double,
    val longitude: Double,
    val now: Double,
    val lastModifiedAge: Long = 0,
    val nickname: String? = null,
    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,
    val lastMessageAge: Long? = null,
    val unreadCount: Int = 0,
)

// Fresh hop buffers on each read, so seeded contacts do not share an array.
private val ALICE_CONTACT_PATH: ByteArray
    get() = byteArrayOf(0x10, 0x20)

private val BOB_CONTACT_PATH: ByteArray
    get() = byteArrayOf(0x20)

private val DIANA_CONTACT_PATH: ByteArray
    get() = byteArrayOf(0x10, 0x20, 0x40)

private val EVE_CONTACT_PATH: ByteArray
    get() = byteArrayOf(0x10, 0x20, 0x30, 0x50)

private val FRANK_CONTACT_PATH: ByteArray
    get() = byteArrayOf(0x10, 0x60)

private val GHOST_CONTACT_PATH: ByteArray
    get() = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x70)
