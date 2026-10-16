package one.meshcore.shared.settings

import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.meshcore.SelfInfo
import kotlin.math.roundToLong

private const val KHZ_PER_MHZ = 1000.0
private const val HZ_PER_KHZ = 1000.0

/** Whether the node name differs from the truncated name the device already stores. */
fun nodeNameNeedsWrite(name: String, current: SelfInfo): Boolean =
    name.utf8BytePrefix(NodeConfigLimits.MAX_USABLE_NAME_BYTES) != current.name

/** Whether either coordinate differs from the integer the device persists. */
fun locationNeedsWrite(position: ConfigImportPlan.Coordinate, current: SelfInfo): Boolean =
    NodeConfigLimits.scaledCoordinate(position.latitude) != NodeConfigLimits.scaledCoordinate(current.latitude) ||
        NodeConfigLimits.scaledCoordinate(position.longitude) != NodeConfigLimits.scaledCoordinate(current.longitude)

/** Whether any radio parameter other than TX power (gated separately) differs from the device. */
fun radioParamsNeedWrite(radio: MeshCoreNodeConfig.RadioSettings, current: SelfInfo): Boolean {
    val expected = buildRadioSettings(current).copy(txPower = radio.txPower)
    return radio != expected
}

/** Whether TX power differs from the device. */
fun txPowerNeedsWrite(radio: MeshCoreNodeConfig.RadioSettings, current: SelfInfo): Boolean =
    radio.txPower != current.txPower.toByte()

/** Builds radio settings from a [SelfInfo], scaling MHz→kHz and kHz→Hz with rounding. */
fun buildRadioSettings(info: SelfInfo): MeshCoreNodeConfig.RadioSettings = MeshCoreNodeConfig.RadioSettings(
    frequency = (info.radioFrequency * KHZ_PER_MHZ).roundToLong().toUInt(),
    bandwidth = (info.radioBandwidth * HZ_PER_KHZ).roundToLong().toUInt(),
    spreadingFactor = info.radioSpreadingFactor.toUByte(),
    codingRate = info.radioCodingRate.toUByte(),
    txPower = info.txPower.toByte(),
)

/** Builds the two companion-app other-settings fields from a [SelfInfo]; the rest stay null (import-only). */
fun buildOtherSettings(info: SelfInfo): MeshCoreNodeConfig.OtherSettings = MeshCoreNodeConfig.OtherSettings(
    manualAddContacts = if (info.manualAddContacts) 1u else 0u,
    advertLocationPolicy = info.advertisementLocationPolicy.toUByte(),
)
