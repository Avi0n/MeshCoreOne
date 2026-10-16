package one.meshcore.android

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.connection.applyManualRadioParams
import one.meshcore.shared.connection.applyNodesAutoAdd
import one.meshcore.shared.connection.applyRadioPreset
import one.meshcore.shared.connection.applyRepeatFrequency
import one.meshcore.shared.connection.deviceOtherParamsSnapshot
import one.meshcore.shared.connection.disableClientRepeat
import one.meshcore.shared.connection.enableClientRepeat
import one.meshcore.shared.connection.radioSnapshot
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.RadioPresets
import one.meshcore.shared.settings.RegionSelectionStore

/** Persists a Settings → Radio location pick so the preset list and backup envelope stay in sync. */
suspend fun AppContainer.setRegionSelection(selection: RegionSelection) {
    RegionSelectionStore.save(prefs, selection)
    settingsSnapshots.region = selection
}

/**
 * Location-based region suggestion for the onboarding region step. The grant is re-read first:
 * the service predates the onboarding permission prompt, so its cached status is stale by design.
 */
suspend fun AppContainer.resolveRegion(): RegionSelection? {
    locationService.refreshAuthorization()
    return regionResolver.resolve()
}

/** Applies a regional radio preset over the live session and refreshes the Device row. */
suspend fun AppContainer.applyRadioPreset(preset: RadioPreset) {
    connectionManager.applyRadioPreset(preset)
    // Path editor and zero-hop ping read this cache, same as [setPathHashMode].
    connectionManager.deviceOtherParamsSnapshot()?.pathHashMode?.let { connectedPathHashMode.set(it) }
    refreshSettingsSnapshotCache(connected = true)
}

/** Applies manual LoRa parameters from Settings → Advanced. */
suspend fun AppContainer.applyManualRadioParams(params: ManualRadioParams) {
    connectionManager.applyManualRadioParams(params)
    refreshSettingsSnapshotCache(connected = true)
}

/**
 * Enables client-repeat mode at the repeat preset nearest the device's current frequency, saving
 * pre-repeat parameters for [disableClientRepeat] to restore.
 */
suspend fun AppContainer.enableClientRepeat() {
    val snapshot = connectionManager.radioSnapshot() ?: error("No radio connected")
    val preset = RadioPresets.nearestRepeatPreset(toFrequencyKHz = snapshot.frequencyKHz)
        ?: error("No repeat preset available")
    stampClientRepeatAfterVerifiedWrite(
        persist = { connectionManager.enableClientRepeat(preset.frequencyKHz) },
        publish = { clientRepeatFlow.value = it },
        enabled = true,
    )
    refreshSettingsSnapshotCache(connected = true)
}

/** Restores the radio parameters saved before [enableClientRepeat] and leaves repeat mode. */
suspend fun AppContainer.disableClientRepeat() {
    stampClientRepeatAfterVerifiedWrite(
        persist = { connectionManager.disableClientRepeat() },
        publish = { clientRepeatFlow.value = it },
        enabled = false,
    )
    refreshSettingsSnapshotCache(connected = true)
}

/** Retunes to a chosen repeat-preset frequency while already in repeat mode (frequency only). */
suspend fun AppContainer.applyRepeatPreset(preset: RadioPreset) {
    connectionManager.applyRepeatFrequency(preset.frequencyKHz)
    refreshSettingsSnapshotCache(connected = true)
}

/** Writes auto-add mode/types to the connected radio. */
suspend fun AppContainer.applyNodesAutoAdd(draft: NodesAutoAddDraft) {
    connectionManager.applyNodesAutoAdd(draft)
    refreshSettingsSnapshotCache(connected = true)
}

/**
 * Persist a verified client-repeat write, then publish so the shell antenna tints immediately.
 * Connection-state collect also refreshes this, but that only runs on the next rung change.
 */
internal suspend fun stampClientRepeatAfterVerifiedWrite(
    persist: suspend () -> Unit,
    publish: (Boolean) -> Unit,
    enabled: Boolean,
) {
    persist()
    publish(enabled)
}
