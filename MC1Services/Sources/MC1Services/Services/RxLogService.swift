// MC1Services/Sources/MC1Services/Services/RxLogService.swift
import Foundation
import MeshCore
import OSLog

private let logger = PersistentLogger(subsystem: "com.mc1.services", category: "RxLogService")

/// Actor that processes RX log events, decodes channel messages, and persists to database.
public actor RxLogService {
    private let session: MeshCoreSession
    private let dataStore: PersistenceStore
    private var deviceID: UUID?

    // Caches for fast lookup
    private var channelSecrets: [UInt8: Data] = [:]  // channelIndex -> secret
    private var channelNames: [UInt8: String] = [:]   // channelIndex -> name
    private var contactNames: [Data: String] = [:]    // pubkey prefix -> name

    // Crypto keys for direct message decryption
    private var myPrivateKey: Data?
    private var contactPublicKeysByPrefix: [UInt8: [Data]] = [:]  // 1-byte prefix -> array of 32-byte public keys

    // Stream for UI updates
    private var streamContinuation: AsyncStream<RxLogEntryDTO>.Continuation?

    /// Called when any RF packet is received, for Live Activity freshness tracking
    private var onPacketReceived: (@Sendable @MainActor () -> Void)?

    // Event monitoring
    private var eventMonitorTask: Task<Void, Never>?

    // Heard repeats processing
    private var heardRepeatsService: HeardRepeatsService?

    // Reentrancy guards for reprocessing (separate to avoid mutual blocking)
    private var isReprocessingChannels = false
    private var isReprocessingDMs = false

    public init(session: MeshCoreSession, dataStore: PersistenceStore) {
        self.session = session
        self.dataStore = dataStore
    }

    /// Sets the HeardRepeatsService for processing channel message repeats.
    public func setHeardRepeatsService(_ service: HeardRepeatsService) {
        self.heardRepeatsService = service
    }

    /// Sets the callback invoked when any RF packet is received.
    public func setPacketReceivedHandler(_ handler: (@Sendable @MainActor () -> Void)?) {
        onPacketReceived = handler
    }

    /// Whether a heard repeats service has been wired via `setHeardRepeatsService`.
    var hasHeardRepeatsServiceWired: Bool { heardRepeatsService != nil }

    deinit {
        eventMonitorTask?.cancel()
    }

    // MARK: - Event Monitoring

    /// Start monitoring for RX log events from MeshCore.
    public func startEventMonitoring(deviceID: UUID) {
        self.deviceID = deviceID
        eventMonitorTask?.cancel()

        eventMonitorTask = Task { [weak self] in
            guard let self else { return }

            // Load secrets from database before entering event loop
            // This eliminates the race condition where events arrive before secrets are synced
            await self.loadSecretsFromDatabase(deviceID: deviceID)

            let events = await session.events()

            for await event in events {
                guard !Task.isCancelled else { break }
                if case .rxLogData(let parsed) = event {
                    await self.process(parsed)
                }
            }
        }
    }

    /// Load channel secrets and contact public keys from database to enable decryption before sync completes.
    private func loadSecretsFromDatabase(deviceID: UUID) async {
        do {
            let channels = try await dataStore.fetchChannels(deviceID: deviceID)
            channelSecrets = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.secret) })
            channelNames = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.name) })
            if !channels.isEmpty {
                logger.info("Loaded \(channels.count) channel secrets from database")
            }
        } catch {
            logger.error("Failed to load channel secrets: \(error.localizedDescription)")
        }

        do {
            let publicKeys = try await dataStore.fetchContactPublicKeysByPrefix(deviceID: deviceID)
            if !publicKeys.isEmpty {
                contactPublicKeysByPrefix = Self.convertPublicKeysToX25519(publicKeys)
                logger.info("Loaded \(publicKeys.count) contact public key prefixes from database")
            }
        } catch {
            logger.error("Failed to load contact public keys: \(error.localizedDescription)")
        }
    }

    /// Stop monitoring events.
    public func stopEventMonitoring() {
        eventMonitorTask?.cancel()
        eventMonitorTask = nil
    }

    /// Returns a stream of new entries.
    /// - Note: Only one active subscriber is supported. Subsequent calls replace the previous subscriber.
    public func entryStream() -> AsyncStream<RxLogEntryDTO> {
        AsyncStream { continuation in
            Task { self.setContinuation(continuation) }
            continuation.onTermination = { @Sendable _ in
                Task { await self.clearContinuation() }
            }
        }
    }

    private func setContinuation(_ continuation: AsyncStream<RxLogEntryDTO>.Continuation) {
        if streamContinuation != nil {
            logger.warning("Replacing existing RX log stream subscriber")
        }
        streamContinuation?.finish()
        self.streamContinuation = continuation
    }

    private func clearContinuation() {
        streamContinuation = nil
    }

    /// Update channel cache (secrets and names).
    /// Re-processes any recent noMatchingKey entries when secrets are provided.
    public func updateChannels(secrets: [UInt8: Data], names: [UInt8: String]) async {
        channelSecrets = secrets
        channelNames = names

        if !secrets.isEmpty {
            await reprocessNoMatchingKeyEntries()
        }
    }

    /// Re-process recent entries that failed decryption due to missing keys.
    /// Uses a reentrancy guard to prevent overlapping reprocessing.
    private func reprocessNoMatchingKeyEntries() async {
        guard !isReprocessingChannels else { return }
        isReprocessingChannels = true
        defer { isReprocessingChannels = false }

        guard let deviceID else { return }

        let cutoff = Date().addingTimeInterval(-60)

        do {
            let entries = try await dataStore.fetchRecentEntriesByDecryptStatus(
                deviceID: deviceID,
                status: .noMatchingKey,
                since: cutoff
            )

            guard !entries.isEmpty else { return }
            logger.info("Re-processing \(entries.count) noMatchingKey entries")

            // Collect successful decryptions for batch update
            var updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)] = []
            var decryptedEntries: [RxLogEntryDTO] = []

            for entry in entries {
                guard !Task.isCancelled else { break }

                let decrypted = decryptEntry(entry)
                guard decrypted.decodedText != nil else { continue }

                updates.append((
                    id: entry.id,
                    channelIndex: decrypted.channelIndex,
                    channelName: channelNames[decrypted.channelIndex ?? 0],
                    senderTimestamp: decrypted.senderTimestamp
                ))
                decryptedEntries.append(decrypted)
            }

            // Batch update database (decodedText is @Transient, not persisted)
            if !updates.isEmpty {
                try await dataStore.batchUpdateRxLogDecryption(updates)

                // Process for heard repeats after DB update
                if let heardRepeatsService {
                    for entry in decryptedEntries {
                        guard !Task.isCancelled else { break }
                        await heardRepeatsService.processForRepeats(entry)
                    }
                }

                logger.info("Successfully re-processed \(updates.count) entries")
            }
        } catch {
            logger.error("Failed to re-process noMatchingKey entries: \(error.localizedDescription)")
        }
    }

    /// Re-process recent DM entries that failed decryption due to missing keys.
    /// Called when contact public keys or the device private key become available.
    private func reprocessDMEntries() async {
        guard !isReprocessingDMs else { return }
        isReprocessingDMs = true
        defer { isReprocessingDMs = false }

        guard let deviceID, let myPrivateKey else { return }
        guard !contactPublicKeysByPrefix.isEmpty else { return }

        let cutoff = Date().addingTimeInterval(-60)

        do {
            let entries = try await dataStore.fetchRecentEntriesByDecryptStatus(
                deviceID: deviceID,
                status: .dmNoMatchingKey,
                since: cutoff
            )

            guard !entries.isEmpty else { return }
            logger.info("Re-processing \(entries.count) DM entries for timestamp extraction")

            var updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)] = []

            for entry in entries {
                guard !Task.isCancelled else { break }

                // Extract sender prefix from packetPayload: [destHash:1][srcHash:1]...
                let payloadHashSize = 1
                guard entry.packetPayload.count >= payloadHashSize * 2,
                      entry.routeType == .direct || entry.routeType == .tcDirect else {
                    continue
                }
                let senderPrefix = entry.packetPayload[payloadHashSize]

                guard let candidateKeys = contactPublicKeysByPrefix[senderPrefix] else {
                    continue
                }

                for senderPublicKey in candidateKeys {
                    if let timestamp = DirectMessageCrypto.extractTimestamp(
                        payload: entry.packetPayload,
                        myPrivateKey: myPrivateKey,
                        senderPublicKey: senderPublicKey
                    ) {
                        updates.append((
                            id: entry.id,
                            channelIndex: nil,
                            channelName: nil,
                            senderTimestamp: timestamp
                        ))
                        break
                    }
                }
            }

            if !updates.isEmpty {
                try await dataStore.batchUpdateRxLogDecryption(updates)
                logger.info("Successfully re-processed \(updates.count) DM entries")
            }
        } catch {
            logger.error("Failed to re-process DM entries: \(error.localizedDescription)")
        }
    }

    /// Update contact names cache.
    public func updateContactNames(_ names: [Data: String]) {
        contactNames = names
    }

    /// Update device private key for direct message decryption.
    /// The exported key is 64 bytes: `[expanded_scalar:32][nonce:32]`.
    /// DirectMessageCrypto needs the 32-byte Curve25519 scalar (first half).
    public func updatePrivateKey(_ key: Data?) async {
        myPrivateKey = key.flatMap { $0.count >= 32 ? Data($0.prefix(32)) : nil }
        if myPrivateKey != nil {
            await reprocessDMEntries()
        }
    }

    /// Update contact public keys for direct message decryption.
    /// Called when contacts sync completes. Re-processes any recent DM entries.
    /// Input keys are Ed25519 public keys; converted to Curve25519 for ECDH.
    public func updateContactPublicKeys(_ keys: [UInt8: [Data]]) async {
        contactPublicKeysByPrefix = Self.convertPublicKeysToX25519(keys)
        if !contactPublicKeysByPrefix.isEmpty {
            await reprocessDMEntries()
        }
    }

    /// Convert Ed25519 contact public keys to Curve25519 for DirectMessageCrypto.
    private static func convertPublicKeysToX25519(_ keys: [UInt8: [Data]]) -> [UInt8: [Data]] {
        var converted: [UInt8: [Data]] = [:]
        for (prefix, publicKeys) in keys {
            let x25519Keys = publicKeys.compactMap { Ed25519ToX25519.convertPublicKey($0) }
            if !x25519Keys.isEmpty {
                converted[prefix] = x25519Keys
            }
        }
        return converted
    }

    /// Process a parsed RX log event.
    public func process(_ parsed: ParsedRxLogData) async {
        guard let deviceID else { return }

        // Decode channel message if applicable
        var channelIndex: UInt8?
        var channelName: String?
        var decryptStatus = DecryptStatus.notApplicable
        var decodedText: String?
        var senderTimestamp: UInt32?
        var fromContactName: String?

        if parsed.payloadType == .groupText || parsed.payloadType == .groupData {
            // Channel payload format: [channelHash: 1B] [MAC: 2B] [ciphertext: NB]
            // The first byte is a truncated channel hash (not the index), so we must
            // try all known secrets to find the one where MAC validates.
            let rawPayload = parsed.packetPayload

            // Need at least: 1 (channel hash) + 2 (MAC) + 16 (min ciphertext block)
            if rawPayload.count >= 1 + ChannelCrypto.macSize + 16 {
                let encryptedPayload = Data(rawPayload.dropFirst(1))

                for (index, secret) in self.channelSecrets {
                    let result = ChannelCrypto.decrypt(payload: encryptedPayload, secret: secret)
                    if case .success(let timestamp, _, let text) = result {
                        channelIndex = index
                        channelName = channelNames[index] ?? "Channel \(index)"
                        decryptStatus = .success
                        senderTimestamp = timestamp
                        decodedText = text
                        break
                    }
                }

                if decryptStatus == .notApplicable {
                    decryptStatus = .noMatchingKey
                }
            } else {
                decryptStatus = .pending
            }
        }

        // Decrypt direct text messages to extract senderTimestamp
        if parsed.payloadType == .textMessage,
           parsed.routeType == .direct || parsed.routeType == .tcDirect {

            if let senderPrefix = parsed.senderPubkeyPrefix?.first,
               let candidateKeys = contactPublicKeysByPrefix[senderPrefix],
               let myPrivateKey = self.myPrivateKey {

                for senderPublicKey in candidateKeys {
                    if let timestamp = DirectMessageCrypto.extractTimestamp(
                        payload: parsed.packetPayload,
                        myPrivateKey: myPrivateKey,
                        senderPublicKey: senderPublicKey
                    ) {
                        senderTimestamp = timestamp
                        decryptStatus = .success
                        logger.debug("Decrypted direct message senderTimestamp: \(timestamp)")
                        break
                    }
                }
            }

            if senderTimestamp == nil {
                decryptStatus = .dmNoMatchingKey
                logger.debug("DM decryption failed, marking as dmNoMatchingKey for reprocessing")
            }
        }

        // Resolve contact name from sender pubkey prefix (direct messages)
        if let senderPrefix = parsed.senderPubkeyPrefix {
            fromContactName = contactNames.first { storedPrefix, _ in
                storedPrefix.starts(with: senderPrefix) || senderPrefix.starts(with: storedPrefix)
            }?.value
        }

        // Create DTO
        let dto = RxLogEntryDTO(
            deviceID: deviceID,
            from: parsed,
            channelIndex: channelIndex,
            channelName: channelName,
            decryptStatus: decryptStatus,
            fromContactName: fromContactName,
            senderTimestamp: senderTimestamp,
            decodedText: decodedText
        )

        // Persist
        do {
            try await dataStore.saveRxLogEntry(dto)
            try await dataStore.pruneRxLogEntries(deviceID: deviceID)
        } catch {
            logger.error("Failed to save RX log entry: \(error.localizedDescription)")
        }

        // Emit to stream
        streamContinuation?.yield(dto)

        if let onPacketReceived {
            await onPacketReceived()
        }

        // Process for heard repeats (inline await provides natural backpressure,
        // preventing unbounded Task accumulation under high RX volume)
        if let heardRepeatsService = self.heardRepeatsService {
            await heardRepeatsService.processForRepeats(dto)
        }
    }

    /// Load existing entries from database, re-decrypting payloads with current secrets.
    public func loadExistingEntries() async -> [RxLogEntryDTO] {
        guard let deviceID else { return [] }
        do {
            let entries = try await dataStore.fetchRxLogEntries(deviceID: deviceID)
            return entries.map { decryptEntry($0) }
        } catch {
            logger.error("Failed to load RX log entries: \(error.localizedDescription)")
            return []
        }
    }

    // MARK: - Decryption

    /// Attempt to decrypt a channel message entry using current secrets.
    /// Returns a copy of the entry with `decodedText` populated if decryption succeeds.
    /// This is reusable for export and other features that need decrypted content.
    ///
    /// If the entry was previously decrypted (`decryptStatus == .success`),
    /// we use the stored channel index for O(1) secret lookup instead of trying all keys.
    public func decryptEntry(_ entry: RxLogEntryDTO) -> RxLogEntryDTO {
        var result = entry

        // Only attempt decryption for channel messages
        guard entry.payloadType == .groupText || entry.payloadType == .groupData else {
            return result
        }

        // Skip if payload is too small
        guard entry.packetPayload.count >= 1 + ChannelCrypto.macSize + 16 else {
            return result
        }

        // Channel payload format: [channelHash: 1B] [MAC: 2B] [ciphertext: NB]
        let encryptedPayload = Data(entry.packetPayload.dropFirst(1))

        // Fast path: use stored channel index if previously decrypted successfully
        if entry.decryptStatus == .success, let channelIndex = entry.channelIndex,
           let secret = channelSecrets[channelIndex] {
            let decryptResult = ChannelCrypto.decrypt(payload: encryptedPayload, secret: secret)
            if case .success(let timestamp, _, let text) = decryptResult {
                result.senderTimestamp = timestamp
                result.decodedText = text
                return result
            }
        }

        // Slow path: try all secrets (for .noMatchingKey entries or if fast path failed)
        for (_, secret) in channelSecrets {
            let decryptResult = ChannelCrypto.decrypt(payload: encryptedPayload, secret: secret)
            if case .success(let timestamp, _, let text) = decryptResult {
                result.senderTimestamp = timestamp
                result.decodedText = text
                break
            }
        }

        return result
    }

    /// Decrypt multiple entries concurrently. Useful for batch export.
    /// Uses parallel processing for better performance with large datasets.
    public func decryptEntries(_ entries: [RxLogEntryDTO]) async -> [RxLogEntryDTO] {
        // Capture secrets for concurrent access
        let secrets = channelSecrets

        return await withTaskGroup(of: (Int, RxLogEntryDTO).self) { group in
            for (index, entry) in entries.enumerated() {
                group.addTask {
                    let decrypted = Self.decryptEntry(entry, secrets: secrets)
                    return (index, decrypted)
                }
            }

            var results = entries
            for await (index, decrypted) in group {
                results[index] = decrypted
            }
            return results
        }
    }

    /// Static decryption for concurrent use (no actor isolation).
    private static func decryptEntry(_ entry: RxLogEntryDTO, secrets: [UInt8: Data]) -> RxLogEntryDTO {
        var result = entry

        guard entry.payloadType == .groupText || entry.payloadType == .groupData else {
            return result
        }

        guard entry.packetPayload.count >= 1 + ChannelCrypto.macSize + 16 else {
            return result
        }

        let encryptedPayload = Data(entry.packetPayload.dropFirst(1))

        // Fast path: use stored channel index
        if entry.decryptStatus == .success, let channelIndex = entry.channelIndex,
           let secret = secrets[channelIndex] {
            let decryptResult = ChannelCrypto.decrypt(payload: encryptedPayload, secret: secret)
            if case .success(let timestamp, _, let text) = decryptResult {
                result.senderTimestamp = timestamp
                result.decodedText = text
                return result
            }
        }

        // Slow path: try all secrets
        for (_, secret) in secrets {
            let decryptResult = ChannelCrypto.decrypt(payload: encryptedPayload, secret: secret)
            if case .success(let timestamp, _, let text) = decryptResult {
                result.senderTimestamp = timestamp
                result.decodedText = text
                break
            }
        }

        return result
    }

    /// Clear all entries.
    public func clearEntries() async {
        guard let deviceID else { return }
        do {
            try await dataStore.clearRxLogEntries(deviceID: deviceID)
        } catch {
            logger.error("Failed to clear RX log entries: \(error.localizedDescription)")
        }
    }
}
