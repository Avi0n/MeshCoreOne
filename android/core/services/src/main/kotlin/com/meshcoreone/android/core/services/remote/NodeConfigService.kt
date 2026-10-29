// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeConfigService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Exports device configuration to [MeshCoreNodeConfig] and imports it back, handling section
 * filtering, other-params merging, and safe import ordering (Swift `actor NodeConfigService`).
 *
 * The only mutable state is the late-bound post-identity callback, confined behind [lock]. Like the
 * Swift actor (which is reentrant across `await`), concurrent export/import calls are not serialized
 * against each other; each call reads fresh device state.
 */
class NodeConfigService(
    private val session: MeshCoreSessionProtocol,
    private val settingsService: NodeConfigSettingsPort,
    private val channelService: NodeConfigChannelWriter,
    private val contactSaver: NodeConfigContactSaver,
    private val syncCoordinator: NodeConfigContactsChangedNotifier?,
    private val logger: NodeConfigLogger = NodeConfigLogger.system("NodeConfigService"),
) {
    constructor(
        session: MeshCoreSessionProtocol,
        settingsService: NodeConfigSettingsPort,
        channelService: NodeConfigChannelWriter,
        dataStore: ContactPersisting,
        syncCoordinator: NodeConfigContactsChangedNotifier?,
    ) : this(session, settingsService, channelService, NodeConfigContactSaver.from(dataStore), syncCoordinator)

    private val lock = Any()
    private var onPostIdentityImport: (suspend () -> RadioId?)? = null

    /**
     * Wires a late-bound callback that fires after a private-key import succeeds. Its non-null
     * return value replaces the radio id used by every later import step (channels, contacts).
     */
    fun setOnPostIdentityImport(callback: (suspend () -> RadioId?)?) {
        synchronized(lock) { onPostIdentityImport = callback }
    }

    /** Whether a sync coordinator was injected at construction. */
    internal val hasSyncCoordinatorWired: Boolean get() = syncCoordinator != null

    // MARK: - Export

    suspend fun exportConfig(sections: ConfigSections): MeshCoreNodeConfig {
        val selfInfo = settingsService.getSelfInfo()
        var config = MeshCoreNodeConfig()

        if (sections.nodeIdentity) {
            config = config.copy(name = selfInfo.name, publicKey = selfInfo.publicKey.hexString)
            // Hardened firmware disables private-key export; emit name + public key in that case.
            // Any other failure (timeout, device error, cancellation) propagates.
            try {
                config = config.copy(privateKey = settingsService.exportPrivateKey().hexString)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!settingsService.isFeatureDisabled(error)) throw error
                logger.warning("Private key export disabled by firmware; exporting identity without it")
            }
        }
        if (sections.radioSettings) config = config.copy(radioSettings = buildRadioSettings(selfInfo))
        if (sections.positionSettings) {
            config = config.copy(
                positionSettings = MeshCoreNodeConfig.PositionSettings(
                    latitude = NodeConfigSwiftText.describe(selfInfo.latitude),
                    longitude = NodeConfigSwiftText.describe(selfInfo.longitude),
                ),
            )
        }
        if (sections.otherSettings) config = config.copy(otherSettings = buildOtherSettings(selfInfo))
        if (sections.channels) {
            val capabilities = settingsService.queryDevice()
            config = config.copy(channels = exportChannels(channelCount(capabilities.maxChannels)).snapshot())
        }
        if (sections.contacts) {
            config = config.copy(contacts = session.getContacts(null).map(::buildContactConfig).snapshot())
        }
        return config
    }

    // MARK: - Import

    /**
     * Writes [config] to the device in safe order (radio last): a non-destructive read, a pure
     * validate/plan pass that rejects a malformed config before any write, then execute.
     */
    suspend fun importConfig(
        config: MeshCoreNodeConfig,
        sections: ConfigSections,
        radioId: RadioId,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ) {
        val plan = buildImportPlan(config, sections)
        val coordinator = syncCoordinator
        executeConfigImport(
            plan = plan, sections = sections, radioId = radioId,
            writers = makeWriters(), logger = logger, onProgress = onProgress,
            notifyContactsChanged = { coordinator?.notifyContactsChanged() },
        )
    }

    /** Summarizes what an import would do so the UI can warn about channel overwrites first. */
    suspend fun previewImport(config: MeshCoreNodeConfig, sections: ConfigSections): ImportPreview =
        ImportPreview(buildImportPlan(config, sections).channelsOverwriteExisting)

    private fun makeWriters(): ConfigImportWriters {
        val callback = synchronized(lock) { onPostIdentityImport }
        return ConfigImportWriters(
            getSelfInfo = { settingsService.getSelfInfo() },
            importPrivateKey = { settingsService.importPrivateKey(it) },
            setNodeName = { settingsService.setNodeName(it) },
            setLocation = { latitude, longitude -> settingsService.setLocation(latitude, longitude) },
            setOtherParams = { importOtherParams(it) },
            resolveEffectiveRadioId = { original, didImport -> resolveEffectiveRadioID(original, didImport, callback) },
            setRadioParams = { radio ->
                // The bandwidthKHz parameter actually takes Hz (misnomer); pass directly.
                settingsService.setRadioParams(radio.frequency, radio.bandwidth, radio.spreadingFactor, radio.codingRate)
            },
            setTxPower = { settingsService.setTxPower(it) },
            setChannel = { id, write -> channelService.setChannelWithSecret(id, write.index, write.name, write.secret) },
            addContact = { id, contact, onDeviceAdded -> addContact(id, contact, onDeviceAdded) },
        )
    }

    /**
     * The device add is irreversible. Report it before attempting the local save so failures and
     * cancellation leave the consumer aware of the partial import. Later sync can reconcile the row.
     */
    private suspend fun addContact(radioId: RadioId, contact: MeshContact, onDeviceAdded: () -> Unit) {
        session.addContact(contact)
        onDeviceAdded()
        try {
            contactSaver.saveContact(radioId, contact.nodeConfigContactFrame())
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            logger.error("Contact was added to the device but local save failed: ${error.javaClass.simpleName}")
            throw NodeConfigContactSaveFailure(error)
        } catch (error: Exception) {
            logger.error("Contact was added to the device but local save failed: ${error.javaClass.simpleName}")
            throw NodeConfigContactSaveFailure(error)
        }
    }

    /**
     * Read phase shared by import and preview: device capabilities, current channel slots, existing
     * contacts (minus ZephCore's virtual V-contact), and max TX power, then the pure planner.
     */
    private suspend fun buildImportPlan(config: MeshCoreNodeConfig, sections: ConfigSections): ConfigImportPlan {
        val capabilities = settingsService.queryDevice()
        val maxChannels = channelCount(capabilities.maxChannels)
        val maxContacts = capabilities.maxContacts.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        val existingChannels = if (sections.channels) readExistingChannels(maxChannels) else emptyList()
        val existingContacts = if (sections.contacts) {
            session.getContacts(null).associateBy { it.publicKey.hexString }.toMutableMap()
        } else {
            mutableMapOf()
        }
        val selfInfo = if (sections.contacts || sections.radioSettings) settingsService.getSelfInfo() else null

        var planConfig = config
        val vKey = if (sections.contacts) selfInfo?.publicKey?.let(VContactIdentity::publicKey) else null
        if (vKey != null) {
            val vKeyHex = vKey.hexString
            existingContacts.remove(vKeyHex)
            planConfig.contacts?.let { contacts ->
                planConfig = planConfig.copy(
                    contacts = contacts.filter { it.publicKey.lowercase() != vKeyHex.lowercase() }.snapshot(),
                )
            }
        }

        val maxTxPower: Byte = if (sections.radioSettings) selfInfo?.maxTxPower ?: 0 else 0
        return planConfigImport(
            config = planConfig, sections = sections,
            maxChannels = maxChannels, maxContacts = maxContacts, maxTxPower = maxTxPower,
            existingChannels = existingChannels, existingContacts = existingContacts.toMap(),
        )
    }

    private suspend fun readExistingChannels(maxChannels: UByte): List<DeviceChannelSlot> {
        val slots = ArrayList<DeviceChannelSlot>(maxChannels.toInt())
        for (index in 0 until maxChannels.toInt()) {
            currentCoroutineContext().ensureActive()
            val info = session.getChannel(index.toUByte())
            slots += DeviceChannelSlot(index.toUByte(), info.name, info.secret, isChannelConfigured(info.name, info.secret))
        }
        return slots
    }

    private suspend fun exportChannels(maxChannels: UByte): List<MeshCoreNodeConfig.ChannelConfig> {
        val channels = mutableListOf<MeshCoreNodeConfig.ChannelConfig>()
        for (index in 0 until maxChannels.toInt()) {
            val info = session.getChannel(index.toUByte())
            if (!isChannelConfigured(info.name, info.secret)) continue
            channels += MeshCoreNodeConfig.ChannelConfig(info.name, info.secret.hexString)
        }
        return channels
    }

    /**
     * Merges imported other-settings with current device values for absent fields, skipping the
     * `/new_prefs` commit entirely when the merge equals the device's current values.
     * `advertisementType` is firmware-managed and neither exported nor imported here.
     */
    internal suspend fun importOtherParams(imported: MeshCoreNodeConfig.OtherSettings) {
        val current = settingsService.getSelfInfo()
        val currentManualAdd: UByte = if (current.manualAddContacts) 1u else 0u
        val manualAdd = imported.manualAddContacts ?: currentManualAdd
        val advertPolicy = imported.advertLocationPolicy ?: current.advertisementLocationPolicy
        val telBase = imported.telemetryModeBase ?: current.telemetryModeBase
        val telLocation = imported.telemetryModeLocation ?: current.telemetryModeLocation
        val telEnvironment = imported.telemetryModeEnvironment ?: current.telemetryModeEnvironment
        val multiAcks = imported.multiAcks ?: current.multiAcks

        if (manualAdd == currentManualAdd && advertPolicy == current.advertisementLocationPolicy &&
            telBase == current.telemetryModeBase && telLocation == current.telemetryModeLocation &&
            telEnvironment == current.telemetryModeEnvironment && multiAcks == current.multiAcks
        ) return

        settingsService.setOtherParams(
            autoAddContacts = manualAdd.toInt() == 0,
            telemetryModes = TelemetryModes.of(telBase, telLocation, telEnvironment),
            advertLocationPolicyRaw = advertPolicy,
            multiAcks = multiAcks,
        )
    }

    companion object {
        /** Builds radio settings from SelfInfo (MHz -> kHz, kHz -> Hz, Swift `rounded()`). */
        fun buildRadioSettings(info: SelfInfo): MeshCoreNodeConfig.RadioSettings = MeshCoreNodeConfig.RadioSettings(
            frequency = NodeConfigSwiftText.saturatingUInt32(NodeConfigSwiftText.roundedHalfAwayFromZero(info.radioFrequency * 1000)),
            bandwidth = NodeConfigSwiftText.saturatingUInt32(NodeConfigSwiftText.roundedHalfAwayFromZero(info.radioBandwidth * 1000)),
            spreadingFactor = info.radioSpreadingFactor,
            codingRate = info.radioCodingRate,
            txPower = info.txPower,
        )

        /** Builds other settings from SelfInfo, matching the official companion app's two fields. */
        fun buildOtherSettings(info: SelfInfo): MeshCoreNodeConfig.OtherSettings = MeshCoreNodeConfig.OtherSettings(
            manualAddContacts = if (info.manualAddContacts) 1u else 0u,
            advertLocationPolicy = info.advertisementLocationPolicy,
        )

        /** Builds a contact config from a MeshContact (flood -> null path, direct -> ""). */
        internal fun buildContactConfig(contact: MeshContact): MeshCoreNodeConfig.ContactConfig {
            val outPath: String? = when {
                contact.isFloodPath -> null
                contact.pathByteLength > 0 && !contact.outPath.isEmpty -> contact.outPath.prefix(contact.pathByteLength).hexString
                else -> ""
            }
            // Hash mode lives in the upper two bits of the encoded out-path length.
            val pathHashMode: UByte? = if (contact.isFloodPath) null else (contact.outPathLength.toInt() ushr 6).toUByte()
            return MeshCoreNodeConfig.ContactConfig(
                type = contact.typeRawValue,
                name = contact.advertisedName,
                publicKey = contact.publicKey.hexString,
                flags = contact.flags.rawValue,
                latitude = NodeConfigSwiftText.describe(contact.latitude),
                longitude = NodeConfigSwiftText.describe(contact.longitude),
                lastAdvert = contact.lastAdvertisement.nodeConfigEpochUInt32(),
                lastModified = contact.lastModified.nodeConfigEpochUInt32(),
                outPath = outPath,
                pathHashMode = pathHashMode,
            )
        }

        /**
         * Total destructive steps for progress reporting, derived from the resolved plan. Must mirror
         * the progress emissions in [executeConfigImport].
         */
        internal fun stepCount(plan: ConfigImportPlan): Int {
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

        /** Swift `ChannelService.isChannelConfigured`: a named slot or a non-zero secret. */
        internal fun isChannelConfigured(name: String, secret: Bytes): Boolean =
            name.isNotEmpty() || !secret.all { it.toInt() == 0 }

        /** Swift `UInt8(capabilities.maxChannels)` (which traps out of range); saturates instead. */
        private fun channelCount(maxChannels: Long): UByte = maxChannels.coerceIn(0L, 255L).toInt().toUByte()
    }
}

/** Swift `MeshContact.toContactFrame()` (ContactService.swift extension) for the local upsert. */
internal fun MeshContact.nodeConfigContactFrame(): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = type,
    flags = flags.rawValue,
    outPathLength = outPathLength,
    outPath = outPath,
    name = advertisedName,
    lastAdvertTimestamp = lastAdvertisement.nodeConfigEpochUInt32(),
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified.nodeConfigEpochUInt32(),
    typeRawValue = typeRawValue,
)

/** Swift `UInt32(date.timeIntervalSince1970)`, saturating where Swift would trap. */
internal fun java.time.Instant.nodeConfigEpochUInt32(): UInt =
    NodeConfigSwiftText.truncatedEpochSeconds(this).coerceIn(0L, UInt.MAX_VALUE.toLong()).toUInt()
