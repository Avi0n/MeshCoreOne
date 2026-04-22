import Foundation
import os
import SwiftData

extension PersistenceStore {

    private static let migrationLogger = Logger(subsystem: "com.pocketmesh.mc1services", category: "RadioIDMigration")
    private static let migrationKey = "hasPopulatedRadioIDs"

    /// One-time migration: populate radioID on all Devices and propagate to children,
    /// then backfill deduplicationKey on outgoing Messages with nil keys.
    public func performRadioIDMigration() throws {
        guard !UserDefaults.standard.bool(forKey: Self.migrationKey) else { return }

        // Step 1: For each Device, children's radioID column still contains the old BLE UUID
        // (device.id) due to the @Attribute(originalName: "deviceID") rename. Generate a new
        // radioID for each Device and propagate to all children.
        let devices = try modelContext.fetch(FetchDescriptor<Device>())

        let lastDeviceIDString = UserDefaults.standard.string(forKey: PersistenceKeys.lastConnectedDeviceID)
        let lastDeviceID = lastDeviceIDString.flatMap(UUID.init)
        var mappedRadioID: UUID?

        for device in devices {
            let newRadioID = UUID()
            let oldRadioID = device.id
            device.radioID = newRadioID

            if oldRadioID == lastDeviceID {
                mappedRadioID = newRadioID
            }

            let targetOldID = oldRadioID

            let contacts = try modelContext.fetch(FetchDescriptor<Contact>(predicate: #Predicate { $0.radioID == targetOldID }))
            for contact in contacts { contact.radioID = newRadioID }

            let channels = try modelContext.fetch(FetchDescriptor<Channel>(predicate: #Predicate { $0.radioID == targetOldID }))
            for channel in channels { channel.radioID = newRadioID }

            let messages = try modelContext.fetch(FetchDescriptor<Message>(predicate: #Predicate { $0.radioID == targetOldID }))
            for message in messages { message.radioID = newRadioID }

            let reactions = try modelContext.fetch(FetchDescriptor<Reaction>(predicate: #Predicate { $0.radioID == targetOldID }))
            for reaction in reactions { reaction.radioID = newRadioID }

            let sessions = try modelContext.fetch(FetchDescriptor<RemoteNodeSession>(predicate: #Predicate { $0.radioID == targetOldID }))
            for session in sessions { session.radioID = newRadioID }

            let paths = try modelContext.fetch(FetchDescriptor<SavedTracePath>(predicate: #Predicate { $0.radioID == targetOldID }))
            for path in paths { path.radioID = newRadioID }

            let nodes = try modelContext.fetch(FetchDescriptor<DiscoveredNode>(predicate: #Predicate { $0.radioID == targetOldID }))
            for node in nodes { node.radioID = newRadioID }

            let blocked = try modelContext.fetch(FetchDescriptor<BlockedChannelSender>(predicate: #Predicate { $0.radioID == targetOldID }))
            for sender in blocked { sender.radioID = newRadioID }

            let logs = try modelContext.fetch(FetchDescriptor<RxLogEntry>(predicate: #Predicate { $0.radioID == targetOldID }))
            for log in logs { log.radioID = newRadioID }
        }

        // Step 2: Backfill deduplicationKey on outgoing messages with nil keys.
        // Only outgoing (directionRawValue == 1); incoming messages get keys during re-sync.
        let outgoingDirection = MessageDirection.outgoing.rawValue
        let nilKeyPredicate = #Predicate<Message> { message in
            message.deduplicationKey == nil && message.directionRawValue == outgoingDirection
        }
        let messagesNeedingKeys = try modelContext.fetch(FetchDescriptor(predicate: nilKeyPredicate))

        for message in messagesNeedingKeys {
            message.deduplicationKey = DeduplicationKey.contentBased(
                contactID: message.contactID,
                channelIndex: message.channelIndex,
                senderNodeName: message.senderNodeName,
                timestamp: message.timestamp,
                content: message.text
            )
        }

        try modelContext.save()

        if let mappedRadioID {
            UserDefaults.standard.set(mappedRadioID.uuidString, forKey: PersistenceKeys.lastConnectedRadioID)
        } else if lastDeviceID != nil {
            Self.migrationLogger.warning("lastConnectedDeviceID did not match any stored device; lastConnectedRadioID not backfilled")
        }

        UserDefaults.standard.set(true, forKey: Self.migrationKey)

        Self.migrationLogger.info("radioID migration complete: \(devices.count) devices, \(messagesNeedingKeys.count) dedup keys backfilled")
    }

    /// Resets the migration flag (for testing only).
    public func resetRadioIDMigrationFlag() {
        UserDefaults.standard.removeObject(forKey: Self.migrationKey)
    }

    // MARK: - Channel flood scope corrective migration

    private static let floodScopeMigrationKey = "hasMigratedChannelFloodScope"
    private static let floodScopeMigrationLogger = Logger(
        subsystem: "com.pocketmesh.mc1services",
        category: "ChannelFloodScopeMigration"
    )

    /// One-time migration: pre-existing rows were persisted before the flood-scope mode
    /// column existed and all come up with the default `.inherit` value, even when they
    /// had a per-channel `regionScope` override. Promote those to `.specific` so the
    /// user's prior choice keeps working. Rows whose `regionScope` was nil keep
    /// `.inherit` — corrective semantics, so the device default applies.
    public func performChannelFloodScopeMigration() throws {
        guard !UserDefaults.standard.bool(forKey: Self.floodScopeMigrationKey) else { return }

        let inheritRaw = ChannelFloodScopeStorage.Mode.inherit.rawValue
        let specificRaw = ChannelFloodScopeStorage.Mode.specific.rawValue

        let predicate = #Predicate<Channel> { channel in
            channel.regionScope != nil && channel.floodScopeModeRawValue == inheritRaw
        }
        let channels = try modelContext.fetch(FetchDescriptor(predicate: predicate))
        for channel in channels {
            channel.floodScopeModeRawValue = specificRaw
        }
        try modelContext.save()

        UserDefaults.standard.set(true, forKey: Self.floodScopeMigrationKey)

        Self.floodScopeMigrationLogger.info(
            "channel flood-scope migration complete: \(channels.count) rows promoted to .specific"
        )
    }

    /// Resets the migration flag (for testing only).
    public func resetChannelFloodScopeMigrationFlag() {
        UserDefaults.standard.removeObject(forKey: Self.floodScopeMigrationKey)
    }
}
