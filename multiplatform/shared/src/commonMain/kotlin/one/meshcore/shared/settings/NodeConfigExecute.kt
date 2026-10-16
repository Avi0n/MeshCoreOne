package one.meshcore.shared.settings

import kotlinx.coroutines.ensureActive
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.coroutines.coroutineContext

/**
 * The concrete device/persistence writes [executeConfigImport] performs, one per closure, mirroring the Swift
 * `ConfigImportWriters` struct. [NodeConfigService] builds these from its session seam; tests build spies, so
 * the destructive-path sequencing is exercised without a live session. [setOtherParams] receives the raw plan
 * value and does the imported-`?:`-current merge itself; [setContact] only throws when the device add fails.
 */
// Writers is a Swift-style bag of write closures; splitting it recreates a loose-parameter problem.
data class ConfigImportWriters(
    val getSelfInfo: suspend () -> SelfInfo,
    val importPrivateKey: suspend (ByteArray) -> Unit,
    val setNodeName: suspend (String) -> Unit,
    val setLocation: suspend (latitude: Double, longitude: Double) -> Unit,
    val setOtherParams: suspend (MeshCoreNodeConfig.OtherSettings) -> Unit,
    val resolveEffectiveRadioID: suspend (original: Uuid, didImportPrivateKey: Boolean) -> Uuid,
    val setRadioParams: suspend (MeshCoreNodeConfig.RadioSettings) -> Unit,
    val setTxPower: suspend (Byte) -> Unit,
    val setChannel: suspend (radioID: Uuid, write: ConfigImportPlan.ChannelWrite) -> Unit,
    val setContact: suspend (radioID: Uuid, contact: PlannedContact) -> Unit,
)

/**
 * Resolves the `radioID` subsequent import steps use, given whether the identity import actually pushed a
 * private key and the late-bound reconciliation callback. A free function so it is testable without a live
 * session, mirroring the Swift `resolveEffectiveRadioID`.
 */
suspend fun resolveEffectiveRadioID(
    original: Uuid,
    didImportPrivateKey: Boolean,
    callback: (suspend () -> Uuid?)?,
): Uuid {
    if (!didImportPrivateKey || callback == null) return original
    return callback() ?: original
}

/** Optional progress and the contact-list refresh invoked by [executeConfigImport]. */
data class ConfigImportHooks(
    val onProgress: ((ImportProgress) -> Unit)? = null,
    val notifyContactsChanged: suspend () -> Unit = {},
)

/**
 * Executes a validated [ConfigImportPlan] in safe order: identity first (so a private-key import can reassign
 * the radioID before channels/contacts key on it), then position, other params, channels, contacts, and radio
 * last. Each progress callback fires only after its write succeeds, so "no progress" reliably means "nothing
 * was written". A free function taking write closures so the ordering/progress/cancellation are unit-testable
 * without a live session, mirroring the Swift `executeConfigImport`.
 */
suspend fun executeConfigImport(
    plan: ConfigImportPlan,
    sections: ConfigSections,
    radioID: Uuid,
    writers: ConfigImportWriters,
    hooks: ConfigImportHooks = ConfigImportHooks(),
) {
    // Read current device state once so the pref/radio writes below diff-gate against it. Only fetched when a
    // section carrying a savePrefs commit is present; other-params gates itself inside its writer.
    val needsPrefState = plan.nodeName != null || plan.position != null || plan.radioSettings != null
    val prefState = if (needsPrefState) writers.getSelfInfo() else null
    ConfigImportRun(plan, sections, writers, prefState, hooks, radioID).applyAll()
}

private class ConfigImportRun(
    private val plan: ConfigImportPlan,
    private val sections: ConfigSections,
    private val writers: ConfigImportWriters,
    private val prefState: SelfInfo?,
    private val hooks: ConfigImportHooks,
    private val originalRadioID: Uuid,
) {
    private val stepper = ImportStepper(stepCount(plan), hooks.onProgress)
    private var effectiveRadioID: Uuid = originalRadioID

    suspend fun applyAll() {
        applyIdentity()
        applyPosition()
        applyOther()
        applyChannels()
        applyContacts()
        applyRadio()
    }

    // A private-key import can reassign the radio id that later channel and contact writes key on.
    private suspend fun applyIdentity() {
        val privateKey = plan.importPrivateKey
        if (privateKey != null) {
            coroutineContext.ensureActive()
            writers.importPrivateKey(privateKey)
            stepper.advance(ImportStep.PrivateKey)
        }
        val name = plan.nodeName
        if (name != null) {
            coroutineContext.ensureActive()
            if (deviceNeedsWrite { nodeNameNeedsWrite(name, it) }) writers.setNodeName(name)
            stepper.advance(ImportStep.NodeName)
        }
        if (sections.nodeIdentity) {
            effectiveRadioID = writers.resolveEffectiveRadioID(originalRadioID, plan.importPrivateKey != null)
        }
    }

    private suspend fun applyPosition() {
        val position = plan.position ?: return
        coroutineContext.ensureActive()
        if (deviceNeedsWrite { locationNeedsWrite(position, it) }) {
            writers.setLocation(position.latitude, position.longitude)
        }
        stepper.advance(ImportStep.Position)
    }

    private suspend fun applyOther() {
        val other = plan.otherSettings ?: return
        coroutineContext.ensureActive()
        writers.setOtherParams(other)
        stepper.advance(ImportStep.OtherParameters)
    }

    private suspend fun applyChannels() {
        for (write in plan.channelWrites) {
            coroutineContext.ensureActive()
            writers.setChannel(effectiveRadioID, write)
            stepper.advance(ImportStep.Channel(write.name))
        }
    }

    private suspend fun applyContacts() {
        for (contact in plan.contactRecords) {
            coroutineContext.ensureActive()
            writers.setContact(effectiveRadioID, contact)
            // setContact only throws on a device-add failure; a local-save failure is swallowed there, so
            // reaching this line means the contact is on the device and the progress yield must fire.
            stepper.advance(ImportStep.Contact(contact.advertisedName))
        }
        if (sections.contacts) {
            // Refresh contacts before radio, so a later radio failure does not suppress the UI update.
            hooks.notifyContactsChanged()
        }
    }

    // Radio is last so a disconnect isolates the mesh for as little time as possible. Params and TX power
    // gate independently, and each still reports progress when the write itself is skipped.
    private suspend fun applyRadio() {
        val radio = plan.radioSettings ?: return
        coroutineContext.ensureActive()
        if (deviceNeedsWrite { radioParamsNeedWrite(radio, it) }) writers.setRadioParams(radio)
        stepper.advance(ImportStep.RadioParameters)
        coroutineContext.ensureActive()
        if (deviceNeedsWrite { txPowerNeedsWrite(radio, it) }) writers.setTxPower(radio.txPower)
        stepper.advance(ImportStep.TxPower)
    }

    // A missing device snapshot still writes. A snapshot writes only when the imported value differs.
    private fun deviceNeedsWrite(differs: (SelfInfo) -> Boolean): Boolean = prefState?.let(differs) != false
}

private class ImportStepper(private val totalSteps: Int, private val onProgress: ((ImportProgress) -> Unit)?) {
    private var currentStep = 0

    fun advance(step: ImportStep) {
        currentStep += 1
        onProgress?.invoke(ImportProgress(step, currentStep, totalSteps))
    }
}

/**
 * Total destructive steps for progress reporting, derived from the resolved plan so the bar reflects
 * post-dedup channel/contact counts. Mirrors the write gates in [executeConfigImport]: every conditional
 * `progress` there needs a matching term here. Mirrors the Swift `NodeConfigService.stepCount`.
 */
fun stepCount(plan: ConfigImportPlan): Int {
    var count = 0
    if (plan.importPrivateKey != null) count += 1
    if (plan.nodeName != null) count += 1
    if (plan.position != null) count += 1
    if (plan.otherSettings != null) count += 1
    count += plan.channelWrites.size
    count += plan.contactRecords.size
    if (plan.radioSettings != null) count += 2 // radio params + tx power
    return count
}

/**
 * Builds a contact config from a [MeshContact], resolving the three-state out_path (flood → null, direct →
 * empty string, routed → meaningful-byte hex) and the path-hash mode. Mirrors the Swift `buildContactConfig`.
 */
fun buildContactConfig(contact: MeshContact): MeshCoreNodeConfig.ContactConfig {
    val isFlood = contact.outPathLength == FLOOD_PATH_LENGTH
    val byteLength = PathEncoding.decodePathLen(contact.outPathLength)?.byteLength ?: 0
    val outPath: String? = when {
        isFlood -> null
        byteLength > 0 && contact.outPath.isNotEmpty() ->
            contact.outPath.copyOf(byteLength.coerceAtMost(contact.outPath.size)).toLowercaseHex()
        else -> ""
    }
    val pathHashMode: UByte? =
        if (isFlood) null else PathEncoding.pathHashMode(contact.outPathLength).toUByte()

    return MeshCoreNodeConfig.ContactConfig(
        type = contact.typeRawValue.toUByte(),
        name = contact.advertisedName,
        publicKey = contact.publicKey.toLowercaseHex(),
        flags = contact.flags.toUByte(),
        latitude = contact.latitude.toString(),
        longitude = contact.longitude.toString(),
        lastAdvert = contact.lastAdvertisement.toUInt(),
        lastModified = contact.lastModified.toUInt(),
        outPath = outPath,
        pathHashMode = pathHashMode,
    )
}

private const val FLOOD_PATH_LENGTH = 0xFF
