package one.meshcore.shared.persistence

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid

/** A named seed radio with empty firmware fields, so a connection test can vary only the methods. */
internal fun seedDevice(id: Uuid, radioID: Uuid, publicKey: ByteArray, methods: List<ConnectionMethod>): DeviceEntity =
    DeviceEntity(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        nodeName = "seed",
        firmwareVersion = 0,
        firmwareVersionString = "",
        manufacturerName = "",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
        connectionMethods = methods,
    )
