// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeConfigService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.SelfInfo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// MARK: - Post-Identity Resolution (testable seam)

/**
 * The radio id later import steps should use: the reconciliation callback's non-null result when a
 * private key was actually pushed, otherwise [original].
 */
internal suspend fun resolveEffectiveRadioID(
    original: RadioId,
    didImportPrivateKey: Boolean,
    callback: (suspend () -> RadioId?)?,
): RadioId {
    if (!didImportPrivateKey || callback == null) return original
    return callback() ?: original
}

// MARK: - Execute Orchestration (testable seam)

/** The concrete device/database writes [executeConfigImport] performs, one per function. */
internal data class ConfigImportWriters(
    /** A read of current device state so pref/radio writes can be diff-gated against it. */
    val getSelfInfo: suspend () -> SelfInfo,
    val importPrivateKey: suspend (Bytes) -> Unit,
    val setNodeName: suspend (String) -> Unit,
    val setLocation: suspend (latitude: Double, longitude: Double) -> Unit,
    val setOtherParams: suspend (MeshCoreNodeConfig.OtherSettings) -> Unit,
    val resolveEffectiveRadioId: suspend (original: RadioId, didImportPrivateKey: Boolean) -> RadioId,
    val setRadioParams: suspend (MeshCoreNodeConfig.RadioSettings) -> Unit,
    val setTxPower: suspend (Byte) -> Unit,
    val setChannel: suspend (radioId: RadioId, write: ConfigImportPlan.ChannelWrite) -> Unit,
    val addContact: suspend (radioId: RadioId, contact: MeshContact, onDeviceAdded: () -> Unit) -> Unit,
    val saveContact: suspend (radioId: RadioId, contact: MeshContact) -> Unit,
)

// MARK: - Pref/radio diff gates (testable seam)

/** Compares the truncated name the device stores, so a longer name with the same stored bytes skips. */
internal fun nodeNameNeedsWrite(name: String, current: SelfInfo): Boolean =
    !NodeConfigSwiftText.canonicallyEqual(name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES), current.name)

/** Compares the scaled integers the device persists, not raw doubles. */
internal fun locationNeedsWrite(position: ConfigImportPlan.Coordinate, current: SelfInfo): Boolean =
    PacketBuilder.scaledCoordinate(position.latitude, PacketBuilder.LATITUDE_RANGE) !=
        PacketBuilder.scaledCoordinate(current.latitude, PacketBuilder.LATITUDE_RANGE) ||
        PacketBuilder.scaledCoordinate(position.longitude, PacketBuilder.LONGITUDE_RANGE) !=
        PacketBuilder.scaledCoordinate(current.longitude, PacketBuilder.LONGITUDE_RANGE)

/** Compares through the export scaling with TX power normalized out (it is gated separately). */
internal fun radioParamsNeedWrite(radio: MeshCoreNodeConfig.RadioSettings, current: SelfInfo): Boolean =
    radio != NodeConfigService.buildRadioSettings(current).copy(txPower = radio.txPower)

internal fun txPowerNeedsWrite(radio: MeshCoreNodeConfig.RadioSettings, current: SelfInfo): Boolean =
    radio.txPower != current.txPower

/**
 * Executes a validated plan in safe order: identity (so a key import can reassign the radio id before
 * channels/contacts are keyed by it), then position, other params, channels, contacts, and radio
 * last. Contact progress is emitted when the device confirms the add, before the local save,
 * so cancellation during that save cannot hide the already-applied device change.
 */
internal suspend fun executeConfigImport(
    plan: ConfigImportPlan,
    sections: ConfigSections,
    radioId: RadioId,
    writers: ConfigImportWriters,
    logger: NodeConfigLogger,
    onProgress: ((ImportProgress) -> Unit)?,
    notifyContactsChanged: suspend () -> Unit = {},
) {
    val totalSteps = NodeConfigService.stepCount(plan)
    var currentStep = 0
    fun progress(step: ImportStep) {
        currentStep += 1
        onProgress?.invoke(ImportProgress(step, currentStep, totalSteps))
    }
    suspend fun checkCancellation() = currentCoroutineContext().ensureActive()

    // Read once, only when a section carrying a savePrefs commit is present; other-params gates
    // itself inside importOtherParams where its merge lives.
    val needsPrefState = plan.nodeName != null || plan.position != null || plan.radioSettings != null
    val prefState = if (needsPrefState) writers.getSelfInfo() else null

    var effectiveRadioId = radioId
    plan.importPrivateKey?.let { privateKey ->
        checkCancellation()
        writers.importPrivateKey(privateKey)
        progress(ImportStep.PrivateKey)
        logger.info("Imported private key")
    }
    plan.nodeName?.let { name ->
        checkCancellation()
        if (prefState?.let { nodeNameNeedsWrite(name, it) } ?: true) {
            writers.setNodeName(name)
            logger.info("Set node name: $name")
        } else {
            logger.info("Skipped node name (already $name)")
        }
        progress(ImportStep.NodeName)
    }
    if (sections.nodeIdentity) {
        effectiveRadioId = writers.resolveEffectiveRadioId(radioId, plan.importPrivateKey != null)
        if (effectiveRadioId != radioId) logger.info("Post-identity reconciliation reassigned radioID to ${effectiveRadioId.canonicalString}")
    }

    plan.position?.let { position ->
        checkCancellation()
        if (prefState?.let { locationNeedsWrite(position, it) } ?: true) {
            writers.setLocation(position.latitude, position.longitude)
            logger.info("Set position: ${position.latitude}, ${position.longitude}")
        } else {
            logger.info("Skipped position (unchanged)")
        }
        progress(ImportStep.Position)
    }

    plan.otherSettings?.let { other ->
        checkCancellation()
        writers.setOtherParams(other)
        progress(ImportStep.OtherParameters)
        logger.info("Set other params")
    }

    for (write in plan.channelWrites) {
        checkCancellation()
        writers.setChannel(effectiveRadioId, write)
        progress(ImportStep.Channel(write.name))
        logger.info("Set channel ${write.index}: ${write.name}")
    }

    var savedContacts = 0
    var contactFailure: Throwable? = null
    try {
        for ((contact, writeDevice) in plan.contactActions) {
            checkCancellation()
            if (writeDevice) {
                writers.addContact(effectiveRadioId, contact) { progress(ImportStep.Contact(contact.advertisedName)) }
            } else {
                writers.saveContact(effectiveRadioId, contact)
            }
            savedContacts++
            logger.info(if (writeDevice) "Imported contact: ${contact.advertisedName}" else "Reconciled local contact: ${contact.advertisedName}")
        }
    } catch (failure: Throwable) {
        contactFailure = failure
        throw failure
    } finally {
        if (sections.contacts && (contactFailure == null || savedContacts > 0)) {
            try {
                if (savedContacts > 0) {
                    withContext(NonCancellable) { notifyContactsChanged() }
                } else {
                    notifyContactsChanged()
                }
            } catch (notificationFailure: Throwable) {
                val original = contactFailure
                if (original == null) throw notificationFailure
                if (original !== notificationFailure) original.addSuppressed(notificationFailure)
                logger.error("Could not notify contacts changed after partial import: ${notificationFailure.javaClass.name}")
            }
        }
    }
    checkCancellation()

    // Radio goes last (minimizes mesh isolation on BLE disconnect); params and TX power gate separately.
    plan.radioSettings?.let { radio ->
        checkCancellation()
        if (prefState?.let { radioParamsNeedWrite(radio, it) } ?: true) {
            writers.setRadioParams(radio)
            logger.info("Set radio params")
        } else {
            logger.info("Skipped radio params (unchanged)")
        }
        progress(ImportStep.RadioParameters)

        checkCancellation()
        if (prefState?.let { txPowerNeedsWrite(radio, it) } ?: true) {
            try {
                writers.setTxPower(radio.txPower)
            } catch (error: Exception) {
                // Params already retuned the node; flag that power did not follow, then rethrow.
                if (error !is CancellationException) logger.error("Radio params applied but TX power did not: ${error.message}")
                throw error
            }
            logger.info("Set TX power: ${radio.txPower}")
        } else {
            logger.info("Skipped TX power (already ${radio.txPower})")
        }
        progress(ImportStep.TxPower)
    }
}
