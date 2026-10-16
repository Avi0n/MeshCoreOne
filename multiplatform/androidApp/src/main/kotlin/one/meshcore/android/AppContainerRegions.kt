package one.meshcore.android

import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.connection.FAILED_ADD_KNOWN_REGION
import one.meshcore.shared.connection.FAILED_CLEAR_DEFAULT_FLOOD_AFTER_REGION_REMOVAL
import one.meshcore.shared.connection.FAILED_REMOVE_KNOWN_REGION
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.persistDeviceSettingsAfterRadioAck
import one.meshcore.shared.connection.rebootDevice
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.persistence.appendDeviceKnownRegions
import one.meshcore.shared.persistence.removeDeviceKnownRegion
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_region_err_loading_repeaters
import one.meshcore.shared.resources.chats_channel_info_region_err_radio_contacts_full
import one.meshcore.shared.resources.chats_channel_info_region_no_new_regions
import one.meshcore.shared.resources.chats_channel_info_region_no_repeaters_responded
import one.meshcore.shared.tools.loadContactsForRxLogDecrypt
import org.jetbrains.compose.resources.getString

/** Appends a manually-entered region to Device.knownRegions. */
suspend fun AppContainer.addKnownRegion(region: String) {
    val radioId = connectionManager.radioId ?: return
    floodScopePublicationGate.invalidate()
    var merged = connectedKnownRegions.get()
    persistDeviceSettingsAfterRadioAck(connectionLogger, FAILED_ADD_KNOWN_REGION) {
        merged = appendDeviceKnownRegions(database, radioId, listOf(region))
    }
    floodScopePublicationGate.publishMutation(radioId, currentRadio = { connectionManager.radioId }) {
        connectedKnownRegions.set(merged)
        rxLogDecodeCache.updateKnownRegions(merged)
    }
}

/**
 * Removes a region from Device.knownRegions. If it was the current default flood scope, also
 * clears the scope on the radio so firmware state can't dangle on a deleted name (iOS
 * `removeKnownRegion`).
 */
suspend fun AppContainer.removeKnownRegion(region: String) {
    val owner = connectionManager.services ?: return
    val radioId = owner.radioId
    floodScopePublicationGate.invalidate()
    val wasDefault = connectedDefaultFloodScopeName.value == region
    var updated = connectedKnownRegions.get()
    persistDeviceSettingsAfterRadioAck(connectionLogger, FAILED_REMOVE_KNOWN_REGION) {
        updated = removeDeviceKnownRegion(database, radioId, region)
    }
    if (connectionManager.services !== owner || owner.radioId != radioId) return
    floodScopePublicationGate.publishMutation(radioId, currentRadio = { connectionManager.radioId }) {
        connectedKnownRegions.set(updated)
        rxLogDecodeCache.updateKnownRegions(updated)
    }
    if (wasDefault) {
        connectedDefaultFloodScopeName.value = null
        persistDeviceSettingsAfterRadioAck(
            connectionLogger,
            FAILED_CLEAR_DEFAULT_FLOOD_AFTER_REGION_REMOVAL,
        ) { applyDefaultFloodScope(null) }
    }
}

/** Reboots the connected companion device. */
suspend fun AppContainer.rebootDevice() {
    connectionManager.rebootDevice()
}
suspend fun AppContainer.regionDiscoverStatusMessage(result: RegionDiscoverUiResult): String? = when (result) {
    RegionDiscoverUiResult.SendFailed ->
        getString(Res.string.chats_channel_info_region_no_repeaters_responded)
    RegionDiscoverUiResult.NoRepeaters ->
        getString(Res.string.chats_channel_info_region_no_repeaters_responded)
    RegionDiscoverUiResult.ErrorLoading ->
        getString(Res.string.chats_channel_info_region_err_loading_repeaters)
    is RegionDiscoverUiResult.Completed -> when {
        result.newRegions.isEmpty() && result.allTableFull ->
            getString(Res.string.chats_channel_info_region_err_radio_contacts_full)
        result.newRegions.isEmpty() ->
            getString(Res.string.chats_channel_info_region_no_new_regions)
        else -> null
    }
}

/**
 * Loads contact Ed25519 public keys so RX Log can ECDH-decrypt direct messages after
 * GET_CONTACTS (iOS `rxLogService.updateContactPublicKeys`). The companion private key is
 * already in the cache from [ServiceContainer.start].
 */
internal suspend fun AppContainer.refreshRxLogContactDecryptKeys() {
    val services = connectionManager.services ?: return
    val radioId = services.radioId
    val contacts = loadContactsForRxLogDecrypt(
        fetch = { services.contactService.getContacts(radioId) },
        logger = rxLogLogger,
    ) ?: return
    val byPrefix = contacts
        .filter { it.publicKey.isNotEmpty() }
        .groupBy(
            keySelector = { it.publicKey[0].toInt() and 0xFF },
            valueTransform = { it.publicKey },
        )
    rxLogDecodeCache.updateContactPublicKeysIf(byPrefix) { connectionManager.services === services }
}

internal suspend fun AppContainer.onContactsChanged() {
    refreshRxLogContactDecryptKeys()
    invalidateLists()
}
