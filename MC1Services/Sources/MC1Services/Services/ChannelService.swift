import Foundation
import CryptoKit
import MeshCore
import os

// MARK: - Channel Service Errors

public enum ChannelServiceError: Error, Sendable {
    case notConnected
    case channelNotFound
    case invalidChannelIndex
    case secretHashingFailed
    case saveFailed(String)
    case sendFailed(String)
    case sessionError(MeshCoreError)
    case syncAlreadyInProgress
    case circuitBreakerOpen(consecutiveFailures: Int)
}

extension ChannelServiceError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .notConnected: "Not connected to device."
        case .channelNotFound: "Channel not found."
        case .invalidChannelIndex: "Invalid channel index."
        case .secretHashingFailed: "Failed to hash channel secret."
        case .saveFailed(let msg): "Failed to save channel: \(msg)"
        case .sendFailed(let msg): "Send failed: \(msg)"
        case .sessionError(let e): e.localizedDescription
        case .syncAlreadyInProgress: "Channel sync is already in progress."
        case .circuitBreakerOpen(let n): "Channel sync suspended after \(n) consecutive failures."
        }
    }
}

// MARK: - Channel Sync Error Details

/// Detailed error information for a failed channel sync
public struct ChannelSyncError: Sendable, Equatable {
    public let index: UInt8
    public let errorType: ErrorType
    public let description: String

    public enum ErrorType: Sendable, Equatable {
        case timeout
        case deviceError(code: UInt8)
        case databaseError
        case unknown
    }

    public init(index: UInt8, errorType: ErrorType, description: String) {
        self.index = index
        self.errorType = errorType
        self.description = description
    }

    /// Whether this error type is potentially recoverable with retry
    public var isRetryable: Bool {
        switch errorType {
        case .timeout:
            return true
        case .deviceError, .databaseError, .unknown:
            return false
        }
    }
}

// MARK: - Channel Sync Result

public struct ChannelSyncResult: Sendable, Equatable {
    public let channelsSynced: Int
    public let errors: [ChannelSyncError]

    /// Whether sync completed without errors
    public var isComplete: Bool { errors.isEmpty }

    /// Indices of channels that failed with retryable errors
    public var retryableIndices: [UInt8] {
        errors.filter { $0.isRetryable }.map { $0.index }
    }

    public init(channelsSynced: Int, errors: [ChannelSyncError] = []) {
        self.channelsSynced = channelsSynced
        self.errors = errors
    }
}

// MARK: - Channel Service Actor

/// Actor-isolated service for channel (group) management.
/// Handles channel CRUD operations, secret hashing, and broadcast messaging.
public actor ChannelService {

    // MARK: - Properties

    private let session: MeshCoreSession
    private let dataStore: PersistenceStore
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "ChannelService")

    /// Callback for channel updates
    private var channelUpdateHandler: (@Sendable ([ChannelDTO]) -> Void)?

    /// Tracks whether a sync operation is in progress
    private var isSyncing = false

    // MARK: - Initialization

    public init(
        session: MeshCoreSession,
        dataStore: PersistenceStore
    ) {
        self.session = session
        self.dataStore = dataStore
    }

    // MARK: - Secret Hashing

    /// Hashes a passphrase into a 16-byte channel secret using SHA-256.
    /// The firmware uses the first 16 bytes of the SHA-256 hash.
    /// - Parameter passphrase: The passphrase to hash
    /// - Returns: 16-byte secret data
    public static func hashSecret(_ passphrase: String) -> Data {
        guard !passphrase.isEmpty else {
            return Data(repeating: 0, count: ProtocolLimits.channelSecretSize)
        }

        let data = passphrase.data(using: .utf8) ?? Data()
        let hash = SHA256.hash(data: data)
        return Data(hash.prefix(ProtocolLimits.channelSecretSize))
    }

    /// Validates that a secret has the correct size
    public static func validateSecret(_ secret: Data) -> Bool {
        secret.count == ProtocolLimits.channelSecretSize
    }

    /// Determines if a channel slot should be treated as configured.
    /// A slot is unconfigured only when both the name is empty and the secret is all zeros.
    static func isChannelConfigured(name: String, secret: Data) -> Bool {
        !name.isEmpty || !isZeroSecret(secret)
    }

    private static func isZeroSecret(_ secret: Data) -> Bool {
        secret.allSatisfy { $0 == 0 }
    }

    // MARK: - Channel CRUD Operations

    /// Fetches all channels for a device from the remote device.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - maxChannels: Maximum number of channels to fetch (from device capacity)
    /// - Returns: Sync result with number of channels synced
    /// - Throws: `syncAlreadyInProgress` if another sync is running,
    ///           `circuitBreakerOpen` if too many consecutive timeouts
    public func syncChannels(radioID: UUID, maxChannels: UInt8) async throws -> ChannelSyncResult {
        // Concurrency guard
        guard !isSyncing else {
            logger.warning("Channel sync already in progress, rejecting concurrent request")
            throw ChannelServiceError.syncAlreadyInProgress
        }

        isSyncing = true
        defer { isSyncing = false }

        var syncedCount = 0
        var syncErrors: [ChannelSyncError] = []
        var channels: [ChannelDTO] = []
        var unconfiguredCount = 0
        var emptyNameWithSecretIndices: [UInt8] = []

        // Circuit breaker state
        var consecutiveTimeouts = 0
        let circuitBreakerThreshold = 3

        for index: UInt8 in 0..<maxChannels {
            // Circuit breaker: fail fast if connection is clearly broken
            if consecutiveTimeouts >= circuitBreakerThreshold {
                logger.error("Circuit breaker open: \(consecutiveTimeouts) consecutive timeouts, aborting sync")
                // Mark remaining channels as failed
                for remaining in index..<maxChannels {
                    syncErrors.append(ChannelSyncError(
                        index: remaining,
                        errorType: .timeout,
                        description: "Skipped due to circuit breaker"
                    ))
                }
                throw ChannelServiceError.circuitBreakerOpen(consecutiveFailures: consecutiveTimeouts)
            }

            do {
                if let channelInfo = try await fetchChannel(index: index) {
                    _ = try await dataStore.saveChannel(radioID: radioID, from: channelInfo)
                    syncedCount += 1
                    consecutiveTimeouts = 0  // Reset on success
                    if channelInfo.name.isEmpty {
                        emptyNameWithSecretIndices.append(index)
                    }

                    // Fetch the saved channel DTO
                    if let dto = try await dataStore.fetchChannel(radioID: radioID, index: index) {
                        channels.append(dto)
                    }
                } else {
                    // Channel not configured on device - delete any stale local entry
                    consecutiveTimeouts = 0  // Not-found is not a timeout
                    unconfiguredCount += 1
                    if let staleChannel = try await dataStore.fetchChannel(radioID: radioID, index: index) {
                        try await dataStore.deleteChannel(id: staleChannel.id)
                    }
                }
            } catch let error as ChannelServiceError {
                // Track consecutive timeouts for circuit breaker
                if case .sessionError(let meshError) = error, case .timeout = meshError {
                    consecutiveTimeouts += 1
                } else {
                    consecutiveTimeouts = 0
                }
                let syncError = classifyError(error, forIndex: index)
                logger.warning("Failed to sync channel \(index): \(syncError.description)")
                syncErrors.append(syncError)
            } catch {
                consecutiveTimeouts = 0
                let syncError = classifyError(error, forIndex: index)
                logger.warning("Failed to sync channel \(index): \(syncError.description)")
                syncErrors.append(syncError)
            }
        }

        // Clean up orphaned channels (index >= maxChannels)
        // This handles the case where device capacity decreased
        do {
            let allLocalChannels = try await dataStore.fetchChannels(radioID: radioID)
            for channel in allLocalChannels where channel.index >= maxChannels {
                logger.info("Removing orphaned channel \(channel.index) (maxChannels=\(maxChannels))")
                try await dataStore.deleteChannel(id: channel.id)
            }
        } catch {
            logger.warning("Failed to cleanup orphaned channels: \(error.localizedDescription)")
            // Non-fatal: continue with sync result
        }

        logger.info(
            "Channel sync diagnostics: synced=\(syncedCount), unconfigured=\(unconfiguredCount), emptyNameWithSecret=\(emptyNameWithSecretIndices.count), errors=\(syncErrors.count)"
        )
        if !emptyNameWithSecretIndices.isEmpty {
            logger.warning(
                "Channel sync detected empty-name channels with non-zero secrets at indices: \(emptyNameWithSecretIndices)"
            )
        }

        // Notify handler of updated channels
        channelUpdateHandler?(channels)

        return ChannelSyncResult(channelsSynced: syncedCount, errors: syncErrors)
    }

    /// Retries syncing only the channels that previously failed.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - indices: Channel indices to retry
    /// - Returns: Sync result for the retried channels
    public func retryFailedChannels(radioID: UUID, indices: [UInt8]) async throws -> ChannelSyncResult {
        guard !isSyncing else {
            throw ChannelServiceError.syncAlreadyInProgress
        }

        guard !indices.isEmpty else {
            return ChannelSyncResult(channelsSynced: 0, errors: [])
        }

        isSyncing = true
        defer { isSyncing = false }

        logger.info("Retrying \(indices.count) failed channels: \(indices)")

        // Brief delay before retry to allow transient issues to resolve
        try await Task.sleep(for: .milliseconds(500))

        var syncedCount = 0
        var syncErrors: [ChannelSyncError] = []
        var channels: [ChannelDTO] = []

        // Circuit breaker for retry (stricter threshold than initial sync)
        var consecutiveTimeouts = 0
        let circuitBreakerThreshold = 2

        for index in indices {
            // Circuit breaker: stop retrying if connection is clearly broken
            if consecutiveTimeouts >= circuitBreakerThreshold {
                logger.warning("Retry circuit breaker open: \(consecutiveTimeouts) consecutive timeouts, stopping retry")
                // Mark remaining channels as failed
                let remainingIndices = indices.drop(while: { $0 != index })
                for remaining in remainingIndices {
                    syncErrors.append(ChannelSyncError(
                        index: remaining,
                        errorType: .timeout,
                        description: "Skipped due to retry circuit breaker"
                    ))
                }
                break
            }

            do {
                if let channelInfo = try await fetchChannel(index: index) {
                    _ = try await dataStore.saveChannel(radioID: radioID, from: channelInfo)
                    syncedCount += 1
                    consecutiveTimeouts = 0  // Reset on success

                    if let dto = try await dataStore.fetchChannel(radioID: radioID, index: index) {
                        channels.append(dto)
                    }
                    logger.info("Retry succeeded for channel \(index)")
                } else {
                    consecutiveTimeouts = 0  // Not-found is not a timeout
                }
            } catch {
                let syncError = classifyError(error, forIndex: index)
                // Track consecutive timeouts for circuit breaker
                if case .timeout = syncError.errorType {
                    consecutiveTimeouts += 1
                } else {
                    consecutiveTimeouts = 0
                }
                logger.warning("Retry failed for channel \(index): \(syncError.description)")
                syncErrors.append(syncError)
            }
        }

        // Notify handler if we recovered any channels
        if !channels.isEmpty {
            let allChannels = try await dataStore.fetchChannels(radioID: radioID)
            channelUpdateHandler?(allChannels)
        }

        return ChannelSyncResult(channelsSynced: syncedCount, errors: syncErrors)
    }

    /// Fetches a single channel from the device with retry logic for transient BLE failures.
    /// - Parameter index: The channel index 
    /// - Returns: Channel info if found, nil if not configured
    public func fetchChannel(index: UInt8) async throws -> ChannelInfo? {
        // BLE operations can fail transiently due to RF interference or timing.
        // Retry with exponential backoff per industry best practices (BLE spec recommends 30s timeout,
        // but shorter retries with backoff are more responsive).
        let maxAttempts = 3
        var lastError: MeshCoreError = .timeout

        for attempt in 1...maxAttempts {
            do {
                let meshChannelInfo = try await session.getChannel(index: index)

                // Validate returned index matches requested
                guard meshChannelInfo.index == index else {
                    logger.error("Channel index mismatch: requested \(index), received \(meshChannelInfo.index)")
                    throw ChannelServiceError.invalidChannelIndex
                }

                // Treat channel as unconfigured only when both name and secret are empty.
                guard Self.isChannelConfigured(name: meshChannelInfo.name, secret: meshChannelInfo.secret) else {
                    return nil
                }
                if meshChannelInfo.name.isEmpty {
                    logger.warning(
                        "Channel \(index) has empty name with non-zero secret; treating as configured"
                    )
                }

                // Convert MeshCore.ChannelInfo to MC1Services.ChannelInfo
                return ChannelInfo(
                    index: meshChannelInfo.index,
                    name: meshChannelInfo.name,
                    secret: meshChannelInfo.secret
                )
            } catch let error as MeshCoreError {
                // Non-retryable: channel not found on device (permanent error)
                if case .deviceError(let code) = error, code == ProtocolError.notFound.rawValue {
                    return nil
                }

                // Retryable: timeout errors are transient BLE issues
                if case .timeout = error {
                    lastError = error
                    if attempt < maxAttempts {
                        // Exponential backoff: 500ms, 1000ms, 2000ms with jitter
                        let baseDelayMs = 500 * (1 << (attempt - 1))
                        let jitterMs = Int.random(in: -100...100)
                        let delayMs = baseDelayMs + jitterMs
                        logger.info("Channel \(index) fetch timeout, retry \(attempt)/\(maxAttempts) in \(delayMs)ms")
                        try await Task.sleep(for: .milliseconds(delayMs))
                        continue
                    }
                }

                // Non-retryable: other MeshCore errors (device errors, parse errors, etc.)
                throw ChannelServiceError.sessionError(error)
            }
        }

        // All retries exhausted
        throw ChannelServiceError.sessionError(lastError)
    }

    /// Sets (creates or updates) a channel on the device.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - index: The channel index 
    ///   - name: The channel name
    ///   - passphrase: The passphrase to hash into a secret
    public func setChannel(
        radioID: UUID,
        index: UInt8,
        name: String,
        passphrase: String
    ) async throws {
        let secret = Self.hashSecret(passphrase)
        let truncatedName = name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)

        do {
            try await session.setChannel(index: index, name: truncatedName, secret: secret)

            // Save to local database
            let channelInfo = ChannelInfo(index: index, name: truncatedName, secret: secret)
            _ = try await dataStore.saveChannel(radioID: radioID, from: channelInfo)

            // Notify handler of update
            let channels = try await dataStore.fetchChannels(radioID: radioID)
            channelUpdateHandler?(channels)
        } catch let error as MeshCoreError {
            throw ChannelServiceError.sessionError(error)
        }
    }

    /// Sets a channel with a pre-computed secret (for advanced use cases).
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - index: The channel index 
    ///   - name: The channel name
    ///   - secret: The 16-byte secret (must be exactly 16 bytes)
    public func setChannelWithSecret(
        radioID: UUID,
        index: UInt8,
        name: String,
        secret: Data
    ) async throws {
        guard Self.validateSecret(secret) else {
            throw ChannelServiceError.secretHashingFailed
        }

        let truncatedName = name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)

        do {
            try await session.setChannel(index: index, name: truncatedName, secret: secret)

            // Save to local database
            let channelInfo = ChannelInfo(index: index, name: truncatedName, secret: secret)
            _ = try await dataStore.saveChannel(radioID: radioID, from: channelInfo)

            // Notify handler of update
            let channels = try await dataStore.fetchChannels(radioID: radioID)
            channelUpdateHandler?(channels)
        } catch let error as MeshCoreError {
            throw ChannelServiceError.sessionError(error)
        }
    }

    /// Clears a channel by setting it to empty name and zero secret.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - index: The channel index
    public func clearChannel(radioID: UUID, index: UInt8) async throws {
        // Get channel ID before clearing, so we can reliably delete it
        // (fetching after setChannelWithSecret may not find the empty-named channel)
        let channelToDelete = try await dataStore.fetchChannel(radioID: radioID, index: index)

        // Set empty name and zero secret to clear on device
        do {
            try await session.setChannel(
                index: index,
                name: "",
                secret: Data(repeating: 0, count: ProtocolLimits.channelSecretSize)
            )
        } catch let error as MeshCoreError {
            throw ChannelServiceError.sessionError(error)
        }

        // Delete messages for this channel first
        try await dataStore.deleteMessagesForChannel(radioID: radioID, channelIndex: index)

        // Delete channel from local database using the ID we captured earlier
        if let channel = channelToDelete {
            try await dataStore.deleteChannel(id: channel.id)
        }

        // Notify handler that channels changed
        let channels = try await dataStore.fetchChannels(radioID: radioID)
        channelUpdateHandler?(channels)
    }

    /// Clears all messages for a channel without deleting the channel itself.
    /// Use this for a "Clear Messages" feature that keeps the channel active.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - channelIndex: The channel index (0-7)
    public func clearChannelMessages(radioID: UUID, channelIndex: UInt8) async throws {
        try await dataStore.deleteMessagesForChannel(radioID: radioID, channelIndex: channelIndex)

        // Clear the last message date so the channel doesn't show a preview
        if let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
            try await dataStore.updateChannelLastMessage(channelID: channel.id, date: nil)
        }
    }

    // MARK: - Local Database Operations

    /// Gets all channels from local database for a device.
    /// - Parameter radioID: The device UUID
    /// - Returns: Array of channel DTOs
    public func getChannels(radioID: UUID) async throws -> [ChannelDTO] {
        try await dataStore.fetchChannels(radioID: radioID)
    }

    /// Gets a specific channel from local database.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - index: The channel index
    /// - Returns: Channel DTO if found
    public func getChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? {
        try await dataStore.fetchChannel(radioID: radioID, index: index)
    }

    /// Gets channels that have messages (for chat list).
    /// - Parameter radioID: The device UUID
    /// - Returns: Array of channel DTOs with lastMessageDate set
    public func getActiveChannels(radioID: UUID) async throws -> [ChannelDTO] {
        let channels = try await dataStore.fetchChannels(radioID: radioID)
        return channels.filter { $0.lastMessageDate != nil }
    }

    // MARK: - Public Channel (Slot 0)

    private static let publicChannelSecret = Data([
        0x8b, 0x33, 0x87, 0xe9, 0xc5, 0xcd, 0xea, 0x6a,
        0xc9, 0xe5, 0xed, 0xba, 0xa1, 0x15, 0xcd, 0x72
    ])

    /// Creates or resets the public channel (slot 0).
    /// - Parameter radioID: The device UUID
    public func setupPublicChannel(radioID: UUID) async throws {
        try await setChannelWithSecret(
            radioID: radioID,
            index: 0,
            name: "Public",
            secret: Self.publicChannelSecret
        )
    }

    /// Checks if the public channel exists locally.
    /// - Parameter radioID: The device UUID
    /// - Returns: True if public channel exists
    public func hasPublicChannel(radioID: UUID) async throws -> Bool {
        let channel = try await dataStore.fetchChannel(radioID: radioID, index: 0)
        return channel != nil
    }

    // MARK: - Handlers

    /// Sets a callback for channel updates.
    public func setChannelUpdateHandler(_ handler: @escaping @Sendable ([ChannelDTO]) -> Void) {
        channelUpdateHandler = handler
    }

    /// Whether a channel update handler has been wired via `setChannelUpdateHandler`.
    var hasChannelUpdateHandlerWired: Bool { channelUpdateHandler != nil }

    // MARK: - Private Helpers

    /// Classifies an error into a ChannelSyncError for the given index
    private func classifyError(_ error: Error, forIndex index: UInt8) -> ChannelSyncError {
        if let channelError = error as? ChannelServiceError {
            switch channelError {
            case .sessionError(let meshError):
                switch meshError {
                case .timeout:
                    return ChannelSyncError(
                        index: index,
                        errorType: .timeout,
                        description: "Request timed out"
                    )
                case .deviceError(let code):
                    return ChannelSyncError(
                        index: index,
                        errorType: .deviceError(code: code),
                        description: meshError.localizedDescription
                    )
                default:
                    return ChannelSyncError(
                        index: index,
                        errorType: .unknown,
                        description: meshError.localizedDescription
                    )
                }
            case .saveFailed(let reason):
                return ChannelSyncError(
                    index: index,
                    errorType: .databaseError,
                    description: "Save failed: \(reason)"
                )
            default:
                return ChannelSyncError(
                    index: index,
                    errorType: .unknown,
                    description: channelError.localizedDescription
                )
            }
        }

        return ChannelSyncError(
            index: index,
            errorType: .unknown,
            description: error.localizedDescription
        )
    }

}

// MARK: - ChannelServiceProtocol Conformance

extension ChannelService: ChannelServiceProtocol {
    // Already implements syncChannels(radioID:maxChannels:) -> ChannelSyncResult
}
