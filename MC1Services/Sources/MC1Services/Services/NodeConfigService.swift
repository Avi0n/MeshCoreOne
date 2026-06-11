import Foundation
import MeshCore
import OSLog

// MARK: - Node Config Service Errors

/// Identifies which radio parameter failed range validation. Kept `L10n`-free so the service
/// layer carries no localization; the app layer maps each case to a localized field label.
public enum RadioField: Sendable, Equatable {
    case frequency, bandwidth, spreadingFactor, codingRate, txPower
}

/// Identifies which coordinate failed range validation, and on which record. Kept `L10n`-free
/// so the service layer carries no localization; the app layer maps each case to a localized label.
public enum CoordinateField: Sendable, Equatable {
    case positionLatitude, positionLongitude
    case contactLatitude(name: String), contactLongitude(name: String)
}

public enum NodeConfigServiceError: Error, LocalizedError, Sendable {
    case invalidChannelSecret(index: Int, hexLength: Int)
    case invalidContactPublicKey(name: String)
    case invalidPathHashMode(name: String, mode: UInt8)
    case invalidPrivateKey(hexLength: Int)
    case invalidRadioSettings(field: RadioField)
    case noAvailableChannelSlot(name: String)
    case invalidCoordinate(field: CoordinateField)
    case invalidOutPath(name: String)
    case contactCapacityExceeded(needed: Int, available: Int)

    public var errorDescription: String? {
        switch self {
        case .invalidChannelSecret(let index, let hexLength):
            "Channel \(index) has invalid secret (\(hexLength) hex chars, expected 32)"
        case .invalidContactPublicKey(let name):
            "Contact \"\(name)\" has an invalid public key"
        case .invalidPathHashMode(let name, let mode):
            "Contact \"\(name)\" has unsupported path hash mode \(mode) (expected 0, 1, or 2)"
        case .invalidPrivateKey(let hexLength):
            "Invalid private key (\(hexLength) hex chars, expected \(ProtocolLimits.privateKeySize * 2))"
        case .invalidRadioSettings:
            "Radio parameter is outside the supported range"
        case .noAvailableChannelSlot(let name):
            "No empty channel slot available for \"\(name)\""
        case .invalidCoordinate:
            "Coordinate is invalid or out of range"
        case .invalidOutPath(let name):
            "Contact \"\(name)\" has an invalid routing path"
        case .contactCapacityExceeded(let needed, let available):
            "Import needs \(needed) free contact slot(s) but only \(available) remain on the device"
        }
    }
}

// MARK: - Import Progress

/// Identifies which destructive write an ``ImportProgress`` update precedes. Kept `L10n`-free
/// so the service layer carries no localization; the app layer maps each case to a localized
/// description for display.
public enum ImportStep: Sendable, Equatable {
    case position, otherParameters, privateKey, nodeName
    case radioParameters, txPower
    case channel(name: String)
    case contact(name: String)
}

/// Reports import progress to the UI.
public struct ImportProgress: Sendable {
    public let step: ImportStep
    public let current: Int
    public let total: Int
}

// MARK: - Import Preview

/// A non-destructive summary of what an import would do, used to drive the confirmation UI
/// before any write. Computed by running the same planner the import uses.
public struct ImportPreview: Sendable {
    /// True when at least one channel write would replace an already-configured slot whose
    /// name or secret differs — i.e. the channels section is not purely additive.
    public let channelsOverwriteExisting: Bool
}

// MARK: - Node Config Service

/// Exports device configuration to `MeshCoreNodeConfig` and imports it back,
/// handling section filtering, other-params merging, and safe import ordering.
public actor NodeConfigService {
    private let session: MeshCoreSession
    private let settingsService: SettingsService
    private let channelService: ChannelService
    private let dataStore: any PersistenceStoreProtocol
    /// Injected by `ServiceContainer` at construction.
    private weak var syncCoordinator: SyncCoordinator?
    private let logger = Logger(subsystem: "com.mc1", category: "NodeConfigService")
    /// Called after a config import restores a private key, so the connection layer can
    /// reconcile device identity. Installed by `ConnectionManager.buildServicesAndSaveDevice`.
    private var onPostIdentityImport: (@Sendable () async throws -> UUID?)?

    public init(
        session: MeshCoreSession,
        settingsService: SettingsService,
        channelService: ChannelService,
        dataStore: any PersistenceStoreProtocol,
        syncCoordinator: SyncCoordinator?
    ) {
        self.session = session
        self.settingsService = settingsService
        self.channelService = channelService
        self.dataStore = dataStore
        self.syncCoordinator = syncCoordinator
    }

    /// Wires a late-bound callback that fires after `importIdentity` succeeds.
    /// The callback is responsible for reconciling the radio's restored
    /// `publicKey` with any ghost `Device` row left by a prior "remove from MC1".
    /// Its return value, when non-nil, replaces the `radioID` used by all
    /// subsequent steps in `importConfig` (channels, contacts).
    public func setOnPostIdentityImport(
        _ callback: (@Sendable () async throws -> UUID?)?
    ) {
        self.onPostIdentityImport = callback
    }

    /// Whether a sync coordinator was injected at construction.
    var hasSyncCoordinatorWired: Bool { syncCoordinator != nil }

    // MARK: - Export

    /// Reads the device state and builds a `MeshCoreNodeConfig`.
    /// - Parameter sections: Which sections to include in the export.
    /// - Returns: A populated config struct.
    public func exportConfig(sections: ConfigSections) async throws -> MeshCoreNodeConfig {
        let selfInfo = try await settingsService.getSelfInfo()

        var config = MeshCoreNodeConfig()

        if sections.nodeIdentity {
            config.name = selfInfo.name
            config.publicKey = selfInfo.publicKey.hexString
            // Hardened firmware disables private-key export (`featureDisabled`). Emit name +
            // public key in that case rather than failing the whole export. Any other failure
            // (transient BLE timeout, device error, cancellation) propagates, so the user sees
            // the export fail and retries instead of saving an identity backup missing its key.
            do {
                let privateKey = try await settingsService.exportPrivateKey()
                config.privateKey = privateKey.hexString
            } catch SettingsServiceError.sessionError(.featureDisabled) {
                logger.warning("Private key export disabled by firmware; exporting identity without it")
            }
        }

        if sections.radioSettings {
            config.radioSettings = Self.buildRadioSettings(from: selfInfo)
        }

        if sections.positionSettings {
            config.positionSettings = MeshCoreNodeConfig.PositionSettings(
                latitude: String(selfInfo.latitude),
                longitude: String(selfInfo.longitude)
            )
        }

        if sections.otherSettings {
            config.otherSettings = Self.buildOtherSettings(from: selfInfo)
        }

        if sections.channels {
            let capabilities = try await settingsService.queryDevice()
            config.channels = try await exportChannels(maxChannels: UInt8(capabilities.maxChannels))
        }

        if sections.contacts {
            let meshContacts = try await session.getContacts(since: nil)
            config.contacts = meshContacts.map { Self.buildContactConfig(from: $0) }
        }

        return config
    }

    // MARK: - Import

    /// Writes a `MeshCoreNodeConfig` to the device in safe order (radio last).
    ///
    /// Runs in three phases: a non-destructive **read** of device capabilities and current
    /// channels, a pure **validate/plan** pass (``planConfigImport``)
    /// that rejects a malformed config before any write, then **execute**. Because everything
    /// that can be validated is validated up front, no validation error can land after the
    /// identity has already been rotated.
    /// - Parameters:
    ///   - config: The config to import.
    ///   - sections: Which sections to actually apply.
    ///   - radioID: The connected device UUID (needed for channel writes).
    ///   - onProgress: Optional callback for UI progress updates.
    public func importConfig(
        _ config: MeshCoreNodeConfig,
        sections: ConfigSections,
        radioID: UUID,
        onProgress: (@Sendable (ImportProgress) -> Void)? = nil
    ) async throws {
        // Read + validate/plan phase (non-destructive): rejects a poison/malformed config before any write.
        let plan = try await buildImportPlan(config, sections: sections)

        // Execute phase, driven through the testable `executeConfigImport` seam. The actor supplies
        // the concrete write closures; the sequencing, progress, and cancellation live in the seam.
        let coordinator = syncCoordinator
        try await executeConfigImport(
            plan: plan, sections: sections, radioID: radioID,
            writers: makeWriters(), logger: logger, onProgress: onProgress,
            notifyContactsChanged: { await coordinator?.notifyContactsChanged() }
        )
    }

    /// Builds the concrete write closures `executeConfigImport` calls, capturing this actor's
    /// services. Each closure performs exactly one device (and, for contacts, database) write.
    private func makeWriters() -> ConfigImportWriters {
        let settings = settingsService
        let channels = channelService
        let session = self.session
        let store = dataStore
        let logger = self.logger
        let callback = onPostIdentityImport
        return ConfigImportWriters(
            importPrivateKey: { try await settings.importPrivateKey($0) },
            setNodeName: { try await settings.setNodeName($0) },
            setLocation: { try await settings.setLocation(latitude: $0, longitude: $1) },
            setOtherParams: { [self] in try await self.importOtherParams($0) },
            resolveEffectiveRadioID: {
                try await resolveEffectiveRadioID(original: $0, didImportPrivateKey: $1, callback: callback)
            },
            setRadioParams: { radio in
                // bandwidthKHz parameter actually takes Hz (misnomer); pass directly.
                try await settings.setRadioParams(
                    frequencyKHz: radio.frequency, bandwidthKHz: radio.bandwidth,
                    spreadingFactor: radio.spreadingFactor, codingRate: radio.codingRate
                )
            },
            setTxPower: { try await settings.setTxPower($0) },
            setChannel: { radioID, write in
                try await channels.setChannelWithSecret(
                    radioID: radioID, index: write.index, name: write.name, secret: write.secret
                )
            },
            addContact: { radioID, contact in
                try await session.addContact(contact)
                // The device add is the irreversible change. The local row is an idempotent
                // (radioID, publicKey) upsert that the next contacts sync reconciles, so a save
                // failure here must not mask that the contact already landed on the device.
                do {
                    let frame = contact.toContactFrame()
                    _ = try await store.saveContact(radioID: radioID, from: frame)
                } catch {
                    logger.error("Contact added to device but local save failed; next sync will reconcile: \(error.localizedDescription)")
                }
            }
        )
    }

    /// Total destructive steps for progress reporting, derived entirely from the resolved plan so the
    /// bar reflects post-dedup channel/contact counts and validated sections rather than raw config.
    ///
    /// This must mirror the write gates in `importConfig`: every conditional `progress(...)` call
    /// there needs a matching term here, or the progress bar will under- or over-count. Keep the
    /// two in sync when adding or removing a write step. Internal (not private) so it is unit-testable.
    static func stepCount(for plan: ConfigImportPlan) -> Int {
        var count = 0
        if plan.importPrivateKey != nil { count += 1 }
        if plan.nodeName != nil { count += 1 }
        if plan.position != nil { count += 1 }
        if plan.otherSettings != nil { count += 1 }
        count += plan.channelWrites.count
        count += plan.contactRecords.count
        if plan.radioSettings != nil { count += 2 } // radio params + tx power
        return count
    }

    /// Non-destructively summarizes what an import would do, so the confirmation UI can warn
    /// about channel overwrites before any write. Runs the same planner as `importConfig`, so it
    /// also surfaces validation errors early. The selection UI shows raw per-section counts; the
    /// planner's post-dedup counts are not surfaced here, only the overwrite flag.
    public func previewImport(
        _ config: MeshCoreNodeConfig,
        sections: ConfigSections
    ) async throws -> ImportPreview {
        let plan = try await buildImportPlan(config, sections: sections)
        return ImportPreview(channelsOverwriteExisting: plan.channelsOverwriteExisting)
    }

    // MARK: - Internal Helpers

    /// Read phase shared by `importConfig` and `previewImport`: gathers device capabilities and current
    /// state (channel slots, existing contact keys, max TX power) needed to validate the config, then
    /// runs the pure planner. Non-destructive. `importConfig` re-runs this at execute time for TOCTOU
    /// freshness, so the device reads happen twice across a preview-then-apply cycle; that is an
    /// accepted cost on this cold, user-initiated path.
    private func buildImportPlan(
        _ config: MeshCoreNodeConfig,
        sections: ConfigSections
    ) async throws -> ConfigImportPlan {
        let capabilities = try await settingsService.queryDevice()
        let maxChannels = UInt8(capabilities.maxChannels)
        let maxContacts = Int(capabilities.maxContacts)

        let existingChannels = sections.channels
            ? try await readExistingChannels(maxChannels: maxChannels)
            : []

        // Existing contact keys credit firmware updates (which consume no slot) against free capacity,
        // so an over-capacity import is rejected up front instead of failing partway through the writes.
        let existingContactKeys: Set<String> = sections.contacts
            ? Set(try await session.getContacts(since: nil).map { $0.publicKey.hexString })
            : []

        // The txPower upper bound is hardware/build-specific, so read the device's max rather than
        // assuming a fixed maximum. Only needed when the radio section is selected.
        let maxTxPower = sections.radioSettings
            ? try await settingsService.getSelfInfo().maxTxPower
            : 0

        return try planConfigImport(
            config: config,
            sections: sections,
            maxChannels: maxChannels,
            maxContacts: maxContacts,
            maxTxPower: maxTxPower,
            existingChannels: existingChannels,
            existingContactKeys: existingContactKeys
        )
    }

    /// Reads every channel slot from the device and classifies it as configured or empty.
    private func readExistingChannels(maxChannels: UInt8) async throws -> [DeviceChannelSlot] {
        var slots: [DeviceChannelSlot] = []
        for index in 0 as UInt8..<maxChannels {
            try Task.checkCancellation()
            let info = try await session.getChannel(index: index)
            slots.append(DeviceChannelSlot(
                index: index,
                name: info.name,
                secret: info.secret,
                isConfigured: ChannelService.isChannelConfigured(name: info.name, secret: info.secret)
            ))
        }
        return slots
    }

    /// Reads all configured channels from the device.
    private func exportChannels(maxChannels: UInt8) async throws -> [MeshCoreNodeConfig.ChannelConfig] {
        var channels: [MeshCoreNodeConfig.ChannelConfig] = []
        for index in 0..<maxChannels {
            let info = try await session.getChannel(index: index)
            guard ChannelService.isChannelConfigured(name: info.name, secret: info.secret) else {
                continue
            }
            channels.append(MeshCoreNodeConfig.ChannelConfig(
                name: info.name,
                secret: info.secret.hexString
            ))
        }
        return channels
    }

    /// Merges imported other-settings with current device values for fields not in the import.
    /// `advertisementType` is neither exported nor imported here (firmware-managed); `setOtherParams`
    /// does not accept it.
    func importOtherParams(_ imported: MeshCoreNodeConfig.OtherSettings) async throws {
        let current = try await settingsService.getSelfInfo()

        let manualAdd = imported.manualAddContacts ?? (current.manualAddContacts ? 1 : 0)
        let advertPolicy = imported.advertLocationPolicy ?? current.advertisementLocationPolicy
        let telBase = imported.telemetryModeBase ?? current.telemetryModeBase
        let telLocation = imported.telemetryModeLocation ?? current.telemetryModeLocation
        let telEnvironment = imported.telemetryModeEnvironment ?? current.telemetryModeEnvironment
        let multiAcks = imported.multiAcks ?? current.multiAcks

        // Pass the policy as a raw byte rather than a typed enum so a value the app doesn't model is
        // sent verbatim instead of coerced to `.none`. The firmware stores this byte without clamping
        // and only special-cases the zero value, so any non-zero policy the app doesn't yet model is
        // persisted as-is and behaves as "share location".
        try await settingsService.setOtherParams(
            autoAddContacts: manualAdd == 0,
            telemetryModes: TelemetryModes(base: telBase, location: telLocation, environment: telEnvironment),
            advertLocationPolicyRaw: advertPolicy,
            multiAcks: multiAcks
        )
    }

}

// MARK: - Post-Identity Resolution (testable seam)

/// Resolves the `radioID` that subsequent `importConfig` steps should use,
/// given whether `importIdentity` actually pushed a private key to the radio
/// and the late-bound reconciliation callback.
///
/// Extracted as a free function so it can be unit-tested without constructing
/// a real `NodeConfigService` (which would require a live `MeshCoreSession`).
internal func resolveEffectiveRadioID(
    original: UUID,
    didImportPrivateKey: Bool,
    callback: (@Sendable () async throws -> UUID?)?
) async throws -> UUID {
    guard didImportPrivateKey, let cb = callback else {
        return original
    }
    if let reconciled = try await cb() {
        return reconciled
    }
    return original
}

// MARK: - Execute Orchestration (testable seam)

/// The concrete device/database write operations `executeConfigImport` performs, one per closure.
/// `NodeConfigService` builds these from its services; tests build spies, so the destructive-path
/// sequencing can be exercised without a live `MeshCoreSession`.
struct ConfigImportWriters: Sendable {
    let importPrivateKey: @Sendable (Data) async throws -> Void
    let setNodeName: @Sendable (String) async throws -> Void
    let setLocation: @Sendable (_ latitude: Double, _ longitude: Double) async throws -> Void
    let setOtherParams: @Sendable (MeshCoreNodeConfig.OtherSettings) async throws -> Void
    let resolveEffectiveRadioID: @Sendable (_ original: UUID, _ didImportPrivateKey: Bool) async throws -> UUID
    let setRadioParams: @Sendable (MeshCoreNodeConfig.RadioSettings) async throws -> Void
    let setTxPower: @Sendable (Int8) async throws -> Void
    let setChannel: @Sendable (_ radioID: UUID, _ write: ConfigImportPlan.ChannelWrite) async throws -> Void
    let addContact: @Sendable (_ radioID: UUID, _ contact: MeshContact) async throws -> Void
}

/// Executes a validated `ConfigImportPlan` in safe order: identity (so a private-key import can
/// reassign the radioID before channels/contacts are keyed by it), then position, other params,
/// channels, contacts, and radio last. Each `progress(...)` is emitted only after its write
/// succeeds, so "no progress reported" reliably means "nothing was written" — a first-write failure
/// surfaces as a clean failure, not a partial one.
///
/// Extracted as a free function taking write closures (mirroring `resolveEffectiveRadioID`) so the
/// ordering, progress, and cancellation behavior are unit-testable without a live session.
internal func executeConfigImport(
    plan: ConfigImportPlan,
    sections: ConfigSections,
    radioID: UUID,
    writers: ConfigImportWriters,
    logger: Logger,
    onProgress: (@Sendable (ImportProgress) -> Void)?,
    notifyContactsChanged: @Sendable () async -> Void = {}
) async throws {
    let totalSteps = NodeConfigService.stepCount(for: plan)
    var currentStep = 0
    func progress(_ step: ImportStep) {
        currentStep += 1
        onProgress?(ImportProgress(step: step, current: currentStep, total: totalSteps))
    }
    func checkCancellation() throws {
        guard !Task.isCancelled else { throw CancellationError() }
    }

    var effectiveRadioID = radioID
    if let privateKey = plan.importPrivateKey {
        try checkCancellation()
        try await writers.importPrivateKey(privateKey)
        progress(.privateKey)
        logger.info("Imported private key")
    }
    if let name = plan.nodeName {
        try checkCancellation()
        try await writers.setNodeName(name)
        progress(.nodeName)
        logger.info("Set node name: \(name)")
    }
    if sections.nodeIdentity {
        effectiveRadioID = try await writers.resolveEffectiveRadioID(radioID, plan.importPrivateKey != nil)
        if effectiveRadioID != radioID {
            logger.info("Post-identity reconciliation reassigned radioID to \(effectiveRadioID)")
        }
    }

    if let position = plan.position {
        try checkCancellation()
        try await writers.setLocation(position.latitude, position.longitude)
        progress(.position)
        logger.info("Set position: \(position.latitude), \(position.longitude)")
    }

    if let other = plan.otherSettings {
        try checkCancellation()
        try await writers.setOtherParams(other)
        progress(.otherParameters)
        logger.info("Set other params")
    }

    for write in plan.channelWrites {
        try checkCancellation()
        try await writers.setChannel(effectiveRadioID, write)
        progress(.channel(name: write.name))
        logger.info("Set channel \(write.index): \(write.name)")
    }

    for contact in plan.contactRecords {
        try checkCancellation()
        try await writers.addContact(effectiveRadioID, contact)
        // writers.addContact only throws if the device add failed; a local-save failure is
        // logged and swallowed there, so reaching this line means the contact is on the
        // device and the progress yield must fire.
        progress(.contact(name: contact.advertisedName))
        logger.info("Imported contact: \(contact.advertisedName)")
    }
    if sections.contacts {
        // Refresh contacts as soon as they are written, before the radio step, so a later radio
        // failure does not suppress the UI update for contacts that already landed.
        await notifyContactsChanged()
    }

    // Radio goes last (minimizes mesh isolation on BLE disconnect).
    if let radio = plan.radioSettings {
        try checkCancellation()
        try await writers.setRadioParams(radio)
        progress(.radioParameters)
        logger.info("Set radio params")

        try checkCancellation()
        do {
            try await writers.setTxPower(radio.txPower)
        } catch {
            // Params already retuned the node; flag that power did not follow so the failure
            // isn't mistaken for "radio unchanged."
            logger.error("Radio params applied but TX power did not: \(error.localizedDescription)")
            throw error
        }
        progress(.txPower)
        logger.info("Set TX power: \(radio.txPower)")
    }
}

// MARK: - Static Builders (testable without actor)

extension NodeConfigService {
    /// Builds radio settings from SelfInfo.
    public static func buildRadioSettings(from info: SelfInfo) -> MeshCoreNodeConfig.RadioSettings {
        MeshCoreNodeConfig.RadioSettings(
            frequency: UInt32((info.radioFrequency * 1000).rounded()),
            bandwidth: UInt32((info.radioBandwidth * 1000).rounded()),
            spreadingFactor: info.radioSpreadingFactor,
            codingRate: info.radioCodingRate,
            txPower: info.txPower
        )
    }

    /// Builds other settings from SelfInfo, matching official companion app format.
    public static func buildOtherSettings(from info: SelfInfo) -> MeshCoreNodeConfig.OtherSettings {
        MeshCoreNodeConfig.OtherSettings(
            manualAddContacts: info.manualAddContacts ? 1 : 0,
            advertLocationPolicy: info.advertisementLocationPolicy
        )
    }

    /// Builds a contact config from a MeshContact.
    static func buildContactConfig(from contact: MeshContact) -> MeshCoreNodeConfig.ContactConfig {
        let outPath: String?
        if contact.isFloodPath {
            outPath = nil
        } else if contact.pathByteLength > 0 && !contact.outPath.isEmpty {
            outPath = contact.outPath.prefix(contact.pathByteLength).hexString
        } else {
            outPath = ""
        }

        // Extract hash mode from encoded outPathLength (upper 2 bits)
        let pathHashMode: UInt8? = contact.isFloodPath ? nil : contact.outPathLength >> 6

        return MeshCoreNodeConfig.ContactConfig(
            type: contact.typeRawValue,
            name: contact.advertisedName,
            publicKey: contact.publicKey.hexString,
            flags: contact.flags.rawValue,
            latitude: String(contact.latitude),
            longitude: String(contact.longitude),
            lastAdvert: UInt32(contact.lastAdvertisement.timeIntervalSince1970),
            lastModified: UInt32(contact.lastModified.timeIntervalSince1970),
            outPath: outPath,
            pathHashMode: pathHashMode
        )
    }
}
