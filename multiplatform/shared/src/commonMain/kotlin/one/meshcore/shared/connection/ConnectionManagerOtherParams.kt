package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.DeviceOtherParams
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot

/**
 * Telemetry-sharing / multi-ack / path-hash fields for Settings → Advanced. `null` when
 * disconnected or the Device row is missing.
 */
suspend fun ConnectionManager.deviceOtherParamsSnapshot(): DeviceOtherParamsSnapshot? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    val device = database.deviceDao().getById(deviceId) ?: return null
    return DeviceOtherParamsSnapshot(
        telemetryModeBase = device.telemetryModeBase,
        telemetryModeLocation = device.telemetryModeLoc,
        telemetryModeEnvironment = device.telemetryModeEnv,
        multiAcks = device.multiAcks,
        pathHashMode = device.pathHashMode,
        firmwareVersion = device.firmwareVersion,
    )
}

/**
 * Commits telemetry-sharing modes (base/location/environment) via setOtherParams, preserving the
 * other other-params fields from the live SelfInfo / Device row, then persists them on the Device
 * row. Mirrors iOS `TelemetrySettingsSection.saveTelemetry`.
 */
suspend fun ConnectionManager.setTelemetryModes(base: Int, location: Int, environment: Int) {
    writeOtherParams { existing, info ->
        ConnectionManager.OtherParamsWrite(
            params = OtherParams.live(existing, info).copy(
                telemetryModeEnvironment = environment,
                telemetryModeLocation = location,
                telemetryModeBase = base,
            ),
            owned = OtherParams::describeTelemetry,
            persist = {
                it.copy(telemetryModeBase = base, telemetryModeLoc = location, telemetryModeEnv = environment)
            },
        )
    }
}

/**
 * Commits the direct-message acknowledgment count (wire value: 0 or 1) via setOtherParams,
 * preserving the other other-params fields, then persists it. Mirrors iOS
 * `DirectMessagesSettingsSection.saveMultiAcks`.
 */
suspend fun ConnectionManager.setMultiAcks(value: Int) {
    writeOtherParams { existing, info ->
        ConnectionManager.OtherParamsWrite(
            params = OtherParams.live(existing, info).copy(multiAcks = value),
            owned = OtherParams::describeMultiAcks,
            persist = { it.copy(multiAcks = value) },
        )
    }
}

/**
 * Sets the routing key-prefix hash size (firmware v10+) on the live radio, proves the radio adopted
 * it, and persists it on the Device row. Mirrors iOS `SettingsService.setPathHashModeVerified`.
 *
 * The read-back is what keeps a refused write from widening the app's cached hop-hash width: the path
 * editor and the zero-hop ping encode at that width, so a mode the radio never took would make every
 * path the app builds unroutable by the radio that has to route it.
 */
suspend fun ConnectionManager.setPathHashMode(mode: Int) {
    val liveSession = session ?: error("No radio connected")
    liveSession.setPathHashMode(mode)
    val reported = liveSession.queryDevice().pathHashMode
    if (reported != mode) throw PathHashModeVerificationFailed(expected = mode, actual = reported)
    val deviceId = lastConnectionStore?.deviceId() ?: return
    val existing = database.deviceDao().getById(deviceId) ?: return
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_PATH_HASH_MODE) {
        database.deviceDao().upsert(existing.copy(pathHashMode = mode))
    }
}

/**
 * Sends one other-params frame and persists it only after the radio reads back the fields the write
 * owns. The frame carries all six fields at once, so it is always built from the radio's live values
 * with the Device row as the per-field fallback. A refused self-info read is logged and uses that
 * fallback. Without the read-back a refused write would be reported as applied, because a radio that
 * rejects one answers with its unchanged settings rather than an error. Only the owned fields are
 * compared: the rest are carried, not requested.
 */
internal suspend fun ConnectionManager.writeOtherParams(
    build: (existing: DeviceEntity, info: SelfInfo?) -> ConnectionManager.OtherParamsWrite,
) {
    val liveSession = session ?: error("No radio connected")
    val deviceId = lastConnectionStore?.deviceId() ?: error("No radio connected")
    val existing = database.deviceDao().getById(deviceId) ?: error("No device row")
    val info = readReportedSelfInfo(logger::warning) { liveSession.getSelfInfo() }
    val write = build(existing, info)
    val requested = write.params
    liveSession.setOtherParams(
        DeviceOtherParams(
            manualAddContacts = requested.manualAddContacts,
            telemetryModeEnvironment = requested.telemetryModeEnvironment,
            telemetryModeLocation = requested.telemetryModeLocation,
            telemetryModeBase = requested.telemetryModeBase,
            advertisementLocationPolicy = requested.advertisementLocationPolicy,
            multiAcks = requested.multiAcks,
        ),
    )
    val reported = OtherParams.reportedBy(liveSession.getSelfInfo())
    if (write.owned(requested) != write.owned(reported)) {
        throw OtherParamsVerificationFailed(write.owned(requested), write.owned(reported))
    }
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_OTHER_PARAMS) {
        database.deviceDao().upsert(write.persist(existing))
    }
}
