package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid

/** Persistence mirror of the SwiftData `Device` model. `radioID` is the data-partition key. */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val nodeName: String,
    val firmwareVersion: Int,
    val firmwareVersionString: String,
    val manufacturerName: String,
    val buildDate: String,
    val maxContacts: Int,
    val maxChannels: Int,
    val frequency: Long,
    val bandwidth: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val latitude: Double,
    val longitude: Double,
    val blePin: Long,
    val clientRepeat: Boolean,
    val pathHashMode: Int,
    val manualAddContacts: Boolean,
    val autoAddConfig: Int,
    val autoAddMaxHops: Int,
    val multiAcks: Int,
    val telemetryModeBase: Int,
    val telemetryModeLoc: Int,
    val telemetryModeEnv: Int,
    val advertLocationPolicy: Int,
    val lastConnected: Double,
    val lastContactSync: Long,
    val isActive: Boolean,
    val connectionMethods: List<ConnectionMethod> = emptyList(),
    val knownRegions: List<String> = emptyList(),
    val defaultFloodScopeName: String? = null,
    val preRepeatFrequency: Long? = null,
    val preRepeatBandwidth: Long? = null,
    val preRepeatSpreadingFactor: Int? = null,
    val preRepeatCodingRate: Int? = null,
    val ocvPreset: String? = null,
    val customOCVArrayString: String? = null,
    val appliedRadioPresetID: String? = null,
)
