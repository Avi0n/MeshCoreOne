// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeConfigService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigCoordinateField
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigRadioField
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.SelfInfo
import java.util.logging.Level
import java.util.logging.Logger

// MARK: - Node Config Service Errors

/** Which radio parameter failed range validation. Localization-free; the app layer maps labels. */
enum class RadioField { FREQUENCY, BANDWIDTH, SPREADING_FACTOR, CODING_RATE, TX_POWER }

/** Which coordinate failed range validation, and on which record. Localization-free. */
sealed interface CoordinateField {
    data object PositionLatitude : CoordinateField
    data object PositionLongitude : CoordinateField
    data class ContactLatitude(val name: String) : CoordinateField
    data class ContactLongitude(val name: String) : CoordinateField
}

/** Swift `NodeConfigServiceError`; [message] is the Swift `errorDescription`. */
sealed class NodeConfigServiceError(message: String) : Exception(message), SourceServiceFaultCarrier {
    data class InvalidChannelSecret(val index: Int, val hexLength: Int) :
        NodeConfigServiceError("Channel $index has invalid secret ($hexLength hex chars, expected 32)")
    data class InvalidContactPublicKey(val name: String) :
        NodeConfigServiceError("Contact \"$name\" has an invalid public key")
    data class InvalidPathHashMode(val name: String, val mode: UByte) :
        NodeConfigServiceError("Contact \"$name\" has unsupported path hash mode $mode (expected 0, 1, or 2)")
    data class InvalidPrivateKey(val hexLength: Int) :
        NodeConfigServiceError("Invalid private key ($hexLength hex chars, expected ${ProtocolLimits.PRIVATE_KEY_SIZE * 2})")
    data class InvalidRadioSettings(val field: RadioField) :
        NodeConfigServiceError("Radio parameter is outside the supported range")
    data class NoAvailableChannelSlot(val name: String) :
        NodeConfigServiceError("No empty channel slot available for \"$name\"")
    data class InvalidCoordinate(val field: CoordinateField) :
        NodeConfigServiceError("Coordinate is invalid or out of range")
    data class InvalidOutPath(val name: String) :
        NodeConfigServiceError("Contact \"$name\" has an invalid routing path")
    data class ContactCapacityExceeded(val needed: Int, val available: Int) :
        NodeConfigServiceError("Import needs $needed free contact slot(s) but only $available remain on the device")

    /** Exhaustive projection onto the neutral payload; every raw value and typed field is carried as-is. */
    override val sourceServiceFault: NodeConfigServiceFault
        get() = when (this) {
            is InvalidChannelSecret -> NodeConfigServiceFault.InvalidChannelSecret(index, hexLength)
            is InvalidContactPublicKey -> NodeConfigServiceFault.InvalidContactPublicKey(name)
            is InvalidPathHashMode -> NodeConfigServiceFault.InvalidPathHashMode(name, mode)
            is InvalidPrivateKey -> NodeConfigServiceFault.InvalidPrivateKey(hexLength)
            is InvalidRadioSettings -> NodeConfigServiceFault.InvalidRadioSettings(this.field.toFaultField())
            is NoAvailableChannelSlot -> NodeConfigServiceFault.NoAvailableChannelSlot(name)
            is InvalidCoordinate -> NodeConfigServiceFault.InvalidCoordinate(this.field.toFaultField())
            is InvalidOutPath -> NodeConfigServiceFault.InvalidOutPath(name)
            is ContactCapacityExceeded -> NodeConfigServiceFault.ContactCapacityExceeded(needed, available)
        }
}

/** A contact was accepted by the radio but its local persistence did not complete. */
class NodeConfigContactSaveFailure(cause: Throwable) :
    Exception(null, cause)

/** One-for-one projection of [RadioField] onto its neutral payload mirror. */
private fun RadioField.toFaultField(): NodeConfigRadioField = when (this) {
    RadioField.FREQUENCY -> NodeConfigRadioField.FREQUENCY
    RadioField.BANDWIDTH -> NodeConfigRadioField.BANDWIDTH
    RadioField.SPREADING_FACTOR -> NodeConfigRadioField.SPREADING_FACTOR
    RadioField.CODING_RATE -> NodeConfigRadioField.CODING_RATE
    RadioField.TX_POWER -> NodeConfigRadioField.TX_POWER
}

/** One-for-one projection of [CoordinateField] onto its neutral payload mirror; the contact name is carried as-is. */
private fun CoordinateField.toFaultField(): NodeConfigCoordinateField = when (this) {
    CoordinateField.PositionLatitude -> NodeConfigCoordinateField.PositionLatitude
    CoordinateField.PositionLongitude -> NodeConfigCoordinateField.PositionLongitude
    is CoordinateField.ContactLatitude -> NodeConfigCoordinateField.ContactLatitude(name)
    is CoordinateField.ContactLongitude -> NodeConfigCoordinateField.ContactLongitude(name)
}

// MARK: - Import Progress / Preview

/** Which destructive write an [ImportProgress] update follows. Localization-free. */
sealed interface ImportStep {
    data object Position : ImportStep
    data object OtherParameters : ImportStep
    data object PrivateKey : ImportStep
    data object NodeName : ImportStep
    data object RadioParameters : ImportStep
    data object TxPower : ImportStep
    data class Channel(val name: String) : ImportStep
    data class Contact(val name: String) : ImportStep
}

data class ImportProgress(val step: ImportStep, val current: Int, val total: Int)

/** Non-destructive summary of an import, computed by the same planner the import runs. */
data class ImportPreview(val channelsOverwriteExisting: Boolean)

// MARK: - Collaborator ports (narrow seams over services owned by other work packages)

/**
 * The `SettingsService` operations `NodeConfigService` calls. Implemented by WP-211's
 * `SettingsService` port (Swift `SettingsService`); kept narrow so this package does not depend on
 * that work package. Implementations must surface the session's firmware `featureDisabled` failure
 * from [exportPrivateKey] so that [isFeatureDisabled] recognises it.
 */
interface NodeConfigSettingsPort {
    suspend fun getSelfInfo(): SelfInfo
    suspend fun queryDevice(): DeviceCapabilities
    suspend fun exportPrivateKey(): Bytes
    suspend fun importPrivateKey(key: Bytes)
    /** Writes the name truncated to `ProtocolLimits.MAX_USABLE_NAME_BYTES` UTF-8 bytes. */
    suspend fun setNodeName(name: String)
    suspend fun setLocation(latitude: Double, longitude: Double)
    /** Swift misnomer preserved: [bandwidthKHz] takes Hz, exactly as the config stores it. */
    suspend fun setRadioParams(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte)
    suspend fun setTxPower(power: Byte)
    /** Sends the policy as a raw byte so an unmodeled firmware value is forwarded verbatim. */
    suspend fun setOtherParams(autoAddContacts: Boolean, telemetryModes: TelemetryModes, advertLocationPolicyRaw: UByte, multiAcks: UByte)

    /** Swift `catch SettingsServiceError.sessionError(.featureDisabled)`; matches anywhere in the cause chain. */
    fun isFeatureDisabled(error: Exception): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any { it is MeshCoreException.FeatureDisabled }
}

/** Swift `ChannelService.setChannelWithSecret`; implemented by WP-209's channel service. */
fun interface NodeConfigChannelWriter {
    suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes)
}

/** The single persistence write the import performs; [ContactPersisting.saveContact] satisfies it. */
fun interface NodeConfigContactSaver {
    suspend fun saveContact(radioId: RadioId, frame: ContactFrame)

    companion object {
        fun from(store: ContactPersisting): NodeConfigContactSaver =
            NodeConfigContactSaver { radioId, frame -> store.saveContact(radioId, frame) }
    }
}

/** Swift `SyncCoordinator.notifyContactsChanged`; implemented by the sync coordinator port. */
fun interface NodeConfigContactsChangedNotifier {
    suspend fun notifyContactsChanged()
}

/** Swift `os.Logger` stand-in; the default writes through `java.util.logging`. */
interface NodeConfigLogger {
    fun info(message: String)
    fun warning(message: String)
    fun error(message: String)

    companion object {
        val NONE: NodeConfigLogger = object : NodeConfigLogger {
            override fun info(message: String) = Unit
            override fun warning(message: String) = Unit
            override fun error(message: String) = Unit
        }

        fun system(category: String): NodeConfigLogger = object : NodeConfigLogger {
            private val logger = Logger.getLogger("com.mc1.$category")
            override fun info(message: String) = logger.log(Level.INFO, message)
            override fun warning(message: String) = logger.log(Level.WARNING, message)
            override fun error(message: String) = logger.log(Level.SEVERE, message)
        }
    }
}
