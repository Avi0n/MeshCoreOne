// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeConfigImportPlanner.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.PathEncoding
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.time.Instant

/** One channel slot read from the device during the import read phase. */
internal data class DeviceChannelSlot(val index: UByte, val name: String, val secret: Bytes, val isConfigured: Boolean)

/**
 * A fully-resolved, validated set of writes produced before any destructive write begins. Building
 * one throws [NodeConfigServiceError] on any structural problem, so nothing is half-applied.
 */
internal data class ConfigImportPlan(
    /** Validated 64-byte private key to push, when identity is selected and present. */
    val importPrivateKey: Bytes? = null,
    val nodeName: String? = null,
    val position: Coordinate? = null,
    /** Passed through verbatim (raw bytes preserved) and merged at execute time. */
    val otherSettings: MeshCoreNodeConfig.OtherSettings? = null,
    val radioSettings: MeshCoreNodeConfig.RadioSettings? = null,
    /** Deduplicated writes; intra-import duplicates fold onto one slot. */
    val channelWrites: List<ChannelWrite> = emptyList(),
    /** True when any write replaces an already-configured slot whose name/secret differs. */
    val channelsOverwriteExisting: Boolean = false,
    /** Deduplicated contacts in import order, including device copies needing local reconciliation. */
    val contactActions: List<ContactAction> = emptyList(),
) {
    data class Coordinate(val latitude: Double, val longitude: Double)
    data class ChannelWrite(val index: UByte, val name: String, val secret: Bytes)
    data class ContactAction(val contact: MeshContact, val writeDevice: Boolean)

    val contactRecords: List<MeshContact> get() = contactActions.filter { it.writeDevice }.map { it.contact }
}

/**
 * Validates [config] against device capabilities and current channel/contact state, returning a
 * ready-to-execute plan or throwing the first problem. Pure and synchronous; only sections selected
 * in [sections] are planned.
 */
internal fun planConfigImport(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    maxChannels: UByte,
    maxContacts: Int,
    maxTxPower: Byte,
    existingChannels: List<DeviceChannelSlot>,
    existingContacts: Map<String, MeshContact>,
): ConfigImportPlan {
    var plan = ConfigImportPlan()
    if (sections.nodeIdentity) {
        plan = plan.copy(importPrivateKey = planPrivateKey(config), nodeName = config.name)
    }
    val position = config.positionSettings
    if (sections.positionSettings && position != null) {
        val latitude = validatedCoordinate(position.latitude, CoordinateField.PositionLatitude, PacketBuilder.LATITUDE_RANGE)
        val longitude = validatedCoordinate(position.longitude, CoordinateField.PositionLongitude, PacketBuilder.LONGITUDE_RANGE)
        plan = plan.copy(position = ConfigImportPlan.Coordinate(latitude, longitude))
    }
    if (sections.otherSettings) plan = plan.copy(otherSettings = config.otherSettings)
    val radio = config.radioSettings
    if (sections.radioSettings && radio != null) plan = plan.copy(radioSettings = planRadioSettings(radio, maxTxPower))
    val channels = config.channels
    if (sections.channels && channels != null) {
        val (writes, overwrite) = planChannelWrites(channels, maxChannels, existingChannels)
        plan = plan.copy(channelWrites = writes, channelsOverwriteExisting = overwrite)
    }
    val contacts = config.contacts
    if (sections.contacts && contacts != null) {
        plan = plan.copy(contactActions = planContactRecords(contacts, maxContacts, existingContacts))
    }
    return plan
}

// MARK: - Identity

/**
 * A present-but-unparseable key is rejected, never skipped. The key is the 64-byte expanded Ed25519
 * secret; its pairing with the public key is taken on trust (firmware re-derives and validates it).
 */
private fun planPrivateKey(config: MeshCoreNodeConfig): Bytes? {
    val privateKeyHex = config.privateKey ?: return null
    val data = NodeConfigSwiftText.strictHexBytes(privateKeyHex)
    if (data == null || data.size != ProtocolLimits.PRIVATE_KEY_SIZE) {
        throw NodeConfigServiceError.InvalidPrivateKey(NodeConfigSwiftText.graphemeCount(privateKeyHex))
    }
    return data
}

// MARK: - Coordinates

private fun validatedCoordinate(raw: String, field: CoordinateField, range: ClosedFloatingPointRange<Double>): Double {
    val value = NodeConfigSwiftText.parseDouble(raw)
    if (value == null || !value.isFinite() || value !in range) throw NodeConfigServiceError.InvalidCoordinate(field)
    return value
}

// MARK: - Radio

/** Rejects out-of-range radio values up front; the TX power ceiling is the device-reported maximum. */
private fun planRadioSettings(radio: MeshCoreNodeConfig.RadioSettings, maxTxPower: Byte): MeshCoreNodeConfig.RadioSettings {
    if (radio.frequency !in PacketBuilder.FREQUENCY_RANGE_KHZ) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.FREQUENCY)
    if (radio.bandwidth !in PacketBuilder.BANDWIDTH_RANGE_HZ) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.BANDWIDTH)
    if (radio.spreadingFactor.toInt() !in PacketBuilder.SPREADING_FACTOR_RANGE) {
        throw NodeConfigServiceError.InvalidRadioSettings(RadioField.SPREADING_FACTOR)
    }
    if (radio.codingRate.toInt() !in PacketBuilder.CODING_RATE_RANGE) throw NodeConfigServiceError.InvalidRadioSettings(RadioField.CODING_RATE)
    if (radio.txPower < PacketBuilder.TX_POWER_FLOOR || radio.txPower > maxTxPower) {
        throw NodeConfigServiceError.InvalidRadioSettings(RadioField.TX_POWER)
    }
    return radio
}

// MARK: - Channels

private class ChannelSlotState(val name: String, val secret: Bytes)

/**
 * Slot assignment with merge semantics: a secret stays single-homed (firmware matches channels by
 * secret), a hashtag name folds onto its existing slot, otherwise the first empty slot is used.
 * Byte-identical writes are skipped and overwrites of device-configured slots are flagged.
 */
private fun planChannelWrites(
    channels: List<MeshCoreNodeConfig.ChannelConfig>,
    maxChannels: UByte,
    existingChannels: List<DeviceChannelSlot>,
): Pair<List<ConfigImportPlan.ChannelWrite>, Boolean> {
    val hashtagNameToIndex = HashMap<String, UByte>()
    val secretToIndex = HashMap<String, UByte>()
    val emptyIndices = ArrayDeque<UByte>()
    val existingByIndex = HashMap<UByte, ChannelSlotState>()

    for (slot in existingChannels) {
        if (slot.index >= maxChannels) continue
        if (slot.isConfigured) {
            existingByIndex[slot.index] = ChannelSlotState(slot.name, slot.secret)
            secretToIndex[slot.secret.hexString] = slot.index
            if (NodeConfigSwiftText.hasCharacterPrefix(slot.name, "#")) {
                hashtagNameToIndex[NodeConfigSwiftText.canonicalKey(slot.name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES))] = slot.index
            }
        } else {
            emptyIndices.addLast(slot.index)
        }
    }

    val writes = mutableListOf<ConfigImportPlan.ChannelWrite>()
    var overwrite = false
    // The value each slot will hold given the writes planned so far; the no-op skip compares
    // against this so a later duplicate restoring a changed slot is not dropped.
    val plannedByIndex = HashMap(existingByIndex)

    channels.forEachIndexed { position, channel ->
        val secretData = NodeConfigSwiftText.strictHexBytes(channel.secret)
        if (secretData == null || secretData.size != ProtocolLimits.CHANNEL_SECRET_SIZE) {
            throw NodeConfigServiceError.InvalidChannelSecret(position, NodeConfigSwiftText.graphemeCount(channel.secret))
        }
        val secretKey = secretData.hexString
        val lookupName = channel.name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)
        // Swift String keys and comparisons are canonical-equivalence based (see canonicalKey).
        val hashtagKey = NodeConfigSwiftText.canonicalKey(lookupName)
        val isHashtag = NodeConfigSwiftText.hasCharacterPrefix(channel.name, "#")

        val targetIndex: UByte = secretToIndex[secretKey]
            ?: hashtagNameToIndex[hashtagKey]?.takeIf { isHashtag }
            ?: emptyIndices.removeFirstOrNull()
            ?: throw NodeConfigServiceError.NoAvailableChannelSlot(channel.name)

        val planned = plannedByIndex[targetIndex]
        if (planned != null && NodeConfigSwiftText.canonicallyEqual(planned.name, lookupName) && planned.secret == secretData) {
            if (isHashtag) hashtagNameToIndex[hashtagKey] = targetIndex
            secretToIndex[secretKey] = targetIndex
            return@forEachIndexed
        }
        val original = existingByIndex[targetIndex]
        if (original != null && (!NodeConfigSwiftText.canonicallyEqual(original.name, lookupName) || original.secret != secretData)) {
            overwrite = true
        }

        if (isHashtag) hashtagNameToIndex[hashtagKey] = targetIndex
        secretToIndex[secretKey] = targetIndex
        plannedByIndex[targetIndex] = ChannelSlotState(lookupName, secretData)
        writes += ConfigImportPlan.ChannelWrite(targetIndex, channel.name, secretData)
    }
    return writes to overwrite
}

// MARK: - Contacts

private class KeyedContact(val config: MeshCoreNodeConfig.ContactConfig, val publicKey: Bytes)

/**
 * Validates and deduplicates contacts (newest `last_modified` wins, ties go to the later entry),
 * enforces remaining device capacity (keys already on the device consume no slot), and reconciles
 * unchanged device records locally without writing them back to the radio.
 */
private fun planContactRecords(
    contacts: List<MeshCoreNodeConfig.ContactConfig>,
    maxContacts: Int,
    existingContacts: Map<String, MeshContact>,
): List<ConfigImportPlan.ContactAction> {
    val byKey = HashMap<String, KeyedContact>()
    val order = mutableListOf<String>()
    for (contact in contacts) {
        val publicKey = NodeConfigSwiftText.strictHexBytes(contact.publicKey)
        if (publicKey == null || publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) {
            throw NodeConfigServiceError.InvalidContactPublicKey(contact.name)
        }
        val key = publicKey.hexString
        val existing = byKey[key]
        if (existing == null) order += key
        if (existing == null || contact.lastModified >= existing.config.lastModified) byKey[key] = KeyedContact(contact, publicKey)
    }

    val newKeyCount = order.count { it !in existingContacts }
    val availableSlots = maxContacts - existingContacts.size
    if (newKeyCount > availableSlots) throw NodeConfigServiceError.ContactCapacityExceeded(newKeyCount, availableSlots)

    return order.map { key ->
        val entry = checkNotNull(byKey[key])
        val record = buildContactRecord(entry.config, entry.publicKey, key)
        val deviceCopy = existingContacts[key]
        if (deviceCopy != null && persistedContactFieldsMatch(deviceCopy, record)) {
            ConfigImportPlan.ContactAction(deviceCopy, writeDevice = false)
        } else {
            ConfigImportPlan.ContactAction(record, writeDevice = true)
        }
    }
}

/**
 * True when the device copy already stores exactly what [record] would write: the add-frame fields
 * the firmware persists, names at the firmware field width, coordinates as stored integers;
 * `lastAdvertisement` is excluded (volatile).
 */
private fun persistedContactFieldsMatch(existing: MeshContact, record: MeshContact): Boolean {
    val width = ProtocolLimits.MAX_USABLE_NAME_BYTES
    if (existing.typeRawValue != record.typeRawValue || existing.flags != record.flags ||
        existing.outPathLength != record.outPathLength ||
        !NodeConfigSwiftText.canonicallyEqual(existing.advertisedName.utf8Prefix(width), record.advertisedName.utf8Prefix(width)) ||
        existing.outPath.prefix(existing.pathByteLength) != record.outPath.prefix(record.pathByteLength) ||
        NodeConfigSwiftText.truncatedEpochSeconds(existing.lastModified) != NodeConfigSwiftText.truncatedEpochSeconds(record.lastModified)
    ) return false
    return PacketBuilder.scaledCoordinate(existing.latitude, PacketBuilder.LATITUDE_RANGE) ==
        PacketBuilder.scaledCoordinate(record.latitude, PacketBuilder.LATITUDE_RANGE) &&
        PacketBuilder.scaledCoordinate(existing.longitude, PacketBuilder.LONGITUDE_RANGE) ==
        PacketBuilder.scaledCoordinate(record.longitude, PacketBuilder.LONGITUDE_RANGE)
}

private fun buildContactRecord(contact: MeshCoreNodeConfig.ContactConfig, publicKey: Bytes, hexKey: String): MeshContact {
    val (outPath, outPathLength) = resolveOutPath(contact)
    val latitude = validatedCoordinate(contact.latitude, CoordinateField.ContactLatitude(contact.name), PacketBuilder.LATITUDE_RANGE)
    val longitude = validatedCoordinate(contact.longitude, CoordinateField.ContactLongitude(contact.name), PacketBuilder.LONGITUDE_RANGE)
    return MeshContact(
        id = hexKey,
        publicKey = publicKey,
        type = ContactType.fromRawValue(contact.type) ?: ContactType.CHAT,
        flags = ContactFlags(contact.flags),
        outPathLength = outPathLength,
        outPath = outPath,
        advertisedName = contact.name,
        lastAdvertisement = Instant.ofEpochSecond(contact.lastAdvert.toLong()),
        latitude = latitude,
        longitude = longitude,
        lastModified = Instant.ofEpochSecond(contact.lastModified.toLong()),
        typeRawValue = contact.type,
    )
}

/** Absent path = flood, "" = direct; anything else must be valid, in-bounds routed hex. */
private fun resolveOutPath(contact: MeshCoreNodeConfig.ContactConfig): Pair<Bytes, UByte> {
    val pathHex = contact.outPath ?: return Bytes.EMPTY to PacketBuilder.FLOOD_PATH_SENTINEL
    if (pathHex.isEmpty()) return Bytes.EMPTY to 0u
    val pathData = NodeConfigSwiftText.strictHexBytes(pathHex)
    if (pathData == null || pathData.isEmpty) throw NodeConfigServiceError.InvalidOutPath(contact.name)
    val mode = contact.pathHashMode ?: 0u
    if (mode.toInt() > PathEncoding.MAX_PATH_HASH_MODE) throw NodeConfigServiceError.InvalidPathHashMode(contact.name, mode)
    val hashSize = mode.toInt() + 1
    if (pathData.size % hashSize != 0 || pathData.size / hashSize > PathEncoding.MAX_HOP_COUNT ||
        pathData.size > PathEncoding.MAX_PATH_BYTES
    ) throw NodeConfigServiceError.InvalidOutPath(contact.name)
    return pathData to encodePathLen(hashSize, pathData.size / hashSize)
}
