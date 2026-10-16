package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid

/**
 * Matches backup devices to local devices by `publicKey` (the reconciliation key; see the device
 * identity model) and produces the radioID remap every other imported family routes through.
 * Mirrors Swift `PersistenceStore.buildRadioIDMapping`.
 */
object BackupRadioIdentity {
    /** Result of matching backup devices to local devices by publicKey. */
    data class RadioIDMapping(
        val mapping: Map<Uuid, Uuid>,
        val unmatchedDevices: List<DeviceDto>,
        val duplicateDeviceCount: Int,
    )

    /**
     * Matches each backup device to a local device by publicKey, producing a radioID remap for
     * child records. A publicKey match never mints a fresh local radioID: the matched device's own
     * local radioID wins. An unmatched device keeps its own (foreign) radioID so its already-remapped
     * child rows stay attached to it once it is inserted as a new local device. Duplicate publicKeys
     * within the envelope (corruption) are counted so the caller can surface the skip; the duplicate's
     * radioID is pointed at the first occurrence's resolved local radioID so its child rows still
     * resolve to a Device row that actually exists.
     */
    fun buildRadioIDMapping(devices: List<DeviceDto>, localRadioIDsByPublicKey: Map<String, Uuid>): RadioIDMapping {
        val mapping = mutableMapOf<Uuid, Uuid>()
        val unmatched = mutableListOf<DeviceDto>()
        val firstRadioIDByPublicKey = mutableMapOf<String, Uuid>()
        var duplicates = 0
        for (backupDevice in devices) {
            val publicKeyKey = backupDevice.publicKey.toBase64Key()
            val firstRadioID = firstRadioIDByPublicKey[publicKeyKey]
            if (firstRadioID != null) {
                duplicates++
                pointDuplicateAtWinningLocal(mapping, backupDevice.radioID, firstRadioID)
                continue
            }
            firstRadioIDByPublicKey[publicKeyKey] = backupDevice.radioID
            val localRadioID = localRadioIDsByPublicKey[publicKeyKey]
            if (localRadioID != null) {
                mapping[backupDevice.radioID] = localRadioID
            } else {
                mapping[backupDevice.radioID] = backupDevice.radioID
                unmatched += backupDevice
            }
        }
        return RadioIDMapping(mapping, unmatched, duplicates)
    }

    /**
     * Points a duplicate backup device's radioID at the first occurrence's resolved local radioID,
     * so its child rows still resolve to a Device row that actually exists. No-op when the duplicate
     * shares the first occurrence's own radioID or already has a mapping entry.
     */
    private fun pointDuplicateAtWinningLocal(
        mapping: MutableMap<Uuid, Uuid>,
        duplicateRadioID: Uuid,
        firstRadioID: Uuid,
    ) {
        if (duplicateRadioID == firstRadioID || mapping[duplicateRadioID] != null) return
        val winningLocal = mapping[firstRadioID] ?: return
        mapping[duplicateRadioID] = winningLocal
    }
}

/** Rewrites `radioID` through `mapping`; leaves an entry unchanged when its radioID isn't a mapping key. */
fun ContactDto.remapRadioID(mapping: Map<Uuid, Uuid>): ContactDto = mapping[radioID]?.let { copy(radioID = it) } ?: this

fun ChannelDto.remapRadioID(mapping: Map<Uuid, Uuid>): ChannelDto = mapping[radioID]?.let { copy(radioID = it) } ?: this

fun MessageDto.remapRadioID(mapping: Map<Uuid, Uuid>): MessageDto = mapping[radioID]?.let { copy(radioID = it) } ?: this

fun ReactionDto.remapRadioID(mapping: Map<Uuid, Uuid>): ReactionDto =
    mapping[radioID]?.let { copy(radioID = it) } ?: this

fun RemoteNodeSessionDto.remapRadioID(mapping: Map<Uuid, Uuid>): RemoteNodeSessionDto =
    mapping[radioID]?.let { copy(radioID = it) } ?: this

fun SavedTracePathDto.remapRadioID(mapping: Map<Uuid, Uuid>): SavedTracePathDto =
    mapping[radioID]?.let { copy(radioID = it) } ?: this

fun BlockedChannelSenderDto.remapRadioID(mapping: Map<Uuid, Uuid>): BlockedChannelSenderDto =
    mapping[radioID]?.let { copy(radioID = it) } ?: this

fun DiscoveredNodeDto.remapRadioID(mapping: Map<Uuid, Uuid>): DiscoveredNodeDto =
    mapping[radioID]?.let { copy(radioID = it) } ?: this

// Per-family List<T> convenience wrappers aren't provided: an extension on List<T> for two
// different concrete T erase to the same JVM signature ("platform declaration clash"), so callers
// map { it.remapRadioID(mapping) } instead.
