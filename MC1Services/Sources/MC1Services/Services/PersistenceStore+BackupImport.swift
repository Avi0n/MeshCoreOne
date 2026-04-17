import Foundation
import SwiftData

private let backupLogger = PersistentLogger(subsystem: "com.mc1", category: "PersistenceStore.Backup")

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
        let channelResult = try batchInsertChannels(channels, radioIDs: allRadioIDs)
        result.record(
            .channels,
            inserted: channelResult.inserted,
            merged: channelResult.merged,
            skipped: channelResult.skipped
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
        result.record(.messages, inserted: messageResult.inserted, skipped: messageResult.skipped)

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
        result.record(.reactions, inserted: reactionResult.inserted, skipped: reactionResult.skipped)

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
    fileprivate func reconcileLastMessageDates(
        messages: [MessageDTO],
        affectedRoomSessionIDs: Set<UUID>
    ) throws {
        let affectedContactIDs = Set(messages.compactMap(\.contactID))
        if !affectedContactIDs.isEmpty {
            try refreshContactLastMessageDates(contactIDs: affectedContactIDs)
        }
        let affectedChannelRadioIDs = Set(messages.lazy.compactMap { $0.channelIndex != nil ? $0.radioID : nil })
        if !affectedChannelRadioIDs.isEmpty {
            try refreshChannelLastMessageDates(radioIDs: affectedChannelRadioIDs)
        }
        if !affectedRoomSessionIDs.isEmpty {
            try refreshRemoteNodeSessionLastMessageDates(sessionIDs: affectedRoomSessionIDs)
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

    /// Refreshes lastMessageDate on contacts by querying the actual Message table,
    /// so both pre-existing and newly imported messages are considered.
    public func refreshContactLastMessageDates(contactIDs: Set<UUID>) throws {
        guard !contactIDs.isEmpty else { return }
        try Task.checkCancellation()

        let idArray = Array(contactIDs)
        let contactPredicate = #Predicate<Contact> { idArray.contains($0.id) }
        let contacts = try modelContext.fetch(FetchDescriptor(predicate: contactPredicate))

        let messagePredicate = #Predicate<Message> { msg in
            if let cid = msg.contactID {
                return idArray.contains(cid)
            } else {
                return false
            }
        }
        let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))

        var latestByContactID: [UUID: Date] = [:]
        for (offset, message) in messages.enumerated() {
            if offset % cancellationCheckStride == 0 {
                try Task.checkCancellation()
            }
            guard let cid = message.contactID else { continue }
            if let existing = latestByContactID[cid], existing >= message.createdAt { continue }
            latestByContactID[cid] = message.createdAt
        }

        for contact in contacts {
            guard let latest = latestByContactID[contact.id] else { continue }
            if contact.lastMessageDate == nil || contact.lastMessageDate! < latest {
                contact.lastMessageDate = latest
            }
        }
    }

    /// Refreshes lastMessageDate on channels by querying the actual Message table,
    /// so both pre-existing and newly imported messages are considered.
    public func refreshChannelLastMessageDates(radioIDs: Set<UUID>) throws {
        guard !radioIDs.isEmpty else { return }
        try Task.checkCancellation()

        let radioIDArray = Array(radioIDs)
        let channelPredicate = #Predicate<Channel> { radioIDArray.contains($0.radioID) }
        let channels = try modelContext.fetch(FetchDescriptor(predicate: channelPredicate))

        let messagePredicate = #Predicate<Message> { msg in
            radioIDArray.contains(msg.radioID) && msg.channelIndex != nil
        }
        let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))

        var latestByChannelKey: [String: Date] = [:]
        for (offset, message) in messages.enumerated() {
            if offset % cancellationCheckStride == 0 {
                try Task.checkCancellation()
            }
            guard let idx = message.channelIndex else { continue }
            let key = channelKey(radioID: message.radioID, index: idx)
            if let existing = latestByChannelKey[key], existing >= message.createdAt { continue }
            latestByChannelKey[key] = message.createdAt
        }

        for channel in channels {
            let key = channelKey(radioID: channel.radioID, index: channel.index)
            guard let latest = latestByChannelKey[key] else { continue }
            if channel.lastMessageDate == nil || channel.lastMessageDate! < latest {
                channel.lastMessageDate = latest
            }
        }
    }

    /// Refreshes lastMessageDate on room sessions by querying the actual RoomMessage table,
    /// so both pre-existing and newly imported room messages are considered.
    public func refreshRemoteNodeSessionLastMessageDates(sessionIDs: Set<UUID>) throws {
        guard !sessionIDs.isEmpty else { return }
        try Task.checkCancellation()

        let idArray = Array(sessionIDs)
        let sessionPredicate = #Predicate<RemoteNodeSession> { idArray.contains($0.id) }
        let sessions = try modelContext.fetch(FetchDescriptor(predicate: sessionPredicate))

        let messagePredicate = #Predicate<RoomMessage> { idArray.contains($0.sessionID) }
        let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))

        var latestBySessionID: [UUID: Date] = [:]
        for (offset, message) in messages.enumerated() {
            if offset % cancellationCheckStride == 0 {
                try Task.checkCancellation()
            }
            if let existing = latestBySessionID[message.sessionID], existing >= message.createdAt { continue }
            latestBySessionID[message.sessionID] = message.createdAt
        }

        for session in sessions {
            guard let latest = latestBySessionID[session.id] else { continue }
            if session.lastMessageDate == nil || session.lastMessageDate! < latest {
                session.lastMessageDate = latest
            }
        }
    }
}
