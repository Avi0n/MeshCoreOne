package one.meshcore.shared.settings

import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.model.toLowercaseHex

/**
 * Validates a [MeshCoreNodeConfig] against the device's capabilities and current channel state, returning a
 * ready-to-execute [ConfigImportPlan] or throwing the first [NodeConfigError]. A pure port of the Swift
 * `planConfigImport`: synchronous and side-effect-free so a malformed/poison config is rejected before any
 * destructive write, and the execute phase performs only proven-applyable writes. Only sections present in
 * [sections] are planned.
 */
fun planConfigImport(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    device: ConfigImportDevice,
): ConfigImportPlan {
    var plan = ConfigImportPlan()

    if (sections.nodeIdentity) {
        plan = plan.copy(importPrivateKey = planPrivateKey(config), nodeName = config.name)
    }
    if (sections.positionSettings) {
        config.positionSettings?.let { position ->
            val lat = validatedCoordinate(position.latitude, CoordinateField.PositionLatitude, LAT_LIMIT)
            val lon = validatedCoordinate(position.longitude, CoordinateField.PositionLongitude, LON_LIMIT)
            plan = plan.copy(position = ConfigImportPlan.Coordinate(lat, lon))
        }
    }
    if (sections.otherSettings) {
        plan = plan.copy(otherSettings = config.otherSettings)
    }
    if (sections.radioSettings) {
        config.radioSettings?.let { plan = plan.copy(radioSettings = planRadioSettings(it, device.maxTxPower)) }
    }
    if (sections.channels) {
        config.channels?.let { channels ->
            val (writes, overwrite) = planChannelWrites(channels, device.maxChannels, device.existingChannels)
            plan = plan.copy(channelWrites = writes, channelsOverwriteExisting = overwrite)
        }
    }
    if (sections.contacts) {
        config.contacts?.let {
            plan = plan.copy(contactRecords = planContactRecords(it, device.maxContacts, device.existingContacts))
        }
    }
    return plan
}

private fun planPrivateKey(config: MeshCoreNodeConfig): ByteArray? {
    val privateKeyHex = config.privateKey ?: return null
    val data = hexToBytes(privateKeyHex)
    if (data == null || data.size != NodeConfigLimits.PRIVATE_KEY_SIZE) {
        throw NodeConfigError.InvalidPrivateKey(privateKeyHex.length)
    }
    return data
}

internal fun validatedCoordinate(raw: String, field: CoordinateField, limit: Double): Double {
    val value = raw.toDoubleOrNull()
    val outOfRange = value != null && (value < -limit || value > limit)
    if (value == null || !value.isFinite() || outOfRange) {
        throw NodeConfigError.InvalidCoordinate(field)
    }
    return value
}

private fun planRadioSettings(
    radio: MeshCoreNodeConfig.RadioSettings,
    maxTxPower: Byte,
): MeshCoreNodeConfig.RadioSettings {
    val field = invalidRadioField(radio, maxTxPower) ?: return radio
    throw NodeConfigError.InvalidRadioSettings(field)
}

private fun invalidRadioField(radio: MeshCoreNodeConfig.RadioSettings, maxTxPower: Byte): RadioField? = when {
    radio.frequency !in NodeConfigLimits.FREQUENCY_RANGE_KHZ -> RadioField.FREQUENCY
    radio.bandwidth !in NodeConfigLimits.BANDWIDTH_RANGE_HZ -> RadioField.BANDWIDTH
    radio.spreadingFactor.toUInt() !in NodeConfigLimits.SPREADING_FACTOR_RANGE -> RadioField.SPREADING_FACTOR
    radio.codingRate.toUInt() !in NodeConfigLimits.CODING_RATE_RANGE -> RadioField.CODING_RATE
    radio.txPower < NodeConfigLimits.TX_POWER_FLOOR || radio.txPower > maxTxPower -> RadioField.TX_POWER
    else -> null
}

/**
 * Plans channel slot assignment with merge semantics: a config secret single-homes onto the slot already
 * holding it (canonical re-hex compare), else a `#`-name match, else the first empty slot, else it is
 * rejected. Intra-import duplicates fold onto one slot; a write is skipped when the slot's effective value
 * already matches; an overwrite of a device-configured slot is flagged. Ports the Swift `planChannelWrites`.
 */
private fun planChannelWrites(
    channels: List<MeshCoreNodeConfig.ChannelConfig>,
    maxChannels: Int,
    existingChannels: List<DeviceChannelSlot>,
): Pair<List<ConfigImportPlan.ChannelWrite>, Boolean> {
    val index = indexExistingChannelSlots(existingChannels, maxChannels)
    val writes = mutableListOf<ConfigImportPlan.ChannelWrite>()
    var overwrite = false
    val plannedByIndex = index.existingByIndex.toMutableMap()

    channels.forEachIndexed { i, channel ->
        val planned = planOneChannelWrite(i, channel, index, plannedByIndex) ?: return@forEachIndexed
        if (planned.overwritesExisting) overwrite = true
        writes += planned.write
    }

    return writes to overwrite
}

private class ChannelSlotIndex(
    val hashtagNameToIndex: MutableMap<String, Int>,
    val secretToIndex: MutableMap<String, Int>,
    val emptyIndices: ArrayDeque<Int>,
    val existingByIndex: Map<Int, Pair<String, ByteArray>>,
)

private class ChannelWritePlan(val write: ConfigImportPlan.ChannelWrite, val overwritesExisting: Boolean)

private fun indexExistingChannelSlots(existingChannels: List<DeviceChannelSlot>, maxChannels: Int): ChannelSlotIndex {
    val hashtagNameToIndex = mutableMapOf<String, Int>()
    val secretToIndex = mutableMapOf<String, Int>()
    val emptyIndices = ArrayDeque<Int>()
    val existingByIndex = mutableMapOf<Int, Pair<String, ByteArray>>()
    for (slot in existingChannels.filter { it.index < maxChannels }) {
        if (slot.isConfigured) {
            existingByIndex[slot.index] = slot.name to slot.secret
            secretToIndex[slot.secret.toLowercaseHex()] = slot.index
            if (slot.name.startsWith("#")) {
                hashtagNameToIndex[slot.name.utf8BytePrefix(NodeConfigLimits.MAX_USABLE_NAME_BYTES)] = slot.index
            }
        } else {
            emptyIndices.addLast(slot.index)
        }
    }
    return ChannelSlotIndex(hashtagNameToIndex, secretToIndex, emptyIndices, existingByIndex)
}

private fun planOneChannelWrite(
    indexInConfig: Int,
    channel: MeshCoreNodeConfig.ChannelConfig,
    slots: ChannelSlotIndex,
    plannedByIndex: MutableMap<Int, Pair<String, ByteArray>>,
): ChannelWritePlan? {
    val secretData = hexToBytes(channel.secret)
    if (secretData == null || secretData.size != NodeConfigLimits.CHANNEL_SECRET_SIZE) {
        throw NodeConfigError.InvalidChannelSecret(indexInConfig, channel.secret.length)
    }
    val secretKey = secretData.toLowercaseHex()
    val lookupName = channel.name.utf8BytePrefix(NodeConfigLimits.MAX_USABLE_NAME_BYTES)
    val isHashtag = channel.name.startsWith("#")
    val targetIndex = slots.secretToIndex[secretKey]
        ?: slots.hashtagNameToIndex[lookupName]?.takeIf { isHashtag }
        ?: slots.emptyIndices.removeFirstOrNull()
        ?: throw NodeConfigError.NoAvailableChannelSlot(channel.name)

    val planned = plannedByIndex[targetIndex]
    if (planned != null && planned.first == lookupName && planned.second.contentEquals(secretData)) {
        if (isHashtag) slots.hashtagNameToIndex[lookupName] = targetIndex
        slots.secretToIndex[secretKey] = targetIndex
        return null
    }

    val original = slots.existingByIndex[targetIndex]
    val overwrites = original != null && (original.first != lookupName || !original.second.contentEquals(secretData))
    if (isHashtag) slots.hashtagNameToIndex[lookupName] = targetIndex
    slots.secretToIndex[secretKey] = targetIndex
    plannedByIndex[targetIndex] = lookupName to secretData
    return ChannelWritePlan(ConfigImportPlan.ChannelWrite(targetIndex, channel.name, secretData), overwrites)
}

private const val LAT_LIMIT = NodeConfigLimits.LATITUDE_LIMIT
private const val LON_LIMIT = NodeConfigLimits.LONGITUDE_LIMIT
