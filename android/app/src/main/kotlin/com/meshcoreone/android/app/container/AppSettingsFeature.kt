// AndroidOnly: WP-407 Settings adapters over the process preferences/store and current connection-owned services.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.*
import com.meshcoreone.android.core.services.device.RadioPreset
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.core.services.device.RegionalAreas
import com.meshcoreone.android.core.services.device.RegionDiscoveryService
import com.meshcoreone.android.core.services.device.SettingsService
import com.meshcoreone.android.core.ui.percentage
import com.meshcoreone.android.feature.settings.device.*
import com.meshcoreone.android.feature.settings.device.GeneratedIdentity
import com.meshcoreone.android.feature.settings.app.nodeconfig.*
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.core.services.remote.NodeConfigService
import com.meshcoreone.android.core.services.remote.ImportStep
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select

class AppSettingsFeature private constructor(
    internal val container: AppContainer,
    private val storage: MeshCoreStorage,
    private val initial: PreferenceSnapshot,
) {
    private val scope get() = container.uiScope
    private val app get() = container.appState
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val preferences = MutableStateFlow(initial)
    internal val preferenceValues: StateFlow<PreferenceSnapshot> get() = preferences
    internal fun setPreference(key: PreferenceKey.BooleanKey, value: Boolean) {
        write { storage.preferences.set(key, value) }
    }
    private val deviceValues = mutableMapOf<UUID, DevicePreferenceSnapshot>()
    val failure = MutableStateFlow<Throwable?>(null)
    private val changes = combine(app.servicesVersion, app.sessionStateChangeCount, container.connectionManager.snapshot) { _, _, _ -> Unit }
    private val device = changes.flatMapLatest {
        val current = app.connectedDevice
        if (current == null) flowOf(null) else storage.devicePreferences.observe(current.id).map { value ->
            deviceValues[current.id] = value
            current
        }
    }.catch { report("Device preferences could not load", it); emit(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            for (write in writes) {
                try { write() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { report("Settings persistence failed", problem) }
            }
        }
        scope.launch {
            storage.preferences.snapshots.catch { report("Settings preferences could not load", it) }
                .collect { preferences.value = it }
        }
    }

    internal fun report(operation: String, problem: Throwable?) {
        Logger.getLogger("com.mc1.Settings").log(Level.WARNING, operation, problem)
        if (problem != null) failure.value = problem
    }

    private fun write(action: suspend () -> Unit) { writes.trySend(action).getOrThrow() }
    private fun live(): RadioSessionContainer = container.sessions.current?.takeIf {
        app.canRunSettingsStartupReads && it.token.radioId == app.currentRadioId
    } ?: throw SettingsNotConnectedException()

    private var configHolder: NodeConfigStateHolder? = null

    internal fun nodeConfig(resources: android.content.res.Resources): NodeConfigStateHolder =
        configHolder ?: NodeConfigStateHolder(scope, {
            val connection = live()
            val token = connection.token
            object : NodeConfigSession {
                override val deviceName = app.connectedDevice?.nodeName.orEmpty()
                override fun requireCurrent() {
                    if (container.sessions.current !== connection || connection.token != token ||
                        !container.connectionManager.snapshot.value.state.isConnected) throw SettingsNotConnectedException()
                }
                private suspend fun <T> bound(action: suspend () -> T): T = coroutineScope {
                    requireCurrent()
                    val work = async { action() }
                    val lost = async {
                        changes.first {
                            container.sessions.current !== connection || connection.token != token ||
                                !container.connectionManager.snapshot.value.state.isConnected
                        }
                    }
                    try {
                        select<T> { work.onAwait { it }; lost.onAwait { throw SettingsNotConnectedException() } }.also { requireCurrent() }
                    } finally {
                        work.cancel()
                        lost.cancel()
                    }
                }
                override suspend fun export(sections: ConfigSections) = bound { connection.nodeConfigService.exportConfig(sections) }
                override suspend fun current() = bound {
                    val info = connection.settingsService.getSelfInfo()
                    MeshCoreNodeConfig(name = info.name, radioSettings = NodeConfigService.buildRadioSettings(info),
                        positionSettings = MeshCoreNodeConfig.PositionSettings(info.latitude.toString(), info.longitude.toString()))
                }
                override suspend fun preview(config: MeshCoreNodeConfig, sections: ConfigSections) =
                    bound { connection.nodeConfigService.previewImport(config, sections).channelsOverwriteExisting }
                override suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit) = bound {
                    connection.nodeConfigService.importConfig(config, sections, token.radioId) {
                        val description = when (val step = it.step) {
                            ImportStep.Position -> resources.getString(S.configImportStepPosition)
                            ImportStep.OtherParameters -> resources.getString(S.configImportStepOtherParameters)
                            ImportStep.PrivateKey -> resources.getString(S.configImportStepPrivateKey)
                            ImportStep.NodeName -> resources.getString(S.configImportStepNodeName)
                            ImportStep.RadioParameters -> resources.getString(S.configImportStepRadioParameters)
                            ImportStep.TxPower -> resources.getString(S.configImportStepTxPower)
                            is ImportStep.Channel -> S.configImportStepChannel(resources, step.name)
                            is ImportStep.Contact -> S.configImportStepContact(resources, step.name)
                        }
                        progress(NodeConfigProgress(it.current, it.total, description))
                    }
                }
                override suspend fun refresh() = bound { connection.settingsService.refreshDeviceInfo(); Unit }
            }
        }, object : NodeConfigCodec {
            override fun decode(bytes: ByteArray) = NodeConfigJson.decode(bytes)
            override fun encode(config: MeshCoreNodeConfig) = NodeConfigJson.encodeToBytes(config)
        }, ResourceNodeConfigMessages(resources)).also { configHolder = it }

    val catalog = object : RadioCatalogPort {
        private fun RadioPreset.option() = RadioPresetOption(id, name, region.rawValue, repeatSectionHeader)
        override fun visiblePresets(region: RegionSelection?, activeId: String?) =
            RadioPresets.visiblePresets(region, activeId).map { it.option() }
        override val repeatPresets get() = RadioPresets.repeatPresets.map { it.option() }
        override fun resolvedPreset(device: DeviceDTO, region: RegionSelection?) =
            RadioPresets.resolvedPreset(device.frequency, device.bandwidth, device.spreadingFactor, device.codingRate,
                device.appliedRadioPresetID, region)?.option()
        override fun matchingPresetIds(device: DeviceDTO) =
            RadioPresets.matchingPresets(device.frequency, device.bandwidth, device.spreadingFactor, device.codingRate).map { it.id }.toSet()
        override fun nearestRepeatPreset(frequencyKHz: UInt) = RadioPresets.nearestRepeatPreset(frequencyKHz)?.option()
        override fun matchingRepeatPreset(frequencyKHz: UInt) = RadioPresets.matchingRepeatPreset(frequencyKHz)?.option()
        override fun repeatFrequencyKHz(presetId: String) = RadioPresets.repeatPresets.firstOrNull { it.id == presetId }?.frequencyKHz
        override fun regionDisplayName(region: RegionSelection) = RegionalAreas.displayName(region)
    }

    val regions = object : RegionSelectionPort {
        override val selection get() = app.regionSelectionFlow
        override fun set(selection: RegionSelection?) { app.regionSelection = selection }
    }

    fun dependencies(
        notifications: NotificationPermissionPort,
        location: LocationPermissionPort,
        navigator: SettingsNavigator,
        diagnostics: DiagnosticsPort,
    ): SettingsFeatureDependencies = SettingsFeatureDependencies(
        connection = object : SettingsConnection {
            override val connectedDevice = device
            override val connectionState = container.connectionManager.snapshot.map { it.state }
                .stateIn(scope, SharingStarted.WhileSubscribed(), app.connectionState)
            override val startupReadsAllowed = changes.map { app.canRunSettingsStartupReads }
                .stateIn(scope, SharingStarted.WhileSubscribed(), app.canRunSettingsStartupReads)
        },
        settingsService = { container.sessions.current?.takeIf { app.canRunSettingsStartupReads }?.let { SettingsRadioAdapter(it.settingsService) } },
        radioCatalog = catalog,
        subdivisions = SubdivisionCatalog(RegionalAreas::showsSubdivisionPicker),
        regions = regions,
        regionLookup = RegionLookupPort { container.settingsRegionResolver?.resolve() },
        devicePreferences = object : DevicePreferencePort {
            private fun value(id: UUID) = checkNotNull(deviceValues[id]) { "Device preferences have not loaded" }
            override fun isAutoUpdateLocationEnabled(deviceId: UUID) = value(deviceId).autoUpdateLocation
            override fun gpsSource(deviceId: UUID) = GpsSource.valueOf(value(deviceId).gpsSource.name)
            override fun hasSetGpsSource(deviceId: UUID) = value(deviceId).hasSetGPSSource
            override fun setAutoUpdateLocationEnabled(enabled: Boolean, deviceId: UUID) {
                write {
                    storage.devicePreferences.setAutoUpdateLocationEnabled(enabled, deviceId)
                    deviceValues[deviceId] = storage.devicePreferences.observe(deviceId).first()
                }
            }
            override fun setGpsSource(source: GpsSource, deviceId: UUID) {
                val mapped = GPSSource.valueOf(source.name)
                write {
                    storage.devicePreferences.setGPSSource(mapped, deviceId)
                    deviceValues[deviceId] = storage.devicePreferences.observe(deviceId).first()
                }
            }
        },
        location = location,
        notificationPreferences = container.notificationPreferences,
        notificationPermission = notifications,
        discoveryChoices = DiscoveryChoiceSource {
            StoredDiscoveryChoices(preferences.value.contains(AppStorageKey.notifyNewContactsContact),
                preferences.value.contains(AppStorageKey.notifyNewContactsRepeater),
                preferences.value.contains(AppStorageKey.notifyNewContactsRoom))
        },
        maintenance = object : DeviceMaintenancePort {
            override suspend fun forgetDevice(deleteData: Boolean) { container.pairing.forgetDevice(deleteData) }
            override suspend fun forgetDevice(id: UUID) { container.pairing.forgetDevice(id) }
            override suspend fun unfavoritedNodeCount() = container.deviceEditor.unfavoritedNodeCount()
            override suspend fun removeUnfavoritedNodes() = container.deviceEditor.removeUnfavoritedNodes().let {
                RemoveUnfavoritedResult(it.removed.toLong(), it.total.toLong())
            }
        },
        deviceStore = object : DeviceSettingsStorePort {
            override suspend fun updateOcvSettings(deviceId: UUID, preset: String, customArray: String?) {
                live().deviceService.updateOCVSettings(deviceId, preset, customArray)
            }
            override fun savePreRepeatSettings() { container.deviceEditor.savePreRepeatSettings() }
            override fun clearPreRepeatSettings() { container.deviceEditor.clearPreRepeatSettings() }
            override fun addKnownRegion(region: String) { container.deviceEditor.addKnownRegion(region) }
            override fun removeKnownRegion(region: String) { container.deviceEditor.removeKnownRegion(region) }
        },
        diagnostics = diagnostics,
        identityKeys = object : IdentityKeyPort {
            override fun isValidExpandedKey(key: Bytes): Boolean = try {
                KeyGenerationService.validateExpandedKey(key); true
            } catch (_: KeyGenerationFailure.InvalidKey) { false }
            override suspend fun generateIdentity(hexPrefix: String?): GeneratedIdentity {
                val key = KeyGenerationService().generateIdentity(hexPrefix)
                return GeneratedIdentity(key.publicKey, key.expandedPrivateKey)
            }
        },
        nodeIdentity = {
            container.sessions.current?.takeIf { app.canRunSettingsStartupReads }?.let { current ->
                object : NodeIdentityPort { override suspend fun setNodeNameVerified(name: String) = current.settingsService.setNodeNameVerified(name) }
            }
        },
        battery = object : DeviceBatteryPort {
            override val battery = app.batteryMonitor.deviceBatteryFlow.map { info ->
                info?.let { DeviceBatterySnapshot(it.level.toDouble() / 1000,
                    it.percentage(app.batteryMonitor.activeBatteryOcvArray(app.connectedDevice)).toInt(),
                    it.usedStorageKB?.let(Math::toIntExact), it.totalStorageKB?.let(Math::toIntExact)) }
            }.stateIn(scope, SharingStarted.WhileSubscribed(), null)
            override suspend fun fetch() { app.batteryMonitor.deviceBattery = live().settingsService.getBattery() }
        },
        staleCleanup = object : StaleNodeCleanupPort {
            override val thresholdDays = preferences.map { Math.toIntExact(it[AppStorageKey.autoDeleteStaleNodesDays]) }
                .stateIn(scope, SharingStarted.WhileSubscribed(), Math.toIntExact(preferences.value[AppStorageKey.autoDeleteStaleNodesDays]))
            override val lastCleanup = preferences.map { values ->
                values[AppStorageKey.lastStaleCleanupDate].takeIf { it > 0 }?.let(DataStoreStaleCleanupPreferences::fromReferenceSeconds)
            }.stateIn(scope, SharingStarted.WhileSubscribed(), null)
            override fun setThresholdDays(days: Int) { write { storage.preferences.set(AppStorageKey.autoDeleteStaleNodesDays, days.toLong()) } }
            override fun performCleanup(force: Boolean) { app.performStaleNodeCleanup(force) }
        },
        regionDiscovery = RegionDiscoveryPort { known, supports ->
            when (val result = live().regionDiscoveryService.discover(known, supports)) {
                is RegionDiscoveryService.Outcome.SendFailed -> RegionDiscoveryOutcome.SendFailed
                RegionDiscoveryService.Outcome.NoRepeatersResponded -> RegionDiscoveryOutcome.NoRepeatersResponded
                is RegionDiscoveryService.Outcome.ErrorLoadingRepeaters -> RegionDiscoveryOutcome.ErrorLoadingRepeaters
                is RegionDiscoveryService.Outcome.Completed -> {
                    if (result.isPartial) report("Some repeater region queries failed: ${result.queryFailures.size}", null)
                    RegionDiscoveryOutcome.Completed(result.newRegions, result.allRepeatersTableFull)
                }
            }
        },
        chatPreferences = object : ChatPreferencePort {
            override val values = preferences.map { values -> CHAT_KEYS.mapValues { (_, key) -> values[key] } }
                .stateIn(scope, SharingStarted.WhileSubscribed(), CHAT_KEYS.mapValues { (_, key) -> preferences.value[key] })
            override fun set(preference: ChatPreference, value: Boolean) { write { storage.preferences.set(CHAT_KEYS.getValue(preference), value) } }
        },
        navigator = navigator,
    )

    companion object {
        private val CHAT_KEYS = mapOf(
            ChatPreference.REPLY_WITH_QUOTE to AppStorageKey.replyWithQuote,
            ChatPreference.SHOW_INCOMING_PATH to AppStorageKey.showIncomingPath,
            ChatPreference.SHOW_INCOMING_HOP_COUNT to AppStorageKey.showIncomingHopCount,
            ChatPreference.SHOW_INCOMING_REGION to AppStorageKey.showIncomingRegion,
            ChatPreference.SHOW_INCOMING_HEARD_COUNT to AppStorageKey.showIncomingHeardCount,
        )
        suspend fun create(container: AppContainer, storage: MeshCoreStorage) =
            AppSettingsFeature(container, storage, storage.preferences.snapshot())
    }
}

private class SettingsRadioAdapter(private val service: SettingsService) : SettingsRadioPort {
    override suspend fun getSelfInfo() = service.getSelfInfo()
    override suspend fun setRadioParamsVerified(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte,
        codingRate: UByte, clientRepeat: Boolean?, appliedRadioPresetID: String?) =
        service.setRadioParamsVerified(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, clientRepeat, appliedRadioPresetID)
    override suspend fun setTxPowerVerified(power: Byte) = service.setTxPowerVerified(power)
    override suspend fun applyRadioPresetVerified(presetId: String) =
        service.applyRadioPresetVerified(requireNotNull((RadioPresets.all + RadioPresets.repeatPresets).firstOrNull { it.id == presetId }))
    override suspend fun setOtherParamsVerified(device: DeviceDTO, autoAddContacts: Boolean?, telemetryModes: TelemetryModes?,
        advertLocationPolicy: AdvertLocationPolicy?, multiAcks: UByte?) =
        service.setOtherParamsVerified(device, autoAddContacts, telemetryModes, advertLocationPolicy, multiAcks)
    override suspend fun setAutoAddConfigVerified(config: AutoAddConfig) = service.setAutoAddConfigVerified(config)
    override suspend fun refreshAutoAddConfig() = service.refreshAutoAddConfig()
    override suspend fun setPathHashModeVerified(mode: UByte) = service.setPathHashModeVerified(mode)
    override suspend fun getDeviceGPSState() = service.getDeviceGPSState().let { DeviceGpsState(it.isSupported, it.isEnabled) }
    override suspend fun setDeviceGPSEnabledVerified(enabled: Boolean) =
        service.setDeviceGPSEnabledVerified(enabled).let { DeviceGpsState(it.isSupported, it.isEnabled) }
    override suspend fun refreshDeviceInfo() = service.refreshDeviceInfo()
    override suspend fun getDefaultFloodScope() = service.getDefaultFloodScope()
    override suspend fun setDefaultFloodScopeVerified(name: String?) = service.setDefaultFloodScopeVerified(name)
    override suspend fun setBlePin(pin: UInt) = expectedReboot { service.setBlePin(pin) }
    override suspend fun reboot() = expectedReboot { service.reboot() }
    override suspend fun factoryReset() = expectedReboot { service.factoryReset() }
    override suspend fun importPrivateKey(key: Bytes) = service.importPrivateKey(key)

    private suspend fun <T> expectedReboot(operation: suspend () -> T): T = try {
        operation()
    } catch (failure: com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException) {
        val error = failure.error
        if (error is com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError.SessionError &&
            error.error is com.meshcoreone.android.core.protocol.config.MeshCoreException.Timeout
        ) throw SettingsOperationTimeoutException().also { it.initCause(failure) }
        throw failure
    }
}
