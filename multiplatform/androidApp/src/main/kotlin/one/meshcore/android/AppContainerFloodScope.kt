package one.meshcore.android

import one.meshcore.shared.channel.ChannelFloodScopeResolver
import one.meshcore.shared.channel.FloodScopePush
import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.channel.RegionDiscoveryService
import one.meshcore.shared.channel.setDefaultFloodScopeVerified
import one.meshcore.shared.connection.AnsweredRadioRead
import one.meshcore.shared.connection.FAILED_PERSIST_DEFAULT_FLOOD_SCOPE
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.persistDeviceSettingsAfterRadioAck
import one.meshcore.shared.connection.radioSnapshot
import one.meshcore.shared.connection.readAnsweredRadioCommand
import one.meshcore.shared.connection.refreshDeviceSettings
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.persistence.floodScopeDeviceInputs
import one.meshcore.shared.persistence.runRoom
import one.meshcore.shared.persistence.setDeviceDefaultFloodScopeName

internal suspend fun AppContainer.clearRxLogDecryptMaterial() {
    rxLogDecodeCache.updatePrivateKey(null)
    rxLogDecodeCache.updateContactPublicKeys(emptyMap())
    rxLogDecodeCache.updateKnownRegions(emptyList())
}
internal suspend fun AppContainer.clearConnectedFloodScopeInputs() {
    floodScopePublicationGate.clear {
        connectedKnownRegions.set(emptyList())
        connectedDefaultFloodScopeName.value = null
        connectedSupportsUnscopedFloodSend.set(false)
    }
}

/**
 * Pushes the session-scoped flood key for a channel's chosen scope after the local preference persists,
 * mirroring the iOS ChannelInfoSheet.selectFloodScope tail. A missing session or failed push is
 * best-effort: the preference is already saved and the send path still works on the prior scope.
 */
internal suspend fun AppContainer.pushChannelFloodScope(scope: ChannelFloodScope, owner: ServiceContainer) {
    if (connectionManager.services !== owner) return
    val session = owner.session
    val resolved = ChannelFloodScopeResolver.resolve(
        channelFloodScope = scope,
        deviceDefaultFloodScopeName = connectedDefaultFloodScopeName.value,
        supportsUnscopedFloodSend = connectedSupportsUnscopedFloodSend.get(),
    )
    // The preference is already saved. A failed push leaves the radio on its prior scope.
    FloodScopePush.push(session, resolved, connectionLogger::warning)
}

/**
 * Load Device.knownRegions / defaultFloodScopeName for the live radio partition so channel-info
 * flood-scope picker, RX-log region resolution, and inherit flood push see the same app-owned
 * lists as iOS. Called at Connected so the default is present before Ready drain.
 */
internal suspend fun AppContainer.refreshConnectedFloodScopeInputs() {
    val radioId = connectionManager.radioId
    if (radioId == null) {
        clearConnectedFloodScopeInputs()
        rxLogDecodeCache.updateKnownRegions(emptyList())
        return
    }
    val generation = floodScopePublicationGate.capture()
    val inputs = runRoom(
        connectionLogger::warning,
        FLOOD_SCOPE_ROOM_FAILED,
        fallback = { null },
    ) {
        floodScopeDeviceInputs(database, radioId)
    } ?: run {
        clearConnectedFloodScopeInputs()
        rxLogDecodeCache.updateKnownRegions(emptyList())
        return
    }
    floodScopePublicationGate.publishLoaded(generation, radioId, currentRadio = { connectionManager.radioId }) {
        connectedKnownRegions.set(inputs.knownRegions)
        connectedDefaultFloodScopeName.value = inputs.defaultFloodScopeName
        connectedSupportsUnscopedFloodSend.set(inputs.supportsUnscopedFloodSend)
        rxLogDecodeCache.updateKnownRegions(inputs.knownRegions)
    }
}

/**
 * Channel-info "Discover nearby regions": DISCOVER_REQ + requestRegions, returning the newly found
 * names for the discovery-results multi-select. The user picks which to add; persistence happens in
 * [addKnownRegion] on confirm, matching the iOS results screen (no auto-add).
 */
suspend fun AppContainer.runRegionDiscoveryForChannelInfo(): RegionDiscoverUiResult {
    val session = connectionManager.services?.session ?: return RegionDiscoverUiResult.SendFailed
    val radioId = connectionManager.radioId ?: return RegionDiscoverUiResult.SendFailed
    val contactService = connectionManager.services?.contactService
        ?: return RegionDiscoverUiResult.ErrorLoading
    val outcome = RegionDiscoveryService.discover(
        RegionDiscoveryService.DiscoverRequest(
            session = session,
            radioID = radioId,
            knownRegions = connectedKnownRegions.get(),
            // A radio older than the ad-hoc request rejects an anonymous query to a non-contact key, so
            // an unconditional true would send requests that can only fail.
            supportsAdHocRequest = connectionManager.radioSnapshot()?.supportsAdHocRepeaterRequest == true,
            loadContacts = { id -> contactService.getContacts(id) },
            loadDiscoveredNodes = { id -> discoveredNodeStoreImpl.list(id) },
        ),
    )
    return when (outcome) {
        RegionDiscoveryService.Outcome.SendFailed -> RegionDiscoverUiResult.SendFailed
        RegionDiscoveryService.Outcome.NoRepeatersResponded -> RegionDiscoverUiResult.NoRepeaters
        RegionDiscoveryService.Outcome.ErrorLoadingRepeaters -> RegionDiscoverUiResult.ErrorLoading
        is RegionDiscoveryService.Outcome.Completed ->
            RegionDiscoverUiResult.Completed(
                newRegions = outcome.newRegions,
                allTableFull = outcome.allRepeatersTableFull,
            )
    }
}

/**
 * The radio-owned settings the Radio, Advanced and Location screens render, re-read when one of them
 * appears. Mirrors the iOS settings startup reads: the self-info half updates the Device row, and the
 * default flood scope is taken from the radio itself on the firmware that persists one — without that
 * read the section would offer a scope while showing whatever the app last wrote.
 */
suspend fun AppContainer.refreshDeviceSettings() {
    connectionManager.refreshDeviceSettings()
    refreshDefaultFloodScopeFromRadio()
    refreshSettingsSnapshotCache(connected = true)
}
internal suspend fun AppContainer.refreshDefaultFloodScopeFromRadio() {
    val session = connectionManager.services?.session
    val radioId = connectionManager.radioId
    val supported = connectionManager.radioSnapshot()?.supportsDefaultFloodScope == true
    if (session == null || radioId == null || !supported) return
    floodScopePublicationGate.invalidate()
    // An unanswered command is not a cleared scope, so only a completed read rewrites the name;
    // a null answer from a radio that did reply means the scope really is unset.
    val name = when (
        val read = readAnsweredRadioCommand(
            connectionLogger::warning,
            DEFAULT_FLOOD_SCOPE_READ_FAILED,
        ) { session.getDefaultFloodScope() }
    ) {
        AnsweredRadioRead.Failed -> return
        is AnsweredRadioRead.Value -> read.value?.name
    }
    persistDeviceSettingsAfterRadioAck(connectionLogger, FAILED_PERSIST_DEFAULT_FLOOD_SCOPE) {
        setDeviceDefaultFloodScopeName(database, radioId, name)
    }
    floodScopePublicationGate.publishMutation(radioId, currentRadio = { connectionManager.radioId }) {
        connectedDefaultFloodScopeName.value = name
    }
}

/** Writes and verifies the device's default flood scope, persisting the display name on success. */
suspend fun AppContainer.applyDefaultFloodScope(name: String?) {
    val session = connectionManager.services?.session ?: error("No radio connected")
    val radioId = connectionManager.radioId ?: error("No radio connected")
    floodScopePublicationGate.invalidate()
    val actual = setDefaultFloodScopeVerified(session, name)
    persistDeviceSettingsAfterRadioAck(connectionLogger, FAILED_PERSIST_DEFAULT_FLOOD_SCOPE) {
        setDeviceDefaultFloodScopeName(database, radioId, actual)
    }
    floodScopePublicationGate.publishMutation(radioId, currentRadio = { connectionManager.radioId }) {
        connectedDefaultFloodScopeName.value = actual
    }
}

private const val FLOOD_SCOPE_ROOM_FAILED = "Flood scope Room read failed"
private const val DEFAULT_FLOOD_SCOPE_READ_FAILED = "Default flood scope read failed"
