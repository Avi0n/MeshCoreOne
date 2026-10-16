package one.meshcore.shared.persistence

import one.meshcore.shared.channel.ChannelFloodScopeDeviceInputs
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.Uuid

/**
 * Flood-scope picker inputs for [radioID] from the Device row (known regions + default flood name).
 * Empty when no device row exists for the partition.
 */
suspend fun floodScopeDeviceInputs(database: MeshDatabase, radioID: Uuid): ChannelFloodScopeDeviceInputs {
    val device = database.deviceDao().listByRadio(radioID).firstOrNull()
    return ChannelFloodScopeDeviceInputs.fromDevice(
        knownRegions = device?.knownRegions,
        defaultFloodScopeName = device?.defaultFloodScopeName,
        firmwareVersion = device?.firmwareVersion,
    )
}

/**
 * Appends region names to Device.knownRegions for [radioID], skipping duplicates (iOS
 * `addDeviceKnownRegion`). Returns the full known-region list after the write, or empty if no device.
 */
suspend fun appendDeviceKnownRegions(database: MeshDatabase, radioID: Uuid, regions: List<String>): List<String> {
    val device = database.deviceDao().listByRadio(radioID).firstOrNull() ?: return emptyList()
    if (regions.isEmpty()) return device.knownRegions
    val merged = (device.knownRegions + regions).distinct()
    if (merged == device.knownRegions) return device.knownRegions
    database.deviceDao().upsert(device.copy(knownRegions = merged))
    return merged
}

/**
 * Removes [region] from Device.knownRegions for [radioID]. Also clears `defaultFloodScopeName` when it
 * matched the removed region, and resets channels scoped to that region to inherit (iOS
 * `removeDeviceKnownRegion`). Returns the known-region list after the write, or empty if no device.
 */
suspend fun removeDeviceKnownRegion(database: MeshDatabase, radioID: Uuid, region: String): List<String> {
    val device = database.deviceDao().listByRadio(radioID).firstOrNull() ?: return emptyList()
    val updated = device.copy(
        knownRegions = device.knownRegions - region,
        defaultFloodScopeName = device.defaultFloodScopeName.takeUnless { it == region },
    )
    if (updated.knownRegions != device.knownRegions ||
        updated.defaultFloodScopeName != device.defaultFloodScopeName
    ) {
        database.deviceDao().upsert(updated)
    }
    resetChannelsScopedToRegion(database, radioID, region)
    return updated.knownRegions
}

private suspend fun resetChannelsScopedToRegion(database: MeshDatabase, radioID: Uuid, region: String) {
    val inherit = ChannelFloodScopeStorage.decompose(ChannelFloodScope.Inherit)
    database.channelDao().listByRadio(radioID)
        .filter { it.floodScope == ChannelFloodScope.Region(region) }
        .forEach { channel ->
            database.channelDao().upsert(
                channel.copy(
                    floodScopeModeRawValue = inherit.first,
                    regionScope = inherit.second,
                ),
            )
        }
}

/** Persists the device's verified default-flood-scope display name (the radio push is the caller's job). */
suspend fun setDeviceDefaultFloodScopeName(database: MeshDatabase, radioID: Uuid, name: String?) {
    val device = database.deviceDao().listByRadio(radioID).firstOrNull() ?: return
    database.deviceDao().upsert(device.copy(defaultFloodScopeName = name))
}

/** Persists a channel's flood-scope preference columns. */
suspend fun setChannelFloodScope(database: MeshDatabase, channelId: Uuid, scope: ChannelFloodScope) {
    val (mode, region) = ChannelFloodScopeStorage.decompose(scope)
    val current = database.channelDao().getById(channelId) ?: return
    database.channelDao().upsert(
        current.copy(
            floodScopeModeRawValue = mode,
            regionScope = region,
        ),
    )
}
