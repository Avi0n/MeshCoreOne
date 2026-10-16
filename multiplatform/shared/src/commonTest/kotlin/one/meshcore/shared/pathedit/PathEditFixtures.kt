package one.meshcore.shared.pathedit

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/** Deterministic test doubles for the path-editing logic, mirroring the Swift `ContactDTO.fixture` defaults. */
internal object PathEditFixtures {
    const val PUBLIC_KEY_LENGTH = 32
    const val FLOOD_SENTINEL = 0xFF

    /** A 32-byte public key whose leading bytes are [prefix] and the rest zero. */
    fun key(vararg prefix: Int): ByteArray =
        ByteArray(PUBLIC_KEY_LENGTH) { i -> if (i < prefix.size) prefix[i].toByte() else 0 }

    /** A deterministic [Uuid] from a small index. */
    fun uuid(index: Int): Uuid = Uuid.normalized("A0000000-0000-0000-0000-${index.toString().padStart(12, '0')}")

    /** A repeater contact by default, matching the Swift fixture: name "Test Repeater", 0xAA key, flood-routed. */
    @Suppress("LongParameterList")
    fun contact(
        name: String = "Test Repeater",
        publicKey: ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xAA.toByte() },
        type: ContactType = ContactType.REPEATER,
        isFavorite: Boolean = false,
        radioID: Uuid = uuid(1),
        outPathLength: Int = FLOOD_SENTINEL,
        outPath: ByteArray = ByteArray(0),
        id: Uuid = uuid(2),
    ): ContactDto = ContactDto(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        name = name,
        typeRawValue = type.raw,
        flags = 0,
        outPathLength = outPathLength,
        outPath = outPath,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = isFavorite,
        unreadCount = 0,
    )

    /** A discovered repeater, matching the Swift fixture: name "Test Discovered", 0xBB key. */
    fun discovered(
        name: String = "Test Discovered",
        publicKey: ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xBB.toByte() },
        isRepeater: Boolean = true,
        id: Uuid = uuid(3),
        location: LatLng? = null,
    ): DiscoveredNode = DiscoveredNode(
        id = id,
        publicKey = publicKey,
        name = name,
        isRepeater = isRepeater,
        location = location,
    )
}
