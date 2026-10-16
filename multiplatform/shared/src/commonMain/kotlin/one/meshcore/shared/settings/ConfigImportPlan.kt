package one.meshcore.shared.settings

/**
 * A snapshot of one device channel slot read during the import read phase, consumed by
 * [planConfigImport] so slot planning stays a pure function. Mirrors the Swift `DeviceChannelSlot`.
 */
data class DeviceChannelSlot(val index: Int, val name: String, val secret: ByteArray, val isConfigured: Boolean)

/**
 * A device-resident contact, keyed by lowercase-hex public key in the planner's `existingContacts` map. Only
 * the fields the contact-add frame carries are needed for the byte-for-byte "already stored" comparison
 * ([outPath] is already trimmed to the meaningful path bytes; `lastAdvertisement` is deliberately excluded).
 */
data class ExistingContact(
    val typeRawValue: UByte,
    val flags: UByte,
    val outPathLength: UByte,
    val outPath: ByteArray,
    val advertisedName: String,
    val lastModified: Long,
    val latitude: Double,
    val longitude: Double,
)

/** A validated contact record ready to write, mirroring the fields of the Swift `MeshContact` add frame. */
data class PlannedContact(
    val id: String,
    val publicKey: ByteArray,
    val typeRawValue: UByte,
    val flags: UByte,
    val outPathLength: UByte,
    val outPath: ByteArray,
    val advertisedName: String,
    val lastAdvert: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
)

/**
 * A fully-resolved, validated set of writes produced from a [MeshCoreNodeConfig] before any destructive
 * write begins, mirroring the Swift `ConfigImportPlan`. The execute phase performs only writes already
 * proven applyable.
 */
data class ConfigImportPlan(
    val importPrivateKey: ByteArray? = null,
    val nodeName: String? = null,
    val position: Coordinate? = null,
    val otherSettings: MeshCoreNodeConfig.OtherSettings? = null,
    val radioSettings: MeshCoreNodeConfig.RadioSettings? = null,
    val channelWrites: List<ChannelWrite> = emptyList(),
    val channelsOverwriteExisting: Boolean = false,
    val contactRecords: List<PlannedContact> = emptyList(),
) {
    data class Coordinate(val latitude: Double, val longitude: Double)

    /** A resolved channel write at [index]; [secret] is the parsed 16-byte secret. */
    data class ChannelWrite(val index: Int, val name: String, val secret: ByteArray)
}
