package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.toContactFrame
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.meshcore.DeviceOtherParams
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.NodeConfigWrites
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.settings.TelemetryModes

/**
 * kHz/Hz plan values divide by this before [MeshSession.setRadio], matching Swift
 * `SettingsService.setRadioParams` (frequency kHz → MHz, bandwidth Hz → kHz).
 */
private const val RADIO_UNIT_SCALE: Double = 1000.0

/** Device and local writes behind [NodeConfigSessionAdapter]. */
internal class NodeConfigSessionWrites(
    private val session: MeshSession,
    private val channelService: ChannelService,
    private val contactStore: ContactStore,
) : NodeConfigWrites {
    override suspend fun importPrivateKey(privateKey: ByteArray) = session.importPrivateKey(privateKey)

    override suspend fun setNodeName(name: String) {
        session.setName(name.utf8BytePrefix(MAX_USABLE_NAME_BYTES))
    }

    override suspend fun setLocation(latitude: Double, longitude: Double) {
        session.setCoordinates(latitude, longitude)
    }

    override suspend fun setOtherParams(
        autoAddContacts: Boolean,
        telemetryModes: TelemetryModes,
        advertLocationPolicyRaw: Int,
        multiAcks: Int,
    ) {
        // SettingsService maps auto-add to the device's manual-add bit by inversion.
        session.setOtherParams(
            DeviceOtherParams(
                manualAddContacts = !autoAddContacts,
                telemetryModeEnvironment = telemetryModes.environment,
                telemetryModeLocation = telemetryModes.location,
                telemetryModeBase = telemetryModes.base,
                advertisementLocationPolicy = advertLocationPolicyRaw,
                multiAcks = multiAcks,
            ),
        )
    }

    override suspend fun setRadioParams(
        frequencyKHz: UInt,
        bandwidthKHz: UInt,
        spreadingFactor: UByte,
        codingRate: UByte,
    ) {
        // bandwidthKHz is Hz on the plan (misnomer); divide both by 1000 for the wire (MHz / kHz).
        session.setRadio(
            frequency = frequencyKHz.toDouble() / RADIO_UNIT_SCALE,
            bandwidth = bandwidthKHz.toDouble() / RADIO_UNIT_SCALE,
            spreadingFactor = spreadingFactor.toInt(),
            codingRate = codingRate.toInt(),
            clientRepeat = null,
        )
    }

    override suspend fun setTxPower(txPower: Byte) = session.setTxPower(txPower.toInt())

    override suspend fun setChannel(radioID: Uuid, index: Int, name: String, secret: ByteArray) {
        channelService.setChannelWithSecret(radioID, index, name, secret)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun setContact(radioID: Uuid, contact: MeshContact) {
        session.addContact(contact)
        // Device add is irreversible; the local (radioID, publicKey) upsert is reconciled by the next
        // contact sync, so a local-save failure must not mask a successful device write.
        try {
            contactStore.saveContact(radioID, contact.toContactFrame())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PersistentLogger(CoroutineScope(currentCoroutineContext()), category = "NodeConfigService")
                .warning("Imported contact local save failed: ${error::class.simpleName}")
        }
    }
}
