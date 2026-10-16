package one.meshcore.shared.settings

import kotlinx.coroutines.ensureActive
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.coroutines.coroutineContext

/**
 * Exports device configuration to a [MeshCoreNodeConfig] and imports it back, over the committed model and
 * [planConfigImport]. The Kotlin port of the Swift `NodeConfigService` actor, rebuilt per connection: it
 * mirrors the export section filtering, the read/validate/plan/execute import split (so a poison config is
 * rejected before any write), the safe write ordering (radio last), the pref/radio diff gates, and the
 * other-params merge. Every device and persistence op goes through the injected [session] seam so it is
 * unit-testable against a scripted fake; [onContactsChanged] is the post-write UI refresh hook and
 * [onPostIdentityImport] reconciles the radioID after a private-key import.
 */
class NodeConfigService(
    private val session: NodeConfigSession,
    private val onContactsChanged: (suspend () -> Unit)? = null,
) {
    /**
     * Fires after `importPrivateKey` succeeds so the connection layer can reconcile the restored identity;
     * a non-null return replaces the radioID used by the channel/contact writes. Installed by the connection
     * layer, mirroring the Swift `setOnPostIdentityImport`.
     */
    var onPostIdentityImport: (suspend () -> Uuid?)? = null

    // MARK: - Export

    /** Reads the device state and builds a [MeshCoreNodeConfig] for the selected [sections]. */
    suspend fun exportConfig(sections: ConfigSections): MeshCoreNodeConfig {
        val selfInfo = session.getSelfInfo()
        var config = MeshCoreNodeConfig()

        if (sections.nodeIdentity) {
            config = config.copy(name = selfInfo.name, publicKey = selfInfo.publicKey.toLowercaseHex())
            // Hardened firmware disables private-key export; emit name + public key rather than failing the
            // whole export. Any other failure propagates so the user retries instead of saving a keyless backup.
            val privateKey = try {
                session.exportPrivateKey()
            } catch (e: MeshBridgeException) {
                if (e.code == BridgeErrorCode.FEATURE_DISABLED) null else throw e
            }
            if (privateKey != null) config = config.copy(privateKey = privateKey.toLowercaseHex())
        }

        if (sections.radioSettings) config = config.copy(radioSettings = buildRadioSettings(selfInfo))

        if (sections.positionSettings) {
            config = config.copy(
                positionSettings = MeshCoreNodeConfig.PositionSettings(
                    latitude = selfInfo.latitude.toString(),
                    longitude = selfInfo.longitude.toString(),
                ),
            )
        }

        if (sections.otherSettings) config = config.copy(otherSettings = buildOtherSettings(selfInfo))

        if (sections.channels) {
            config = config.copy(channels = exportChannels(session.queryDevice().maxChannels))
        }

        if (sections.contacts) {
            config = config.copy(contacts = session.getContacts(null).map { buildContactConfig(it) })
        }

        return config
    }

    /** Reads all configured channels from the device. */
    private suspend fun exportChannels(maxChannels: Int): List<MeshCoreNodeConfig.ChannelConfig> {
        val channels = mutableListOf<MeshCoreNodeConfig.ChannelConfig>()
        for (index in 0 until maxChannels) {
            val info = session.getChannel(index)
            if (!ChannelSecret.isChannelConfigured(info.name, info.secret)) continue
            channels += MeshCoreNodeConfig.ChannelConfig(name = info.name, secret = info.secret.toLowercaseHex())
        }
        return channels
    }

    // MARK: - Import

    /** Writes a [MeshCoreNodeConfig] to the device in safe order (radio last), validating up front. */
    suspend fun importConfig(
        config: MeshCoreNodeConfig,
        sections: ConfigSections,
        radioID: Uuid,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ) {
        val plan = buildImportPlan(config, sections)
        executeConfigImport(
            plan = plan,
            sections = sections,
            radioID = radioID,
            writers = makeWriters(),
            hooks = ConfigImportHooks(
                onProgress = onProgress,
                notifyContactsChanged = { onContactsChanged?.invoke() },
            ),
        )
    }

    /**
     * Non-destructively summarizes what an import would do (the channel-overwrite flag), running the same
     * planner as [importConfig] so it also surfaces validation errors early.
     */
    suspend fun previewImport(config: MeshCoreNodeConfig, sections: ConfigSections): ImportPreview {
        val plan = buildImportPlan(config, sections)
        return ImportPreview(channelsOverwriteExisting = plan.channelsOverwriteExisting)
    }

    /**
     * Reads the live device once for import-preview "Current" diffs (name / radio / position).
     * Does not export the private key.
     */
    suspend fun loadDeviceStateForDiff(): NodeConfigDeviceDiff {
        val selfInfo = session.getSelfInfo()
        return NodeConfigDeviceDiff(
            name = selfInfo.name,
            radio = buildRadioSettings(selfInfo),
            position = MeshCoreNodeConfig.PositionSettings(
                latitude = selfInfo.latitude.toString(),
                longitude = selfInfo.longitude.toString(),
            ),
        )
    }

    /**
     * Read phase shared by [importConfig] and [previewImport]: gathers device capabilities and current state
     * (channel slots, existing contacts, max TX power) needed to validate the config, then runs the pure
     * planner. Non-destructive.
     */
    private suspend fun buildImportPlan(config: MeshCoreNodeConfig, sections: ConfigSections): ConfigImportPlan {
        val capabilities = session.queryDevice()
        val maxChannels = capabilities.maxChannels
        val maxContacts = capabilities.maxContacts

        val existingChannels = if (sections.channels) readExistingChannels(maxChannels) else emptyList()
        val existingContacts = if (sections.contacts) buildExistingContacts() else emptyMap()

        // One read shared by the V-contact exclusion and the TX power bound, as the Swift buildImportPlan does.
        val selfInfo = if (sections.contacts || sections.radioSettings) session.getSelfInfo() else null

        // ZephCore streams a virtual V-contact in GET_CONTACTS that holds no real contact-table slot, so it must
        // not count against free capacity; its derived key must never be added either, because with V disabled
        // the firmware no longer intercepts the ADD and stores it as a real contact.
        val vKeyHex = if (sections.contacts) {
            selfInfo?.publicKey?.let { VContactIdentity.publicKey(it) }?.toLowercaseHex()
        } else {
            null
        }

        // The TX power upper bound is hardware/build-specific, so read the device's max; only needed for radio.
        val maxTxPower: Byte = if (sections.radioSettings) (selfInfo?.maxTxPower ?: 0).toByte() else 0

        return planConfigImport(
            config = config.withoutContact(vKeyHex),
            sections = sections,
            device = ConfigImportDevice(
                maxChannels = maxChannels,
                maxContacts = maxContacts,
                maxTxPower = maxTxPower,
                existingChannels = existingChannels,
                existingContacts = existingContacts - setOfNotNull(vKeyHex),
            ),
        )
    }

    /** Reads every channel slot and classifies it as configured or empty. */
    private suspend fun readExistingChannels(maxChannels: Int): List<DeviceChannelSlot> {
        val slots = mutableListOf<DeviceChannelSlot>()
        for (index in 0 until maxChannels) {
            coroutineContext.ensureActive()
            val info = session.getChannel(index)
            slots += DeviceChannelSlot(
                index = index,
                name = info.name,
                secret = info.secret,
                isConfigured = ChannelSecret.isChannelConfigured(info.name, info.secret),
            )
        }
        return slots
    }

    /**
     * Reads existing contacts keyed by lowercase-hex public key (last write wins, matching the Swift uniquing),
     * carrying the fields the planner's byte-for-byte "already stored" drop compares.
     */
    private suspend fun buildExistingContacts(): Map<String, ExistingContact> {
        val map = LinkedHashMap<String, ExistingContact>()
        for (contact in session.getContacts(null)) {
            map[contact.publicKey.toLowercaseHex()] = contact.toExistingContact()
        }
        return map
    }

    /** Builds the concrete write closures [executeConfigImport] calls, capturing this service's session seam. */
    private fun makeWriters(): ConfigImportWriters = ConfigImportWriters(
        getSelfInfo = { session.getSelfInfo() },
        importPrivateKey = { session.importPrivateKey(it) },
        setNodeName = { session.setNodeName(it) },
        setLocation = { latitude, longitude -> session.setLocation(latitude, longitude) },
        setOtherParams = { importOtherParams(it) },
        resolveEffectiveRadioID = { original, didImport ->
            resolveEffectiveRadioID(original, didImport, onPostIdentityImport)
        },
        setRadioParams = { radio ->
            session.setRadioParams(radio.frequency, radio.bandwidth, radio.spreadingFactor, radio.codingRate)
        },
        setTxPower = { session.setTxPower(it) },
        setChannel = { radioID, write -> session.setChannel(radioID, write.index, write.name, write.secret) },
        setContact = { radioID, planned -> session.setContact(radioID, planned.toMeshContact()) },
    )

    /**
     * Merges imported other-settings with current device values for fields not in the import, then commits
     * only when the merged result differs from the device (a no-op merge is a vulnerable flash write for
     * nothing). `advertisement_type` is neither exported nor imported here (firmware-managed). Mirrors the
     * Swift `importOtherParams`.
     */
    suspend fun importOtherParams(imported: MeshCoreNodeConfig.OtherSettings) {
        val current = session.getSelfInfo()

        val currentManualAdd = if (current.manualAddContacts) 1 else 0
        val merged = OtherParamsSnapshot(
            manualAdd = imported.manualAddContacts?.toInt() ?: currentManualAdd,
            advertPolicy = imported.advertLocationPolicy?.toInt() ?: current.advertisementLocationPolicy,
            telBase = imported.telemetryModeBase?.toInt() ?: current.telemetryModeBase,
            telLocation = imported.telemetryModeLocation?.toInt() ?: current.telemetryModeLocation,
            telEnvironment = imported.telemetryModeEnvironment?.toInt() ?: current.telemetryModeEnvironment,
            multiAcks = imported.multiAcks?.toInt() ?: current.multiAcks,
        )
        val live = OtherParamsSnapshot(
            manualAdd = currentManualAdd,
            advertPolicy = current.advertisementLocationPolicy,
            telBase = current.telemetryModeBase,
            telLocation = current.telemetryModeLocation,
            telEnvironment = current.telemetryModeEnvironment,
            multiAcks = current.multiAcks,
        )
        if (merged == live) return

        // Pass the policy as a raw byte so a value the app does not model is sent verbatim, not coerced.
        session.setOtherParams(
            autoAddContacts = merged.manualAdd == 0,
            telemetryModes = TelemetryModes(
                base = merged.telBase,
                location = merged.telLocation,
                environment = merged.telEnvironment,
            ),
            advertLocationPolicyRaw = merged.advertPolicy,
            multiAcks = merged.multiAcks,
        )
    }
}

/**
 * The config with any contact whose public key is [publicKeyHex] removed, comparing case-insensitively because
 * a config's hex casing is whatever wrote the file. A null key leaves the config untouched.
 */
private fun MeshCoreNodeConfig.withoutContact(publicKeyHex: String?): MeshCoreNodeConfig {
    if (publicKeyHex == null) return this
    val remaining = contacts?.filter { it.publicKey.lowercase() != publicKeyHex } ?: return this
    return copy(contacts = remaining)
}

/** Comparable snapshot of the other-params fields so a merge no-op is a single equality check. */
private data class OtherParamsSnapshot(
    val manualAdd: Int,
    val advertPolicy: Int,
    val telBase: Int,
    val telLocation: Int,
    val telEnvironment: Int,
    val multiAcks: Int,
)

/** The device add frame for a planned contact. */
internal fun PlannedContact.toMeshContact(): MeshContact = MeshContact(
    id = id,
    publicKey = publicKey,
    typeRawValue = typeRawValue.toInt(),
    flags = flags.toInt(),
    outPathLength = outPathLength.toInt(),
    outPath = outPath,
    advertisedName = advertisedName,
    lastAdvertisement = lastAdvert,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
)

/**
 * The planner's view of a device-resident contact, trimming the stored path to its meaningful bytes so the
 * byte-for-byte "already stored" comparison matches the plan's resolved out-path. Mirrors the Swift planner's
 * `existing.outPath.prefix(existing.pathByteLength)`.
 */
internal fun MeshContact.toExistingContact(): ExistingContact {
    val byteLength = PathEncoding.decodePathLen(outPathLength)?.byteLength ?: 0
    return ExistingContact(
        typeRawValue = typeRawValue.toUByte(),
        flags = flags.toUByte(),
        outPathLength = outPathLength.toUByte(),
        outPath = outPath.copyOf(byteLength.coerceAtMost(outPath.size)),
        advertisedName = advertisedName,
        lastModified = lastModified,
        latitude = latitude,
        longitude = longitude,
    )
}
