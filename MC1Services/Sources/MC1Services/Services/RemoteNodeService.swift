import Foundation
import MeshCore
import os

// MARK: - CLI Response Text Type

/// Wire value `0x01` — firmware `TXT_TYPE_CLI_DATA`.
/// `ContactMessage.textType` is a raw byte on the MeshCore side, so we keep
/// the constant as a `UInt8` rather than routing through a Swift enum.
private let cliResponseTextType: UInt8 = 0x01

// MARK: - Remote Node Service

/// Shared service for remote node operations.
/// Handles login, keep-alive, status, telemetry, and CLI for both room servers and repeaters.
public actor RemoteNodeService {

    // MARK: - Properties

    let session: any RemoteAccessSessionOps & SessionEventStreaming & ContactSessionOps
    let dataStore: any PersistenceStoreProtocol
    let keychainService: KeychainService
    let logger = PersistentLogger(subsystem: "com.mc1", category: "RemoteNode")
    let auditLogger = CommandAuditLogger()

    /// Pending login continuations keyed by 6-byte public key prefix.
    /// Using 6-byte prefix matches MeshCore protocol format for login results.
    var pendingLogins: [Data: CheckedContinuation<LoginResult, Error>] = [:]

    /// Timeout tasks for pending logins, keyed by 6-byte public key prefix.
    /// Cancelled when login succeeds/fails before timeout.
    var pendingLoginTimeoutTasks: [Data: Task<Void, Never>] = [:]

    /// Pending CLI request with command info for content-based response matching
    struct PendingCLIRequest {
        let command: String
        let continuation: CheckedContinuation<String, Error>
        let timestamp: Date
    }

    /// Pending CLI requests keyed by 6-byte public key prefix.
    /// Multiple requests per destination stored in order for FIFO fallback.
    var pendingCLIRequests: [Data: [PendingCLIRequest]] = [:]

    /// Pending raw CLI requests for passthrough (FIFO matching, single request per sender).
    /// Used by CLI tool where any response should be delivered without content-based matching.
    var pendingRawCLIRequests: [Data: CheckedContinuation<String, Error>] = [:]

    /// Keep-alive timer tasks
    var keepAliveTasks: [UUID: Task<Void, Never>] = [:]

    /// Keep-alive intervals per session (from login response, in seconds)
    /// Default to 90 seconds if not specified
    var keepAliveIntervals: [UUID: Duration] = [:]
    static let defaultKeepAliveInterval: Duration = .seconds(90)

    /// Reentrancy guard for BLE reconnection handling
    var isReauthenticating = false

    /// Event monitoring task
    private var eventMonitorTask: Task<Void, Never>?

    // MARK: - Handlers

    /// Handler for keep-alive ACK responses
    /// Called when ACK with unsynced count is received.
    /// Nothing assigns or reads this property today.
    public var keepAliveResponseHandler: (@Sendable (UUID, Int) async -> Void)?

    // MARK: - Events

    /// Multicast broadcaster for session connection-state events.
    nonisolated let eventBroadcaster = EventBroadcaster<RemoteNodeEvent>()

    /// Returns a fresh stream of remote-node session events. Registration is
    /// synchronous, so events yielded after this call are never dropped.
    /// Consumers must re-subscribe per connection because the owning
    /// `ServiceContainer` is rebuilt on every connection.
    public nonisolated func events() -> AsyncStream<RemoteNodeEvent> {
        eventBroadcaster.subscribe()
    }

    /// Ends every `events()` subscriber's for-await loop. Called by
    /// `ServiceContainer.tearDown()` so consumer tasks release the service
    /// references they hold.
    nonisolated func finishEvents() {
        eventBroadcaster.finish()
    }

    // MARK: - Initialization

    init(
        session: any RemoteAccessSessionOps & SessionEventStreaming & ContactSessionOps,
        dataStore: any PersistenceStoreProtocol,
        keychainService: KeychainService
    ) {
        self.session = session
        self.dataStore = dataStore
        self.keychainService = keychainService
    }

    deinit {
        eventMonitorTask?.cancel()
        for task in keepAliveTasks.values {
            task.cancel()
        }
    }

    // MARK: - Event Monitoring

    /// Start monitoring MeshCore events for login results
    public func startEventMonitoring() {
        eventMonitorTask?.cancel()

        eventMonitorTask = Task { [weak self] in
            guard let self else { return }
            let filter = EventFilter { event in
                switch event {
                case .loginSuccess, .loginFailed:
                    return true
                case .contactMessageReceived(let info) where info.textType == cliResponseTextType:
                    return true
                default:
                    return false
                }
            }
            let events = await session.events(filter: filter)

            for await event in events {
                guard !Task.isCancelled else { break }
                await self.handleEvent(event)
            }
        }
    }

    /// Stop monitoring events
    public func stopEventMonitoring() {
        eventMonitorTask?.cancel()
        eventMonitorTask = nil
    }

    /// Handle incoming MeshCore event
    private func handleEvent(_ event: MeshEvent) async {
        switch event {
        case .loginSuccess(let info):
            let prefixHex = info.publicKeyPrefix.map { String(format: "%02x", $0) }.joined()
            logger.info("loginSuccess received for prefix \(prefixHex)")
            let result = LoginResult(
                success: true,
                isAdmin: info.isAdmin,
                aclPermissions: info.permissions,
                publicKeyPrefix: info.publicKeyPrefix
            )
            await handleLoginResult(result, fromPublicKeyPrefix: info.publicKeyPrefix)

        case .loginFailed(let publicKeyPrefix):
            if let prefix = publicKeyPrefix {
                let result = LoginResult(
                    success: false,
                    isAdmin: false,
                    aclPermissions: nil,
                    publicKeyPrefix: prefix
                )
                await handleLoginResult(result, fromPublicKeyPrefix: prefix)
            }

        case .contactMessageReceived(let message):
            if message.textType == cliResponseTextType {
                handleCLIResponse(message)
            }

        default:
            break
        }
    }

    /// Handle CLI response from a contact message.
    /// Content-based matching runs first — raw (FIFO) matching only gets responses
    /// that no typed query claimed.
    private func handleCLIResponse(_ message: ContactMessage) {
        let prefix = Data(message.senderPublicKeyPrefix.prefix(6))

        // Try content-based matching first for structured requests
        if var requests = pendingCLIRequests[prefix], !requests.isEmpty {
            let (matchIndex, matchCount) = findBestMatch(response: message.text, in: requests)

            if let matchIndex {
                let matched = requests.remove(at: matchIndex)
                pendingCLIRequests[prefix] = requests.isEmpty ? nil : requests
                matched.continuation.resume(returning: message.text)
                return
            }

            if matchCount > 1 {
                // Multiple matches (ambiguous like "OK") - fall back to FIFO
                let oldest = requests.removeFirst()
                pendingCLIRequests[prefix] = requests.isEmpty ? nil : requests
                oldest.continuation.resume(returning: message.text)
                return
            }
        }

        // No content match — deliver to raw CLI request if one is pending
        if let continuation = pendingRawCLIRequests.removeValue(forKey: prefix) {
            continuation.resume(returning: message.text)
            return
        }

        // No pending requests matched
        logger.debug("Unmatched CLI response (no pending request): \(message.text.prefix(50))")
    }

    /// Find best matching request for a response based on CLIResponse parsing
    /// Returns the matching index (if exactly one) and total match count
    private func findBestMatch(response: String, in requests: [PendingCLIRequest]) -> (index: Int?, matchCount: Int) {
        var matchingIndices: [Int] = []

        for (index, request) in requests.enumerated() {
            let parsed = CLIResponse.parse(response, forQuery: request.command)

            // If parsing with this query produces a specific result (not .raw),
            // it's a potential match
            if case .raw = parsed {
                continue
            }
            matchingIndices.append(index)
        }

        // Return match only if exactly one command matches
        if matchingIndices.count == 1 {
            return (matchingIndices[0], 1)
        }

        return (nil, matchingIndices.count)
    }

    func timeInterval(for duration: Duration) -> TimeInterval {
        let (seconds, attoseconds) = duration.components
        return TimeInterval(seconds) + TimeInterval(attoseconds) / 1e18
    }

    func cancelPendingLogin(for prefix: Data) {
        pendingLoginTimeoutTasks.removeValue(forKey: prefix)?.cancel()
        if let continuation = pendingLogins.removeValue(forKey: prefix) {
            continuation.resume(throwing: RemoteNodeError.cancelled)
        }
    }

    func cancelPendingCLIRequest(for prefix: Data, timestamp: Date) {
        guard var requests = pendingCLIRequests[prefix],
              let index = requests.firstIndex(where: { $0.timestamp == timestamp }) else {
            return
        }

        let cancelled = requests.remove(at: index)
        pendingCLIRequests[prefix] = requests.isEmpty ? nil : requests
        cancelled.continuation.resume(throwing: CancellationError())
    }

    // MARK: - Session Management

    /// Create a session DTO for a contact, optionally preserving data from an existing session.
    private func makeSessionDTO(
        radioID: UUID,
        contact: ContactDTO,
        role: RemoteNodeRole,
        preserving existing: RemoteNodeSessionDTO? = nil
    ) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: existing?.id ?? UUID(),
            radioID: radioID,
            publicKey: contact.publicKey,
            name: contact.displayName,
            role: role,
            latitude: contact.latitude,
            longitude: contact.longitude,
            isConnected: false,
            permissionLevel: existing?.permissionLevel ?? .guest,
            lastConnectedDate: existing?.lastConnectedDate,
            lastBatteryMillivolts: existing?.lastBatteryMillivolts,
            lastUptimeSeconds: existing?.lastUptimeSeconds,
            lastNoiseFloor: existing?.lastNoiseFloor,
            unreadCount: existing?.unreadCount ?? 0,
            notificationLevel: existing?.notificationLevel ?? .all,
            lastRxAirtimeSeconds: existing?.lastRxAirtimeSeconds,
            neighborCount: existing?.neighborCount ?? 0,
            lastSyncTimestamp: existing?.lastSyncTimestamp ?? 0,
            lastMessageDate: existing?.lastMessageDate
        )
    }

    /// Create a new session for a remote node.
    public func createSession(
        radioID: UUID,
        contact: ContactDTO
    ) async throws -> RemoteNodeSessionDTO {
        guard let role = RemoteNodeRole(contactType: contact.type) else {
            throw RemoteNodeError.invalidResponse
        }

        guard contact.publicKey.count == ProtocolLimits.publicKeySize else {
            throw RemoteNodeError.loginFailed("Invalid public key length: expected \(ProtocolLimits.publicKeySize) bytes, got \(contact.publicKey.count)")
        }

        let pubKeyHex = contact.publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()

        // Check for existing session - reuse to avoid duplicates
        let existing = try? await dataStore.fetchRemoteNodeSession(publicKey: contact.publicKey)

        if let existing {
            logger.info("createSession: reusing existing session \(existing.id) for \(pubKeyHex), isConnected=\(existing.isConnected)")
        } else {
            logger.info("createSession: creating new session for \(pubKeyHex)")
        }

        let dto = makeSessionDTO(radioID: radioID, contact: contact, role: role, preserving: existing)

        try await dataStore.saveRemoteNodeSessionDTO(dto)

        // Clean up any duplicate sessions with the same public key but different IDs
        try await dataStore.cleanupDuplicateRemoteNodeSessions(publicKey: contact.publicKey, keepID: dto.id)

        guard let saved = try await dataStore.fetchRemoteNodeSession(publicKey: contact.publicKey) else {
            logger.error("createSession: failed to fetch saved session for \(pubKeyHex)")
            throw RemoteNodeError.sessionNotFound
        }

        logger.info("createSession: saved session \(saved.id) for \(pubKeyHex)")
        return saved
    }

    /// Remove a session and its associated data
    public func removeSession(id: UUID, publicKey: Data) async throws {
        stopKeepAlive(sessionID: id)
        try await keychainService.deletePassword(forNodeKey: publicKey)
        try await dataStore.deleteRemoteNodeSession(id: id)
    }

    /// Check if a password is stored for a contact's public key.
    public func hasPassword(forContact contact: ContactDTO) async -> Bool {
        await keychainService.hasPassword(forNodeKey: contact.publicKey)
    }

    /// Retrieve the stored password for a contact's public key.
    public func retrievePassword(forContact contact: ContactDTO) async -> String? {
        try? await keychainService.retrievePassword(forNodeKey: contact.publicKey)
    }

    /// Store a password for a remote node.
    /// Call this after successful login to save correct passwords only.
    public func storePassword(_ password: String, forNodeKey publicKey: Data) async throws {
        try await keychainService.storePassword(password, forNodeKey: publicKey)
    }

    /// Delete the stored password for a contact's public key.
    public func deletePassword(forContact contact: ContactDTO) async throws {
        try await keychainService.deletePassword(forNodeKey: contact.publicKey)
    }

    // MARK: - Disconnect

    /// Mark session as disconnected without sending logout.
    public func disconnect(sessionID: UUID) async {
        stopKeepAlive(sessionID: sessionID)
        do {
            try await dataStore.markSessionDisconnected(sessionID)
        } catch {
            logger.error("Failed to persist disconnected state for session \(sessionID): \(error)")
        }

        // Notify UI of session state change
        eventBroadcaster.yield(.sessionStateChanged(sessionID: sessionID, isConnected: false))
    }

    // MARK: - Cleanup

    /// Stop all keep-alive timers and resume any parked login continuations (call on app termination)
    public func stopAllKeepAlives() {
        for task in keepAliveTasks.values {
            task.cancel()
        }
        keepAliveTasks.removeAll()

        // Resume parked logins before dropping their timeout tasks; otherwise the
        // continuation that would have resumed them is gone and the caller hangs.
        for prefix in Array(pendingLogins.keys) {
            cancelPendingLogin(for: prefix)
        }
    }

    /// Number of login continuations currently parked. Exposed for teardown tests.
    var pendingLoginCount: Int { pendingLogins.count }

}
