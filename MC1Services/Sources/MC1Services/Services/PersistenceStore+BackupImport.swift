import Foundation
import SwiftData

let backupLogger = PersistentLogger(subsystem: "com.mc1", category: "PersistenceStore.Backup")

/// Iteration interval at which the import reconciliation loops yield to a cancellation
/// check. Small enough to keep cancellation latency under a second on a busy backup;
/// large enough to avoid measurable overhead on small imports.
private let cancellationCheckStride = 500

// MARK: - Import Key Lookups (existingKeys)

extension PersistenceStore {

    /// Fetches every local Device in one pass and returns a publicKey → radioID map,
    /// which both `buildRadioIDMapping` and `batchInsertDevices` consume — avoiding the
    /// previous per-device `fetchDevice(publicKey:)` loop plus a second full-table scan.
    public func existingDeviceRadioIDsByPublicKey() throws -> [Data: UUID] {
        let descriptor = FetchDescriptor<Device>()
        let devices = try modelContext.fetch(descriptor)
        return Dictionary(devices.map { ($0.publicKey, $0.radioID) }, uniquingKeysWith: { first, _ in first })
    }

    /// Fetches all messages for the given radioIDs and returns both the dedup key set
    /// and the dedup-key-to-IDs mapping in a single pass (avoids two full-table scans).
    /// Outgoing messages key on identity; incoming messages share a content-based key
    /// only when they represent the same wire packet.
    public func existingMessageLookups(
        radioIDs: Set<UUID>
    ) throws -> (keys: Set<String>, idsByKey: [String: [UUID]]) {
        let radioIDArray = Array(radioIDs)
        guard !radioIDArray.isEmpty else { return ([], [:]) }
        let predicate = #Predicate<Message> { radioIDArray.contains($0.radioID) }
        let descriptor = FetchDescriptor<Message>(predicate: predicate)
        let messages = try modelContext.fetch(descriptor)
        var keys = Set<String>()
        var idsByKey: [String: [UUID]] = [:]
        for message in messages {
            let key = messageBackupKey(for: message)
            keys.insert(key)
            idsByKey[key, default: []].append(message.id)
        }
        return (keys, idsByKey)
    }

    /// Each `MessageRepeat` row represents a distinct hearing of a message, so keying
    /// on `id` (unique-by-construction) is sufficient and preserves multiple repeats
    /// that traverse the same route.
    public func existingMessageRepeatIDs(messageIDs: Set<UUID>) throws -> Set<UUID> {
        let messageIDArray = Array(messageIDs)
        guard !messageIDArray.isEmpty else { return [] }
        let repeats = try fetchInChunks(keys: messageIDArray) { chunk in
            let predicate = #Predicate<MessageRepeat> { chunk.contains($0.messageID) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }
        return Set(repeats.map(\.id))
    }

    public func existingReactionKeys(messageIDs: Set<UUID>) throws -> Set<String> {
        let messageIDArray = Array(messageIDs)
        guard !messageIDArray.isEmpty else { return [] }
        let reactions = try fetchInChunks(keys: messageIDArray) { chunk in
            let predicate = #Predicate<Reaction> { chunk.contains($0.messageID) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }
        return Set(reactions.map { reactionKey(messageID: $0.messageID, senderName: $0.senderName, emoji: $0.emoji) })
    }

    public func existingRoomMessageKeys(sessionIDs: Set<UUID>) throws -> Set<String> {
        let sessionIDArray = Array(sessionIDs)
        guard !sessionIDArray.isEmpty else { return [] }
        let predicate = #Predicate<RoomMessage> { sessionIDArray.contains($0.sessionID) }
        let descriptor = FetchDescriptor<RoomMessage>(predicate: predicate)
        let messages = try modelContext.fetch(descriptor)
        return Set(messages.map { roomMessageKey(sessionID: $0.sessionID, deduplicationKey: $0.deduplicationKey) })
    }

    public func existingBlockedSenderKeys(radioIDs: Set<UUID>) throws -> Set<String> {
        let radioIDArray = Array(radioIDs)
        guard !radioIDArray.isEmpty else { return [] }
        let predicate = #Predicate<BlockedChannelSender> { radioIDArray.contains($0.radioID) }
        let descriptor = FetchDescriptor<BlockedChannelSender>(predicate: predicate)
        let senders = try modelContext.fetch(descriptor)
        return Set(senders.map { blockedChannelSenderKey(radioID: $0.radioID, name: $0.name) })
    }

    public func existingNodeStatusSnapshotKeys() throws -> Set<String> {
        let descriptor = FetchDescriptor<NodeStatusSnapshot>()
        let snapshots = try modelContext.fetch(descriptor)
        return Set(snapshots.map {
            nodeStatusSnapshotKey(nodePublicKey: $0.nodePublicKey, timestamp: $0.timestamp)
        })
    }
}

// MARK: - Import

extension PersistenceStore {

    /// Result of matching backup devices to local devices by publicKey.
    fileprivate struct RadioIDMapping {
        let mapping: [UUID: UUID]
        let unmatchedDevices: [DeviceDTO]
        let duplicateDeviceCount: Int
    }

    /// Imports the SwiftData-backed portion of a backup in one store-actor turn.
    ///
    /// Running the whole database import inside `PersistenceStore` prevents other
    /// live-store writes from interleaving between awaited hops, and the `defer`
    /// cleanup guarantees autosave state is restored even if the batch fails.
    @discardableResult
    func importBackupDatabase(
        _ envelope: AppBackupEnvelope
    ) throws -> ImportResult {
        var result = ImportResult()
        let originalAutosaveEnabled = modelContext.autosaveEnabled
        var didCommit = false

        modelContext.autosaveEnabled = false

        defer {
            if !didCommit {
                modelContext.rollback()
            }
            modelContext.autosaveEnabled = originalAutosaveEnabled
        }

        let localDeviceRadioIDsByPublicKey = try existingDeviceRadioIDsByPublicKey()
        let radioMap = buildRadioIDMapping(
            from: envelope,
            localDeviceRadioIDsByPublicKey: localDeviceRadioIDsByPublicKey
        )
        if radioMap.duplicateDeviceCount > 0 {
            backupLogger.warning(
                "Backup contained \(radioMap.duplicateDeviceCount) duplicate device public key(s); first occurrence wins."
            )
            result.record(.devices, skipped: radioMap.duplicateDeviceCount)
        }

        var contacts = envelope.contacts
        var channels = envelope.channels
        var messages = envelope.messages
        var reactions = envelope.reactions
        var sessions = envelope.remoteNodeSessions
        var tracePaths = envelope.savedTracePaths
        var blockedSenders = envelope.blockedChannelSenders
        var messageRepeats = envelope.messageRepeats
        var roomMessages = envelope.roomMessages

        // Skip the rewrite when every mapping entry is identity (same-device restore),
        // avoiding seven copy-on-write copies.
        if radioMap.mapping.contains(where: { $0.key != $0.value }) {
            applyRadioIDMapping(radioMap.mapping, to: &contacts, keyPath: \.radioID)
            applyRadioIDMapping(radioMap.mapping, to: &channels, keyPath: \.radioID)
            applyRadioIDMapping(radioMap.mapping, to: &messages, keyPath: \.radioID)
            applyRadioIDMapping(radioMap.mapping, to: &reactions, keyPath: \.radioID)
            applyRadioIDMapping(radioMap.mapping, to: &sessions, keyPath: \.radioID)
            applyRadioIDMapping(radioMap.mapping, to: &tracePaths, keyPath: \.radioID)
            applyRadioIDMapping(radioMap.mapping, to: &blockedSenders, keyPath: \.radioID)
        }

        try Task.checkCancellation()
        let deviceResult = try batchInsertDevices(
            radioMap.unmatchedDevices,
            existingKeys: Set(localDeviceRadioIDsByPublicKey.keys)
        )
        result.record(.devices, inserted: deviceResult.inserted, skipped: deviceResult.skipped)

        var allRadioIDs = Set(radioMap.mapping.values)
        allRadioIDs.formUnion(contacts.map(\.radioID))
        allRadioIDs.formUnion(channels.map(\.radioID))
        allRadioIDs.formUnion(messages.map(\.radioID))
        allRadioIDs.formUnion(reactions.map(\.radioID))
        allRadioIDs.formUnion(sessions.map(\.radioID))
        allRadioIDs.formUnion(tracePaths.map(\.radioID))
        allRadioIDs.formUnion(blockedSenders.map(\.radioID))

        let contactResult = try batchInsertContacts(contacts, radioIDs: allRadioIDs)
        result.record(
            .contacts,
            inserted: contactResult.inserted,
            merged: contactResult.merged,
            skipped: contactResult.skipped
        )

        let contactIDMapping = buildContactIDMapping(
            contacts: contacts,
            contactIDsByKey: contactResult.contactIDsByKey
        )
        applyContactIDMapping(contactIDMapping, toMessages: &messages, toReactions: &reactions)

        try Task.checkCancellation()
        let maxChannelsByRadioID = maxChannelsByLocalRadioID(
            devices: envelope.devices,
            radioMap: radioMap.mapping
        )
        let channelResult = try batchInsertChannels(
            channels,
            radioIDs: allRadioIDs,
            maxChannelsByRadioID: maxChannelsByRadioID
        )
        result.record(
            .channels,
            inserted: channelResult.inserted,
            merged: channelResult.merged,
            skipped: channelResult.skipped,
            dropped: channelResult.dropped
        )

        // Channels are now final, so rewrite channel-message slots before inserting
        // messages: relocated channels carry their messages to the new slot, and messages
        // for a dropped (no-free-slot) channel are removed rather than mis-associated.
        let droppedChildren = applyChannelIndexMapping(
            channelResult.channelIndexRemap,
            droppedChannelIndices: channelResult.droppedChannelIndices,
            toMessages: &messages,
            toReactions: &reactions
        )

        try Task.checkCancellation()
        let sessionResult = try batchInsertRemoteNodeSessions(sessions, radioIDs: allRadioIDs)
        result.record(
            .remoteNodeSessions,
            inserted: sessionResult.inserted,
            merged: sessionResult.merged,
            skipped: sessionResult.skipped
        )

        try Task.checkCancellation()
        let messageLookups = try existingMessageLookups(radioIDs: allRadioIDs)
        let messageResult = try batchInsertMessages(
            messages,
            existingKeys: messageLookups.keys,
            existingIDsByKey: messageLookups.idsByKey
        )
        result.record(.messages, inserted: messageResult.inserted, skipped: messageResult.skipped, dropped: droppedChildren.messages)

        let messageIDMapping = messageResult.messageIDByBackupID
        let allMessageIDs = Set(messages.map { messageIDMapping[$0.id] ?? $0.id })
        applyMessageIDMapping(messageIDMapping, toRepeats: &messageRepeats, toReactions: &reactions)

        let existingRepeatIDs = try existingMessageRepeatIDs(messageIDs: allMessageIDs)
        let repeatResult = try batchInsertMessageRepeats(
            messageRepeats,
            existingIDs: existingRepeatIDs,
            existingMessageIDs: allMessageIDs
        )
        result.record(.messageRepeats, inserted: repeatResult.inserted, skipped: repeatResult.skipped)

        let existingReactionKeys = try existingReactionKeys(messageIDs: allMessageIDs)
        let reactionResult = try batchInsertReactions(
            reactions,
            existingKeys: existingReactionKeys,
            existingMessageIDs: allMessageIDs
        )
        result.record(.reactions, inserted: reactionResult.inserted, skipped: reactionResult.skipped, dropped: droppedChildren.reactions)

        let affectedMessageIDs = repeatResult.affectedMessageIDs.union(reactionResult.affectedMessageIDs)
        try recomputeMessageCaches(messageIDs: affectedMessageIDs)

        let sessionIDMapping = buildSessionIDMapping(
            sessions: sessions,
            sessionIDsByKey: sessionResult.sessionIDsByKey
        )
        let allSessionIDs = Set(sessions.map { sessionIDMapping[$0.id] ?? $0.id })
        applySessionIDMapping(sessionIDMapping, toRoomMessages: &roomMessages)

        try Task.checkCancellation()
        let existingRoomMsgKeys = try existingRoomMessageKeys(sessionIDs: allSessionIDs)
        let roomMsgResult = try batchInsertRoomMessages(
            roomMessages,
            existingKeys: existingRoomMsgKeys,
            existingSessionIDs: allSessionIDs
        )
        result.record(.roomMessages, inserted: roomMsgResult.inserted, skipped: roomMsgResult.skipped)

        let traceResult = try batchInsertSavedTracePaths(tracePaths)
        result.record(
            .savedTracePaths,
            inserted: traceResult.inserted,
            merged: traceResult.merged,
            skipped: traceResult.skipped
        )

        let existingBlockedKeys = try existingBlockedSenderKeys(radioIDs: allRadioIDs)
        let blockedResult = try batchInsertBlockedChannelSenders(
            blockedSenders,
            existingKeys: existingBlockedKeys
        )
        result.record(.blockedChannelSenders, inserted: blockedResult.inserted, skipped: blockedResult.skipped)

        let existingSnapshotKeys = try existingNodeStatusSnapshotKeys()
        let snapshotResult = try batchInsertNodeStatusSnapshots(
            envelope.nodeStatusSnapshots,
            existingKeys: existingSnapshotKeys
        )
        result.record(.nodeStatusSnapshots, inserted: snapshotResult.inserted, skipped: snapshotResult.skipped)

        try reconcileLastMessageDates(
            messages: messages,
            roomMessages: roomMessages,
            affectedRoomSessionIDs: roomMsgResult.affectedSessionIDs
        )

        try Task.checkCancellation()
        #if DEBUG
        try backupImportFaultInjection?()
        #endif
        try modelContext.save()
        didCommit = true

        #if DEBUG
        backupImportPostCommitHook?()
        #endif

        return result
    }

    #if DEBUG
    public func setBackupImportFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
        backupImportFaultInjection = hook
    }

    public func setBackupImportPostCommitHook(_ hook: (@Sendable () -> Void)?) {
        backupImportPostCommitHook = hook
    }
    #endif
}

// MARK: - Import Mapping Helpers

extension PersistenceStore {

    /// Matches each backup device to a local device by publicKey, producing a
    /// radioID remap for child records. Duplicate publicKeys in the envelope
    /// (corruption) are counted so the caller can surface the skip.
    ///
    /// Keys are matched verbatim, with no empty/zero-key guard, because no MC1 export can
    /// produce an empty or all-zero `publicKey`: every persist path sources it from a 32-byte
    /// post-handshake `selfInfo.publicKey`, and `Device.init` requires it, so this branch only
    /// ever sees real, distinct keys. The guard is intentionally omitted — the only way to reach
    /// a collision (two devices carrying empty or zero `publicKey`s on distinct `radioID`s, which
    /// would collapse into one partition and merge two radios' rows) is a hand-edited file, which
    /// is out of scope for import validation.
    fileprivate func buildRadioIDMapping(
        from envelope: AppBackupEnvelope,
        localDeviceRadioIDsByPublicKey: [Data: UUID]
    ) -> RadioIDMapping {
        var mapping: [UUID: UUID] = [:]
        var unmatched: [DeviceDTO] = []
        var firstRadioIDByPublicKey: [Data: UUID] = [:]
        var duplicates = 0
        for backupDevice in envelope.devices {
            if let firstRadioID = firstRadioIDByPublicKey[backupDevice.publicKey] {
                duplicates += 1
                // Point the duplicate's radioID at the first occurrence's local mapping so
                // any child records keyed off it resolve to a Device row that actually exists.
                if backupDevice.radioID != firstRadioID, mapping[backupDevice.radioID] == nil,
                   let winningLocal = mapping[firstRadioID] {
                    mapping[backupDevice.radioID] = winningLocal
                }
                continue
            }
            firstRadioIDByPublicKey[backupDevice.publicKey] = backupDevice.radioID
            if let localRadioID = localDeviceRadioIDsByPublicKey[backupDevice.publicKey] {
                mapping[backupDevice.radioID] = localRadioID
            } else {
                mapping[backupDevice.radioID] = backupDevice.radioID
                unmatched.append(backupDevice)
            }
        }
        return RadioIDMapping(
            mapping: mapping,
            unmatchedDevices: unmatched,
            duplicateDeviceCount: duplicates
        )
    }

    /// Rewrites a UUID field on every element of `dtos` through `mapping`.
    /// Leaves elements whose current value isn't in the mapping untouched.
    fileprivate func applyRadioIDMapping<T>(
        _ mapping: [UUID: UUID],
        to dtos: inout [T],
        keyPath: WritableKeyPath<T, UUID>
    ) {
        for i in dtos.indices {
            let current = dtos[i][keyPath: keyPath]
            if let remapped = mapping[current] {
                dtos[i][keyPath: keyPath] = remapped
            }
        }
    }

    /// Returns backup-contact-ID → local-contact-ID entries for contacts that
    /// merged into an existing local row (identity mappings are omitted).
    fileprivate func buildContactIDMapping(
        contacts: [ContactDTO],
        contactIDsByKey: [String: UUID]
    ) -> [UUID: UUID] {
        var mapping: [UUID: UUID] = [:]
        for contact in contacts {
            let key = contactKey(radioID: contact.radioID, publicKey: contact.publicKey)
            if let localID = contactIDsByKey[key], localID != contact.id {
                mapping[contact.id] = localID
            }
        }
        return mapping
    }

    /// Rewrites `contactID` on messages (including DM dedup keys) and reactions.
    fileprivate func applyContactIDMapping(
        _ mapping: [UUID: UUID],
        toMessages messages: inout [MessageDTO],
        toReactions reactions: inout [ReactionDTO]
    ) {
        guard !mapping.isEmpty else { return }
        for i in messages.indices {
            guard let backupID = messages[i].contactID,
                  let localID = mapping[backupID] else { continue }
            messages[i].contactID = localID
            if let dedupKey = messages[i].deduplicationKey {
                messages[i].deduplicationKey = rewriteDMDeduplicationKey(dedupKey, from: backupID, to: localID)
            }
        }
        for i in reactions.indices {
            guard let backupID = reactions[i].contactID,
                  let localID = mapping[backupID] else { continue }
            reactions[i].contactID = localID
        }
    }

    /// Resolves each device's channel capacity to the local radioID it maps to, so the
    /// channel reconciler can bound free-slot search by `maxChannels`. The envelope's
    /// device radioIDs are pre-remap; `radioMap` rewrites them to the local partition key,
    /// matching the radioID the channel DTOs were already remapped onto.
    fileprivate func maxChannelsByLocalRadioID(
        devices: [DeviceDTO],
        radioMap: [UUID: UUID]
    ) -> [UUID: UInt8] {
        var result: [UUID: UInt8] = [:]
        for device in devices {
            let localRadioID = radioMap[device.radioID] ?? device.radioID
            // First occurrence wins, consistent with duplicate-device handling upstream.
            if result[localRadioID] == nil {
                result[localRadioID] = device.maxChannels
            }
        }
        return result
    }

    /// Rewrites `channelIndex` on channel messages and reactions to the slot their channel
    /// was placed at locally, rewriting the content-based channel dedup key in lockstep so
    /// a second import of the same backup still deduplicates. Messages and reactions
    /// belonging to a channel that had no free local slot are dropped, since no placement
    /// exists to attach them to. Mirrors how ``applyContactIDMapping`` rewrites the DM
    /// dedup key alongside the remapped identifier.
    @discardableResult
    fileprivate func applyChannelIndexMapping(
        _ remap: [UUID: [UInt8: UInt8]],
        droppedChannelIndices: [UUID: Set<UInt8>],
        toMessages messages: inout [MessageDTO],
        toReactions reactions: inout [ReactionDTO]
    ) -> (messages: Int, reactions: Int) {
        guard !remap.isEmpty || !droppedChannelIndices.isEmpty else { return (0, 0) }

        var droppedMessages = 0
        var droppedReactions = 0
        if !droppedChannelIndices.isEmpty {
            let beforeMessages = messages.count
            messages.removeAll { dto in
                guard let index = dto.channelIndex else { return false }
                return droppedChannelIndices[dto.radioID]?.contains(index) ?? false
            }
            droppedMessages = beforeMessages - messages.count

            let beforeReactions = reactions.count
            reactions.removeAll { dto in
                guard let index = dto.channelIndex else { return false }
                return droppedChannelIndices[dto.radioID]?.contains(index) ?? false
            }
            droppedReactions = beforeReactions - reactions.count
        }

        if !remap.isEmpty {
            for i in messages.indices {
                guard let backupIndex = messages[i].channelIndex,
                      let localIndex = remap[messages[i].radioID]?[backupIndex] else { continue }
                messages[i].channelIndex = localIndex
                if let dedupKey = messages[i].deduplicationKey {
                    messages[i].deduplicationKey = rewriteChannelDeduplicationKey(
                        dedupKey, from: backupIndex, to: localIndex
                    )
                }
            }
            for i in reactions.indices {
                guard let backupIndex = reactions[i].channelIndex,
                      let localIndex = remap[reactions[i].radioID]?[backupIndex] else { continue }
                reactions[i].channelIndex = localIndex
            }
        }
        return (droppedMessages, droppedReactions)
    }

    /// Rewrites `messageID` on both message repeats and reactions so they
    /// reference the local (post-merge) parent.
    fileprivate func applyMessageIDMapping(
        _ mapping: [UUID: UUID],
        toRepeats repeats: inout [MessageRepeatDTO],
        toReactions reactions: inout [ReactionDTO]
    ) {
        guard !mapping.isEmpty else { return }
        for i in repeats.indices {
            if let localID = mapping[repeats[i].messageID] {
                repeats[i].messageID = localID
            }
        }
        for i in reactions.indices {
            if let localID = mapping[reactions[i].messageID] {
                reactions[i].messageID = localID
            }
        }
    }

    /// Returns backup-session-ID → local-session-ID entries for sessions
    /// that merged into an existing local row (identity mappings omitted).
    fileprivate func buildSessionIDMapping(
        sessions: [RemoteNodeSessionDTO],
        sessionIDsByKey: [String: UUID]
    ) -> [UUID: UUID] {
        var mapping: [UUID: UUID] = [:]
        for session in sessions {
            let key = remoteNodeSessionKey(radioID: session.radioID, publicKey: session.publicKey)
            if let localID = sessionIDsByKey[key], localID != session.id {
                mapping[session.id] = localID
            }
        }
        return mapping
    }

    /// Rewrites `sessionID` on room messages so they attach to the local
    /// (post-merge) session row.
    fileprivate func applySessionIDMapping(
        _ mapping: [UUID: UUID],
        toRoomMessages roomMessages: inout [RoomMessageDTO]
    ) {
        guard !mapping.isEmpty else { return }
        for i in roomMessages.indices {
            if let localID = mapping[roomMessages[i].sessionID] {
                roomMessages[i].sessionID = localID
            }
        }
    }

    /// Refreshes `lastMessageDate` on contacts, channels, and remote-node
    /// sessions that received new rows during this import.
    ///
    /// The max date per target is computed from the imported DTOs in-memory,
    /// so the store only fetches the contact/channel/session rows it needs
    /// to mutate. Duplicates in the envelope are harmless: their timestamps
    /// are already reflected in the pre-existing `lastMessageDate`, and the
    /// max-vs-current check filters them out.
    fileprivate func reconcileLastMessageDates(
        messages: [MessageDTO],
        roomMessages: [RoomMessageDTO],
        affectedRoomSessionIDs: Set<UUID>
    ) throws {
        var contactMaxDates: [UUID: Date] = [:]
        var channelMaxDates: [UUID: [UInt8: Date]] = [:]
        for message in messages {
            if let contactID = message.contactID {
                let existing = contactMaxDates[contactID]
                if existing == nil || existing! < message.createdAt {
                    contactMaxDates[contactID] = message.createdAt
                }
            }
            if let index = message.channelIndex {
                let existing = channelMaxDates[message.radioID]?[index]
                if existing == nil || existing! < message.createdAt {
                    channelMaxDates[message.radioID, default: [:]][index] = message.createdAt
                }
            }
        }

        if !contactMaxDates.isEmpty {
            try applyLastMessageDatesToContacts(contactMaxDates)
        }
        if !channelMaxDates.isEmpty {
            try applyLastMessageDatesToChannels(channelMaxDates)
        }

        guard !affectedRoomSessionIDs.isEmpty else { return }
        var sessionMaxDates: [UUID: Date] = [:]
        for roomMessage in roomMessages where affectedRoomSessionIDs.contains(roomMessage.sessionID) {
            let existing = sessionMaxDates[roomMessage.sessionID]
            if existing == nil || existing! < roomMessage.createdAt {
                sessionMaxDates[roomMessage.sessionID] = roomMessage.createdAt
            }
        }
        if !sessionMaxDates.isEmpty {
            try applyLastMessageDatesToRemoteNodeSessions(sessionMaxDates)
        }
    }
}

// MARK: - Import Reconciliation

extension PersistenceStore {

    /// Recomputes cached heardRepeats count and reactionSummary for messages
    /// that received new child rows during import.
    public func recomputeMessageCaches(messageIDs: Set<UUID>) throws {
        guard !messageIDs.isEmpty else { return }
        try Task.checkCancellation()
        let idArray = Array(messageIDs)

        let messages = try fetchInChunks(keys: idArray) { chunk in
            let predicate = #Predicate<Message> { chunk.contains($0.id) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }

        let allRepeats = try fetchInChunks(keys: idArray) { chunk in
            let predicate = #Predicate<MessageRepeat> { chunk.contains($0.messageID) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }
        var repeatCountsByMessageID: [UUID: Int] = [:]
        for r in allRepeats {
            repeatCountsByMessageID[r.messageID, default: 0] += 1
        }

        try Task.checkCancellation()
        let allReactions = try fetchInChunks(keys: idArray) { chunk in
            let predicate = #Predicate<Reaction> { chunk.contains($0.messageID) }
            return try modelContext.fetch(FetchDescriptor(predicate: predicate))
        }
        var reactionsByMessageID: [UUID: [Reaction]] = [:]
        for r in allReactions {
            reactionsByMessageID[r.messageID, default: []].append(r)
        }

        for (offset, message) in messages.enumerated() {
            if offset % cancellationCheckStride == 0 {
                try Task.checkCancellation()
            }
            let newHeardRepeats = repeatCountsByMessageID[message.id] ?? 0
            if message.heardRepeats != newHeardRepeats {
                message.heardRepeats = newHeardRepeats
            }

            let newSummary: String?
            if let reactions = reactionsByMessageID[message.id], !reactions.isEmpty {
                let reactionDTOs = reactions.map { ReactionDTO(from: $0) }
                newSummary = ReactionParser.buildSummary(from: reactionDTOs)
            } else {
                newSummary = nil
            }
            if message.reactionSummary != newSummary {
                message.reactionSummary = newSummary
            }
        }
    }

    /// Advances `Contact.lastMessageDate` toward the per-contact max timestamp
    /// from the just-imported messages. Existing values are preserved when
    /// they're already newer (e.g., a DTO imported out of order).
    fileprivate func applyLastMessageDatesToContacts(_ maxDates: [UUID: Date]) throws {
        try Task.checkCancellation()
        let idArray = Array(maxDates.keys)
        let predicate = #Predicate<Contact> { idArray.contains($0.id) }
        let contacts = try modelContext.fetch(FetchDescriptor(predicate: predicate))
        for contact in contacts {
            guard let latest = maxDates[contact.id] else { continue }
            if contact.lastMessageDate == nil || contact.lastMessageDate! < latest {
                contact.lastMessageDate = latest
            }
        }
    }

    /// Advances `Channel.lastMessageDate` using max timestamps keyed by
    /// `(radioID, channelIndex)`.
    fileprivate func applyLastMessageDatesToChannels(_ maxDates: [UUID: [UInt8: Date]]) throws {
        try Task.checkCancellation()
        let radioIDArray = Array(maxDates.keys)
        let predicate = #Predicate<Channel> { radioIDArray.contains($0.radioID) }
        let channels = try modelContext.fetch(FetchDescriptor(predicate: predicate))
        for channel in channels {
            guard let latest = maxDates[channel.radioID]?[channel.index] else { continue }
            if channel.lastMessageDate == nil || channel.lastMessageDate! < latest {
                channel.lastMessageDate = latest
            }
        }
    }

    /// Advances `RemoteNodeSession.lastMessageDate` using max timestamps
    /// keyed by session ID.
    fileprivate func applyLastMessageDatesToRemoteNodeSessions(_ maxDates: [UUID: Date]) throws {
        try Task.checkCancellation()
        let idArray = Array(maxDates.keys)
        let predicate = #Predicate<RemoteNodeSession> { idArray.contains($0.id) }
        let sessions = try modelContext.fetch(FetchDescriptor(predicate: predicate))
        for session in sessions {
            guard let latest = maxDates[session.id] else { continue }
            if session.lastMessageDate == nil || session.lastMessageDate! < latest {
                session.lastMessageDate = latest
            }
        }
    }
}
