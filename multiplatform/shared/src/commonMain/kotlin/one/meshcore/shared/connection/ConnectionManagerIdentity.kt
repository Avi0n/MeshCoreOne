package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity

// Returns the generation in force once the bump has happened, before the teardown suspends.
internal suspend fun ConnectionManager.tearDownCurrent(stopSession: Boolean, supersede: Boolean = true): Long {
    // Abandoning the live connection invalidates any ceremony still building on it. Every supersede
    // path — an explicit disconnect, a second connect, a link drop, a reconnect retry — passes through
    // here, so this one bump is the whole supersede fence. Inner GATT retries keep the generation
    // (iOS `connectWithRetry` stays on the same connect(to:)).
    if (supersede) {
        connectGeneration++
        bondRefreshPersistEpoch++
        cancelBondRefreshLoop()
        initialSyncInProgress = false
    }
    val generation = connectGeneration
    val oldServices = services
    val oldSession = session
    val oldLink = link
    val teardownCancelled = captureTearDownCancellation { oldServices?.tearDown() }
    val resourcesMatch = services === oldServices && session === oldSession && link === oldLink
    var stopCancelled: CancellationException? = null
    if (ownsConnectAttempt(generation) && resourcesMatch) {
        services = null
        session = null
        link = null
        if (oldSession != null) {
            if (stopSession) stopCancelled = captureSessionStop(logger::warning) { oldSession.stop() }
            oldSession.close()
        }
        oldLink?.setListener(null)
        oldLink?.disconnect()
    }
    (teardownCancelled ?: stopCancelled)?.let { throw it }
    return generation
}

/**
 * The data partition this radio's rows belong to: the row this connection handle already has, else the
 * row whose public key matches, else a freshly minted one.
 *
 * The handle comes first because the public key is the axis that rotates — a key imported from another
 * client or a factory re-key leaves the same row reachable at the same handle. Resolving by key alone
 * would mint a second partition and [persistDevice] would write it over the row, orphaning every
 * contact, message, channel and pending send held under the old one. The key lookup stays as the
 * fallback for the handle that changed instead: a re-pair, or a row a backup import brought in.
 */
internal suspend fun ConnectionManager.resolveRadioId(rowId: Uuid, publicKey: ByteArray): Uuid {
    val byHandle = database.deviceDao().getById(rowId)
    if (byHandle != null) return byHandle.radioID
    val byPublicKey = database.deviceDao().listAll().firstOrNull { it.publicKey.contentEquals(publicKey) }
    return byPublicKey?.radioID ?: newRadioId()
}

/**
 * Local contact-table occupancy for the at-capacity prune. A count-read fault degrades to
 * incremental, matching iOS. The V-contact is excluded so it cannot trip the radio slot cap.
 */

@Suppress("TooGenericExceptionCaught")
internal suspend fun ConnectionManager.localContactTableCapacity(radioId: Uuid): ContactTableCapacity {
    val device = database.deviceDao().listByRadio(radioId).firstOrNull()
        ?: return ContactTableCapacity(realContactCount = 0, maxContacts = 0)
    return try {
        contactTableCapacity(
            maxContacts = device.maxContacts,
            localPublicKeys = database.contactDao().listByRadio(radioId).map { it.publicKey },
            selfPublicKey = device.publicKey,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.error("Failed to read local contact count for capacity check: ${error.message ?: error}")
        ContactTableCapacity(realContactCount = 0, maxContacts = device.maxContacts)
    }
}

/** The instant contacts were last synced from this radio, or null when there is no usable watermark. */
internal suspend fun ConnectionManager.contactSyncWatermark(radioId: Uuid): Long? =
    database.deviceDao().listByRadio(radioId).maxOfOrNull { it.lastContactSync }?.takeIf { it > 0 }

/**
 * Records the newest `lastModified` a contact pull returned, so the next connect can ask the radio for
 * changes since it. An empty pull reports 0 and leaves the stored watermark alone rather than resetting
 * the partition to full syncs.
 */
internal suspend fun ConnectionManager.persistContactSyncWatermark(radioId: Uuid, timestamp: Long) {
    if (timestamp <= 0) return
    for (row in database.deviceDao().listByRadio(radioId)) {
        if (row.lastContactSync < timestamp) database.deviceDao().upsert(row.copy(lastContactSync = timestamp))
    }
}

/**
 * Corrects the radio's clock when it has drifted past [ConnectionManager.DEVICE_CLOCK_DRIFT_TOLERANCE_SECONDS].
 *
 * The radio stamps every message it originates, and both the reaction-matching window and remote-node
 * login timestamps are read against those stamps, so an uncorrected drift is carried into persisted
 * data. A radio that will not answer is left alone: this runs after promotion and must never cost the
 * user a connection.
 */
internal suspend fun ConnectionManager.syncDeviceTimeIfNeeded() {
    val liveSession = session ?: return
    syncRadioClockIfNeeded(
        ConnectionClockSync(
            getTime = { liveSession.getTime() },
            setTime = { liveSession.setTime(it) },
            nowEpochSeconds = nowEpochSeconds,
            commandTimeout = ConnectionManager.DEVICE_CLOCK_SYNC_TIMEOUT,
            driftToleranceSeconds = ConnectionManager.DEVICE_CLOCK_DRIFT_TOLERANCE_SECONDS,
            logInfo = logger::info,
            logWarning = logger::warning,
        ),
    )
}

/**
 * Writes what the radio just reported onto the row it already has, leaving every other column alone.
 *
 * The update is a [DeviceEntity.copy] rather than a fresh entity because Room's upsert replaces the
 * whole row: a constructor call here would reset each column the connect path does not name — the
 * curated region list, the battery curve, the saved pre-repeat parameters, the contact-sync
 * watermark — on every link-up, reconnect included. A column added later is carried forward by the
 * copy, and [firstPairDeviceRow] is the one place that has to name it.
 */
internal suspend fun ConnectionManager.persistDevice(
    rowId: Uuid,
    radioId: Uuid,
    selfInfo: SelfInfo,
    capabilities: DeviceCapabilities,
    reachability: List<ConnectionMethod>,
) {
    val existingById = database.deviceDao().getById(rowId)
    // A new BLE handle (or an iOS-backup Device.id that is not the Kable handle) misses getById.
    // The public-key row still holds lastContactSync, knownRegions, flood default, OCV, and
    // pre-repeat — iOS createDevice uses that row as existingDevice before deleting the old id.
    val existingByPublicKey = if (existingById == null) {
        database.deviceDao().listAll().firstOrNull { row ->
            row.publicKey.contentEquals(selfInfo.publicKey)
        }
    } else {
        null
    }
    val existing = existingById ?: existingByPublicKey
    val autoAdd = readAutoAddConfig(capabilities.version, storedAutoAddConfig(existing))
    // The row id is derived from the transport handle (BLE) or the public key (WiFi); radioID is the
    // stable partition key resolved above. The two never collapse (three-axis identity).
    val base = existing ?: firstPairDeviceRow(rowId, radioId, selfInfo.publicKey)
    val updated = base.copy(
        id = rowId,
        radioID = radioId,
        publicKey = selfInfo.publicKey,
        nodeName = selfInfo.name,
        firmwareVersion = capabilities.firmwareVersion,
        firmwareVersionString = capabilities.version,
        manufacturerName = capabilities.model,
        buildDate = capabilities.firmwareBuild,
        maxContacts = capabilities.maxContacts,
        maxChannels = capabilities.maxChannels,
        frequency = (selfInfo.radioFrequency * ConnectionManager.KHZ_PER_MHZ).toLong(),
        bandwidth = (selfInfo.radioBandwidth * ConnectionManager.HZ_PER_KHZ).toLong(),
        spreadingFactor = selfInfo.radioSpreadingFactor,
        codingRate = selfInfo.radioCodingRate,
        txPower = selfInfo.txPower,
        maxTxPower = selfInfo.maxTxPower,
        latitude = selfInfo.latitude,
        longitude = selfInfo.longitude,
        blePin = capabilities.blePin,
        clientRepeat = capabilities.clientRepeat,
        pathHashMode = capabilities.pathHashMode,
        manualAddContacts = selfInfo.manualAddContacts,
        autoAddConfig = autoAdd.bitmask,
        autoAddMaxHops = autoAdd.maxHops,
        multiAcks = selfInfo.multiAcks,
        telemetryModeBase = selfInfo.telemetryModeBase,
        telemetryModeLoc = selfInfo.telemetryModeLocation,
        telemetryModeEnv = selfInfo.telemetryModeEnvironment,
        advertLocationPolicy = selfInfo.advertisementLocationPolicy,
        lastConnected = nowEpochSeconds(),
        isActive = true,
        // A radio whose pack is not a single Li-Ion cell reads full forever on the default curve, so
        // the low-battery alert never fires. Seed the model's curve, but only onto a row that has
        // none: a curve the user picked in Settings outranks the detected one.
        ocvPreset = base.ocvPreset ?: OCVPreset.presetForManufacturer(capabilities.model)?.rawValue,
        // Merge the fresh reachability descriptor onto whatever the row already had, replacing by
        // transport type so a radio reachable both ways keeps both descriptors.
        connectionMethods = mergeConnectionMethods(base.connectionMethods, reachability),
    )
    database.deviceDao().upsert(updated.withoutOrphanedPreRepeatParams(capabilities.clientRepeat))
    // iOS deletes the public-key twin only after a handle miss (`deviceByPublicKey`). A handle
    // hit must keep a backup shadow so later identity reconcile can still adopt its radioID.
    if (existingByPublicKey != null && existingByPublicKey.id != rowId) {
        database.deviceDao().deleteById(existingByPublicKey.id)
    }
}

/**
 * The auto-add policy the Device row already holds, or the firmware default for a first-ever pairing.
 */
internal fun ConnectionManager.storedAutoAddConfig(existing: DeviceEntity?): AutoAddConfig = AutoAddConfig(
    bitmask = existing?.autoAddConfig ?: ConnectionManager.DEFAULT_AUTO_ADD_CONFIG,
    maxHops = existing?.autoAddMaxHops ?: ConnectionManager.DEFAULT_AUTO_ADD_MAX_HOPS,
)
