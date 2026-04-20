import Foundation
import MeshCore
import os

// MARK: - Remote Node Errors

public enum RemoteNodeError: Error, LocalizedError, Sendable {
    case notConnected
    case loginFailed(String)
    case sendFailed(String)
    case invalidResponse
    case permissionDenied
    case timeout
    case sessionNotFound
    case passwordNotFound
    case floodRouted  // Keep-alive requires direct path
    case pathDiscoveryFailed
    case contactNotFound
    case cancelled  // Login cancelled due to duplicate attempt or shutdown
    case sessionError(MeshCoreError)

    public var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to mesh device"
        case .loginFailed(let reason):
            return "Login failed: \(reason)"
        case .sendFailed(let reason):
            return "Failed to send: \(reason)"
        case .invalidResponse:
            return "Invalid response from remote node"
        case .permissionDenied:
            return "Permission denied"
        case .timeout:
            return "Request timed out"
        case .sessionNotFound:
            return "Remote node session not found"
        case .passwordNotFound:
            return "Password not found in keychain"
        case .floodRouted:
            return "Keep-alive requires direct routing path"
        case .pathDiscoveryFailed:
            return "Failed to establish direct path"
        case .contactNotFound:
            return "Contact not found in database"
        case .cancelled:
            return "Login cancelled"
        case .sessionError(let error):
            return error.localizedDescription
        }
    }

    public var isRetryable: Bool {
        switch self {
        case .timeout, .notConnected, .floodRouted:
            return true
        default:
            return false
        }
    }
}

// MARK: - Login Result

public struct LoginResult: Sendable {
    public let success: Bool
    public let isAdmin: Bool
    public let aclPermissions: UInt8?
    public let publicKeyPrefix: Data

    public init(success: Bool, isAdmin: Bool, aclPermissions: UInt8?, publicKeyPrefix: Data) {
        self.success = success
        self.isAdmin = isAdmin
        self.aclPermissions = aclPermissions
        self.publicKeyPrefix = publicKeyPrefix
    }

    public var permissionLevel: RoomPermissionLevel {
        isAdmin ? .admin : (RoomPermissionLevel(rawValue: aclPermissions ?? 0) ?? .guest)
    }
}

// MARK: - Login Timeout Configuration

/// Configuration for login timeout based on path length
public enum LoginTimeoutConfig {
    /// Base timeout for direct (0-hop) connections
    public static let directTimeout: Duration = .seconds(5)

    /// Additional timeout per hop in the path
    public static let perHopTimeout: Duration = .seconds(10)

    /// Maximum timeout regardless of path length
    public static let maximumTimeout: Duration = .seconds(60)

    /// Calculate appropriate timeout based on path length
    public static func timeout(forPathLength pathLength: UInt8) -> Duration {
        let base = directTimeout
        let hopCount = decodePathLen(pathLength)?.hopCount ?? 0
        let additional = perHopTimeout * hopCount
        let total = base + additional
        return min(total, maximumTimeout)
    }
}

// MARK: - Remote Timeout Policy

public enum RemoteOperationTimeoutPolicy {
    static let firmwareRoundTripMultiplier = 2
    static let loginMaximum: Duration = .seconds(20)
    public static let binaryMaximum: Duration = .seconds(15)
    static let cliMaximum: Duration = .seconds(15)
    static let fireAndForgetCLI: Duration = .seconds(2)
    static let pollInterval: Duration = .milliseconds(500)

    static func firmwareRoundTripTimeout(from sentInfo: MessageSentInfo) -> Duration {
        .milliseconds(Int(sentInfo.suggestedTimeoutMs) * firmwareRoundTripMultiplier)
    }

    static func loginTimeout(for sentInfo: MessageSentInfo, pathLength: UInt8) -> Duration {
        min(max(firmwareRoundTripTimeout(from: sentInfo), LoginTimeoutConfig.timeout(forPathLength: pathLength)), loginMaximum)
    }

    static func cliTimeout(for sentInfo: MessageSentInfo, requestedTimeout: Duration) -> Duration {
        min(max(requestedTimeout, firmwareRoundTripTimeout(from: sentInfo)), cliMaximum)
    }
}

// MARK: - Remote Node Service

/// Shared service for remote node operations.
/// Handles login, keep-alive, status, telemetry, and CLI for both room servers and repeaters.
public actor RemoteNodeService {

    // MARK: - Properties

    private let session: MeshCoreSession
    private let dataStore: PersistenceStore
    private let keychainService: KeychainService
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "RemoteNode")
    private let auditLogger = CommandAuditLogger()

    /// Pending login continuations keyed by 6-byte public key prefix.
    /// Using 6-byte prefix matches MeshCore protocol format for login results.
    private var pendingLogins: [Data: CheckedContinuation<LoginResult, Error>] = [:]

    /// Timeout tasks for pending logins, keyed by 6-byte public key prefix.
    /// Cancelled when login succeeds/fails before timeout.
    private var pendingLoginTimeoutTasks: [Data: Task<Void, Never>] = [:]

    /// Pending CLI request with command info for content-based response matching
    private struct PendingCLIRequest {
        let command: String
        let continuation: CheckedContinuation<String, Error>
        let timestamp: Date
    }

    /// Pending CLI requests keyed by 6-byte public key prefix.
    /// Multiple requests per destination stored in order for FIFO fallback.
    private var pendingCLIRequests: [Data: [PendingCLIRequest]] = [:]

    /// Pending raw CLI requests for passthrough (FIFO matching, single request per sender).
    /// Used by CLI tool where any response should be delivered without content-based matching.
    private var pendingRawCLIRequests: [Data: CheckedContinuation<String, Error>] = [:]

    /// Keep-alive timer tasks
    private var keepAliveTasks: [UUID: Task<Void, Never>] = [:]

    /// Keep-alive intervals per session (from login response, in seconds)
    /// Default to 90 seconds if not specified
    private var keepAliveIntervals: [UUID: Duration] = [:]
    private static let defaultKeepAliveInterval: Duration = .seconds(90)

    /// Reentrancy guard for BLE reconnection handling
    private var isReauthenticating = false

    /// Event monitoring task
    private var eventMonitorTask: Task<Void, Never>?

    // MARK: - Handlers

    /// Handler for keep-alive ACK responses
    /// Called when ACK with unsynced count is received
    public var keepAliveResponseHandler: (@Sendable (UUID, Int) async -> Void)?

    /// Handler for session connection state changes
    /// Called when session isConnected state changes (sessionID, isConnected)
    private var sessionStateChangedHandler: (@Sendable (UUID, Bool) async -> Void)?

    /// Set the handler for session connection state changes.
    public func setSessionStateChangedHandler(_ handler: @escaping @Sendable (UUID, Bool) async -> Void) {
        sessionStateChangedHandler = handler
    }

    // MARK: - Initialization

    public init(
        session: MeshCoreSession,
        dataStore: PersistenceStore,
        keychainService: KeychainService
    ) {
        self.session = session
        self.dataStore = dataStore
        self.keychainService = keychainService
    }

    deinit {
        eventMonitorTask?.cancel()
    }

    // MARK: - Event Monitoring

    /// Start monitoring MeshCore events for login results
    public func startEventMonitoring() {
        eventMonitorTask?.cancel()

        eventMonitorTask = Task { [weak self] in
            guard let self else { return }
            let events = await session.events()

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
            // Check if this is a CLI response (textType == 0x01)
            if message.textType == 0x01 {
                handleCLIResponse(message)
            }

        default:
            break
        }
    }

    /// Handle CLI response from a contact message.
    private func handleCLIResponse(_ message: ContactMessage) {
        let prefix = Data(message.senderPublicKeyPrefix.prefix(6))

        // Check raw CLI requests first (FIFO - single pending per sender)
        // Used by CLI tool for passthrough where any response is accepted
        if let continuation = pendingRawCLIRequests.removeValue(forKey: prefix) {
            continuation.resume(returning: message.text)
            return
        }

        // Fall back to content-based matching for structured requests
        guard var requests = pendingCLIRequests[prefix], !requests.isEmpty else {
            return
        }

        // Try content-based matching using CLIResponse.parse()
        let (matchIndex, matchCount) = findBestMatch(response: message.text, in: requests)

        if let matchIndex {
            // Exactly one match - deliver to that request
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

        // No matches - likely a late response for a timed-out request
        // Response still flows to CLI handler for UI display
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

    private func timeInterval(for duration: Duration) -> TimeInterval {
        let (seconds, attoseconds) = duration.components
        return TimeInterval(seconds) + TimeInterval(attoseconds) / 1e18
    }

    private func cancelPendingLogin(for prefix: Data) {
        pendingLoginTimeoutTasks.removeValue(forKey: prefix)?.cancel()
        if let continuation = pendingLogins.removeValue(forKey: prefix) {
            continuation.resume(throwing: RemoteNodeError.cancelled)
        }
    }

    private func cancelPendingCLIRequest(for prefix: Data, timestamp: Date) {
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
        deviceID: UUID,
        contact: ContactDTO,
        role: RemoteNodeRole,
        preserving existing: RemoteNodeSessionDTO? = nil
    ) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: existing?.id ?? UUID(),
            deviceID: deviceID,
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
        deviceID: UUID,
        contact: ContactDTO,
        password: String?,
        rememberPassword: Bool = true
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

        let dto = makeSessionDTO(deviceID: deviceID, contact: contact, role: role, preserving: existing)

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

    // MARK: - Login

    /// Login to a remote node.
    /// Works for both room servers and repeaters.
    /// - Parameters:
    ///   - sessionID: The remote session ID.
    ///   - password: Optional password (uses stored password if nil).
    ///   - pathLength: Path length hint for timeout calculation.
    ///   - onTimeoutKnown: Optional callback invoked with timeout in seconds once firmware responds.
    public func login(
        sessionID: UUID,
        password: String? = nil,
        pathLength: UInt8 = 0,
        onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
    ) async throws -> LoginResult {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Get password from parameter or keychain
        let pwd: String
        if let password {
            pwd = password
        } else if let stored = try await keychainService.retrievePassword(forNodeKey: remoteSession.publicKey) {
            pwd = stored
        } else {
            throw RemoteNodeError.passwordNotFound
        }

        let prefix = Data(remoteSession.publicKey.prefix(6))

        // Cancel any existing pending login for this prefix
        if let existing = pendingLogins.removeValue(forKey: prefix) {
            let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
            logger.warning("Overwriting pending login for prefix \(prefixHex)")
            pendingLoginTimeoutTasks.removeValue(forKey: prefix)?.cancel()
            existing.resume(throwing: RemoteNodeError.cancelled)
        }

        // Log login request
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logLoginRequest(target: targetType, publicKey: remoteSession.publicKey, pathLength: pathLength)

        // Register continuation BEFORE sending to avoid race condition with loginSuccess event
        let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
        logger.info("login: registering pending login for prefix \(prefixHex)")
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pendingLogins[prefix] = continuation

                let timeoutTask = Task { [self] in
                    let sentInfo: MessageSentInfo
                    do {
                        sentInfo = try await session.sendLogin(to: remoteSession.publicKey, password: pwd)
                    } catch {
                        pendingLoginTimeoutTasks.removeValue(forKey: prefix)
                        if let pending = pendingLogins.removeValue(forKey: prefix) {
                            let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
                            pending.resume(throwing: RemoteNodeError.sessionError(meshError))
                        }
                        return
                    }

                    let timeout = RemoteOperationTimeoutPolicy.loginTimeout(for: sentInfo, pathLength: pathLength)
                    logger.info("login: send succeeded, starting \(timeout) timeout for prefix \(prefixHex)")

                    if let onTimeoutKnown {
                        let timeoutSeconds = max(1, Int(timeInterval(for: timeout).rounded(.up)))
                        await onTimeoutKnown(timeoutSeconds)
                    }
                    try? await Task.sleep(for: timeout)
                    guard !Task.isCancelled else { return }
                    if let pending = pendingLogins.removeValue(forKey: prefix) {
                        logger.warning("Login timeout after \(timeout) for session \(sessionID), prefix \(prefixHex)")
                        pendingLoginTimeoutTasks.removeValue(forKey: prefix)
                        pending.resume(throwing: RemoteNodeError.timeout)
                    } else {
                        logger.info("login: timeout elapsed but continuation already consumed for prefix \(prefixHex)")
                    }
                }
                pendingLoginTimeoutTasks[prefix] = timeoutTask
            }
        } onCancel: { [weak self] in
            Task { [weak self] in
                await self?.cancelPendingLogin(for: prefix)
            }
        }
    }

    /// Handle login result push from device.
    private func handleLoginResult(_ result: LoginResult, fromPublicKeyPrefix: Data) async {
        guard fromPublicKeyPrefix.count >= 6 else {
            logger.warning("Login result has invalid prefix length: \(fromPublicKeyPrefix.count)")
            return
        }

        let prefix = Data(fromPublicKeyPrefix.prefix(6))
        let prefixHex = prefix.map { String(format: "%02x", $0) }.joined()
        let pendingKeys = pendingLogins.keys.map { $0.map { String(format: "%02x", $0) }.joined() }
        logger.info("handleLoginResult: looking for prefix \(prefixHex), pending keys: \(pendingKeys)")
        guard let continuation = pendingLogins.removeValue(forKey: prefix) else {
            logger.warning("Login result with no pending request. Prefix: \(prefixHex)")
            return
        }
        pendingLoginTimeoutTasks.removeValue(forKey: prefix)?.cancel()
        logger.info("handleLoginResult: found continuation for prefix \(prefixHex)")

        if result.success {
            // Update session state
            do {
                guard let remoteSession = try await dataStore.fetchRemoteNodeSessionByPrefix(prefix) else {
                    logger.error("handleLoginResult: no session found for prefix \(prefixHex) - database may be corrupted")
                    continuation.resume(returning: result)
                    return
                }

                // Log successful login
                let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
                await auditLogger.logLoginSuccess(target: targetType, publicKey: prefix, isAdmin: result.isAdmin)

                let permission = result.permissionLevel

                logger.info("handleLoginResult: updating session \(remoteSession.id) isConnected=true, permission=\(permission.rawValue)")

                try await dataStore.updateRemoteNodeSessionConnection(
                    id: remoteSession.id,
                    isConnected: true,
                    permissionLevel: permission
                )

                // Verify the update succeeded
                if let verifySession = try await dataStore.fetchRemoteNodeSession(id: remoteSession.id) {
                    if verifySession.isConnected {
                        logger.info("handleLoginResult: verified session \(remoteSession.id) isConnected=true")
                    } else {
                        logger.error("handleLoginResult: session \(remoteSession.id) still shows isConnected=false after update!")
                    }
                }

                // Notify UI of session state change
                await sessionStateChangedHandler?(remoteSession.id, true)

                keepAliveIntervals[remoteSession.id] = Self.defaultKeepAliveInterval
            } catch {
                logger.error("handleLoginResult: failed to update session state: \(error)")
            }
            continuation.resume(returning: result)
        } else {
            // Log failed login
            // Try to determine target type from existing session
            if let remoteSession = try? await dataStore.fetchRemoteNodeSessionByPrefix(prefix) {
                let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
                await auditLogger.logLoginFailed(target: targetType, publicKey: prefix, reason: "authentication failed")
            } else {
                await auditLogger.logLoginFailed(target: .repeater, publicKey: prefix, reason: "authentication failed")
            }
            continuation.resume(throwing: RemoteNodeError.loginFailed("authentication failed"))
        }
    }

    // MARK: - Keep-Alive (Room Servers)

    /// Start periodic keep-alive for a room server session.
    /// Sends an immediate keep-alive on start (for connectivity check + sync_since update),
    /// then continues at the configured interval.
    private func startKeepAlive(sessionID: UUID, publicKey: Data) {
        stopKeepAlive(sessionID: sessionID)

        let interval = keepAliveIntervals[sessionID] ?? Self.defaultKeepAliveInterval

        let task = Task {
            while !Task.isCancelled {
                do {
                    try await sendKeepAliveIfDirectRouted(sessionID: sessionID, publicKey: publicKey)
                } catch RemoteNodeError.floodRouted {
                    logger.info("Skipping keep-alive for flood-routed session \(sessionID)")
                } catch {
                    logger.warning("Keep-alive failed for session \(sessionID): \(error)")
                    do {
                        try await dataStore.markSessionDisconnected(sessionID)
                    } catch {
                        logger.error("Failed to persist disconnected state for session \(sessionID): \(error)")
                    }
                    await sessionStateChangedHandler?(sessionID, false)
                    break
                }

                try? await Task.sleep(for: interval)
                guard !Task.isCancelled else { break }
            }
        }

        keepAliveTasks[sessionID] = task
    }

    /// Stop keep-alive for a session
    private func stopKeepAlive(sessionID: UUID) {
        keepAliveTasks[sessionID]?.cancel()
        keepAliveTasks.removeValue(forKey: sessionID)
    }

    /// Send keep-alive only if the session has a direct routing path.
    private func sendKeepAliveIfDirectRouted(sessionID: UUID, publicKey: Data) async throws {
        // Fetch session to get deviceID
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Check contact's routing status
        guard let contact = try await dataStore.fetchContact(deviceID: remoteSession.deviceID, publicKey: publicKey) else {
            throw RemoteNodeError.contactNotFound
        }

        // Keep-alive only works with direct routing
        if contact.isFloodRouted {
            throw RemoteNodeError.floodRouted
        }

        // Log keep-alive
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logKeepAlive(target: targetType, publicKey: publicKey)

        // Send keep-alive with sync_since for force-resync hint
        do {
            _ = try await session.sendKeepAlive(to: publicKey, syncSince: remoteSession.lastSyncTimestamp)
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    /// Public method to send keep-alive (for manual refresh).
    public func sendKeepAlive(sessionID: UUID) async throws {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }
        try await sendKeepAliveIfDirectRouted(sessionID: sessionID, publicKey: remoteSession.publicKey)
    }

    /// Start keep-alive for a room session (called when room view appears).
    public func startSessionKeepAlive(sessionID: UUID, publicKey: Data) {
        startKeepAlive(sessionID: sessionID, publicKey: publicKey)
    }

    /// Stop keep-alive for a room session (called when room view disappears).
    public func stopSessionKeepAlive(sessionID: UUID) {
        stopKeepAlive(sessionID: sessionID)
    }

    // MARK: - History Sync

    /// Request message history from a room server.
    public func requestHistorySync(sessionID: UUID, since: UInt32 = 1) async throws {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        guard remoteSession.isRoom else {
            throw RemoteNodeError.invalidResponse
        }

        // Check for direct route
        guard let contact = try await dataStore.fetchContact(deviceID: remoteSession.deviceID, publicKey: remoteSession.publicKey) else {
            throw RemoteNodeError.contactNotFound
        }

        if contact.isFloodRouted {
            throw RemoteNodeError.floodRouted
        }

        // Request status (which triggers sync)
        do {
            let contactType: ContactType = remoteSession.isRoom ? .room : .repeater
            _ = try await session.requestStatus(from: remoteSession.publicKey, type: contactType)
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }

        logger.info("Requested history sync for room \(remoteSession.name) since \(since)")
    }

    // MARK: - Logout

    /// Explicitly logout from a remote node.
    public func logout(sessionID: UUID) async throws {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Log logout
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logLogout(target: targetType, publicKey: remoteSession.publicKey)

        stopKeepAlive(sessionID: sessionID)

        do {
            try await session.sendLogout(to: remoteSession.publicKey)
        } catch {
            // Ignore errors - we're disconnecting anyway
            logger.info("Logout send failed (ignoring): \(error)")
        }

        try await dataStore.updateRemoteNodeSessionConnection(
            id: sessionID,
            isConnected: false,
            permissionLevel: .guest
        )

        // Notify UI of session state change
        await sessionStateChangedHandler?(sessionID, false)
    }

    // MARK: - Status

    /// Request status from a remote node.
    public func requestStatus(sessionID: UUID, timeout: Duration? = nil) async throws -> StatusResponse {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Log status request
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logStatusRequest(target: targetType, publicKey: remoteSession.publicKey)

        do {
            let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
            return try await withTimeout(effectiveTimeout, operationName: "remoteStatus") {
                let contactType: ContactType = remoteSession.isRoom ? .room : .repeater
                return try await self.session.requestStatus(from: remoteSession.publicKey, type: contactType)
            }
        } catch is TimeoutError {
            throw RemoteNodeError.timeout
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    // MARK: - Telemetry

    /// Request telemetry from a remote node
    public func requestTelemetry(sessionID: UUID, timeout: Duration? = nil) async throws -> TelemetryResponse {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        // Log telemetry request
        let targetType: CommandAuditLogger.Target = remoteSession.isRoom ? .room : .repeater
        await auditLogger.logTelemetryRequest(target: targetType, publicKey: remoteSession.publicKey)

        do {
            let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
            return try await withTimeout(effectiveTimeout, operationName: "remoteTelemetry") {
                try await self.session.requestTelemetry(from: remoteSession.publicKey)
            }
        } catch is TimeoutError {
            throw RemoteNodeError.timeout
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    // MARK: - Owner Info

    /// Request owner info from a repeater using binary protocol.
    public func requestOwnerInfo(sessionID: UUID, timeout: Duration? = nil) async throws -> OwnerInfoResponse {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        do {
            let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
            return try await withTimeout(effectiveTimeout, operationName: "remoteOwnerInfo") {
                try await self.session.requestOwnerInfo(from: remoteSession.publicKey)
            }
        } catch is TimeoutError {
            throw RemoteNodeError.timeout
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    // MARK: - CLI Commands

    /// Send a CLI command to a remote node and wait for response (admin only).
    /// - Parameters:
    ///   - sessionID: The remote node session ID.
    ///   - command: The CLI command to send.
    ///   - timeout: Hard maximum time to wait for response (default 10 seconds).
    /// - Returns: The CLI response text from the remote node.
    public func sendCLICommand(
        sessionID: UUID,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        guard remoteSession.isAdmin else {
            throw RemoteNodeError.permissionDenied
        }

        // Log CLI command (with password redaction)
        await auditLogger.logCLICommand(publicKey: remoteSession.publicKey, command: command)

        let destinationPrefix = Data(remoteSession.publicKey.prefix(6))
        let requestTimestamp = Date()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let request = PendingCLIRequest(
                    command: command,
                    continuation: continuation,
                    timestamp: requestTimestamp
                )

                if pendingCLIRequests[destinationPrefix] == nil {
                    pendingCLIRequests[destinationPrefix] = []
                }
                pendingCLIRequests[destinationPrefix]!.append(request)

                Task { [self] in
                    let sentInfo: MessageSentInfo
                    do {
                        sentInfo = try await session.sendCommand(to: remoteSession.publicKey, command: command)
                    } catch {
                        if var requests = pendingCLIRequests[destinationPrefix],
                           let index = requests.firstIndex(where: { $0.timestamp == requestTimestamp }) {
                            let failed = requests.remove(at: index)
                            pendingCLIRequests[destinationPrefix] = requests.isEmpty ? nil : requests
                            let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
                            failed.continuation.resume(throwing: RemoteNodeError.sessionError(meshError))
                        }
                        return
                    }

                    let effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: timeout)
                    let deadline = ContinuousClock.now.advanced(by: effectiveTimeout)
                    while ContinuousClock.now < deadline {
                        if let requests = pendingCLIRequests[destinationPrefix],
                           !requests.contains(where: { $0.timestamp == requestTimestamp }) {
                            return
                        } else if pendingCLIRequests[destinationPrefix] == nil {
                            return
                        }

                        let remaining = deadline - .now
                        let pollDuration = min(RemoteOperationTimeoutPolicy.pollInterval, remaining)
                        _ = try? await session.getMessage(timeout: max(0.1, timeInterval(for: pollDuration)))
                    }

                    if var requests = pendingCLIRequests[destinationPrefix],
                       let index = requests.firstIndex(where: { $0.timestamp == requestTimestamp }) {
                        let timedOut = requests.remove(at: index)
                        pendingCLIRequests[destinationPrefix] = requests.isEmpty ? nil : requests
                        timedOut.continuation.resume(throwing: RemoteNodeError.timeout)
                    }
                }
            }
        } onCancel: { [weak self] in
            Task { [weak self] in
                await self?.cancelPendingCLIRequest(for: destinationPrefix, timestamp: requestTimestamp)
            }
        }
    }

    /// Send a raw CLI command to a remote node using FIFO response matching (admin only).
    /// Unlike `sendCLICommand`, this method accepts any response from the target node
    /// without content-based matching. Used by CLI tool for passthrough commands.
    /// - Parameters:
    ///   - sessionID: The remote node session ID.
    ///   - command: The CLI command to send.
    ///   - timeout: Hard maximum time to wait for response (default 10 seconds).
    /// - Returns: The raw response text from the remote node.
    public func sendRawCLICommand(
        sessionID: UUID,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            throw RemoteNodeError.sessionNotFound
        }

        guard remoteSession.isAdmin else {
            throw RemoteNodeError.permissionDenied
        }

        let response = try await sendRawCLICommand(
            publicKey: remoteSession.publicKey,
            command: command,
            timeout: timeout
        )

        // Clear stored password after admin password change
        await handlePasswordChangeIfNeeded(command: command, sessionID: sessionID)

        return response
    }

    /// Send a raw CLI command directly to a known public key.
    /// This bypasses session lookup and is used by the local CLI session.
    public func sendRawCLICommand(
        publicKey: Data,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        await auditLogger.logCLICommand(publicKey: publicKey, command: command)

        let destinationPrefix = Data(publicKey.prefix(6))

        // Only one raw CLI request per sender at a time (FIFO matching)
        guard pendingRawCLIRequests[destinationPrefix] == nil else {
            throw RemoteNodeError.sessionError(.connectionLost(underlying: nil))
        }

        // Register continuation BEFORE sending to avoid race condition
        // Use withTaskCancellationHandler to clean up pending request if caller cancels
        let response = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pendingRawCLIRequests[destinationPrefix] = continuation

                Task { [self] in
                    // Send CLI command
                    let sentInfo: MessageSentInfo
                    do {
                        sentInfo = try await session.sendCommand(to: publicKey, command: command)
                    } catch {
                        // Send failed - remove pending request and resume with error
                        if let pending = pendingRawCLIRequests.removeValue(forKey: destinationPrefix) {
                            let meshError = error as? MeshCoreError ?? MeshCoreError.connectionLost(underlying: error)
                            pending.resume(throwing: RemoteNodeError.sessionError(meshError))
                        }
                        return
                    }

                    let effectiveTimeout = RemoteOperationTimeoutPolicy.cliTimeout(for: sentInfo, requestedTimeout: timeout)

                    // Poll for response
                    let deadline = ContinuousClock.now.advanced(by: effectiveTimeout)
                    while ContinuousClock.now < deadline {
                        // Check if our request was already satisfied
                        guard pendingRawCLIRequests[destinationPrefix] != nil else {
                            return  // Request was matched and removed by handleCLIResponse
                        }

                        // Check for task cancellation
                        if Task.isCancelled {
                            if let cancelled = pendingRawCLIRequests.removeValue(forKey: destinationPrefix) {
                                cancelled.resume(throwing: CancellationError())
                            }
                            return
                        }

                        // Poll device for pending messages
                        let remaining = deadline - .now
                        let pollDuration = min(RemoteOperationTimeoutPolicy.pollInterval, remaining)
                        _ = try? await session.getMessage(timeout: max(0.1, timeInterval(for: pollDuration)))
                    }

                    // Timeout - remove pending request and resume with error
                    if let timedOut = pendingRawCLIRequests.removeValue(forKey: destinationPrefix) {
                        timedOut.resume(throwing: RemoteNodeError.timeout)
                    }
                }
            }
        } onCancel: { [weak self] in
            Task { [weak self] in
                await self?.cancelPendingRawCLIRequest(for: destinationPrefix)
            }
        }

        return response
    }

    /// Clear stored password if command is an admin password change.
    private func handlePasswordChangeIfNeeded(command: String, sessionID: UUID) async {
        let lower = command.lowercased().trimmingCharacters(in: .whitespaces)

        // Only admin password changes, not guest
        guard lower.hasPrefix("password ") && !lower.contains("guest.password") else {
            return
        }

        guard let session = try? await dataStore.fetchRemoteNodeSession(id: sessionID) else {
            return
        }

        do {
            try await keychainService.deletePassword(forNodeKey: session.publicKey)
            logger.info("Cleared stored password after password change for session \(sessionID)")
        } catch {
            logger.warning("Failed to clear stored password for session \(sessionID): \(error)")
            // Next login fails naturally - user re-enters password, overwrites stale credential
        }
    }

    /// Cancel a pending raw CLI request when the calling task is cancelled.
    private func cancelPendingRawCLIRequest(for prefix: Data) {
        if let cancelled = pendingRawCLIRequests.removeValue(forKey: prefix) {
            cancelled.resume(throwing: CancellationError())
        }
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
        await sessionStateChangedHandler?(sessionID, false)
    }

    // MARK: - BLE Disconnection

    /// Called when BLE connection is lost.
    /// Marks all connected sessions as disconnected, stops keep-alive timers,
    /// and notifies UI via `sessionStateChangedHandler`.
    /// Returns the set of session IDs that were connected, for re-auth on reconnect.
    public func handleBLEDisconnection() async -> Set<UUID> {
        let connectedSessions: [RemoteNodeSessionDTO]
        do {
            connectedSessions = try await dataStore.fetchConnectedRemoteNodeSessions()
        } catch {
            logger.error("Failed to fetch connected sessions for BLE disconnection: \(error)")
            return []
        }

        guard !connectedSessions.isEmpty else { return [] }

        logger.info("BLE disconnection: marking \(connectedSessions.count) session(s) disconnected")
        var sessionIDs: Set<UUID> = []

        for session in connectedSessions {
            sessionIDs.insert(session.id)
            stopKeepAlive(sessionID: session.id)
            do {
                try await dataStore.markSessionDisconnected(session.id)
            } catch {
                logger.error("Failed to mark session \(session.id) disconnected: \(error)")
            }
            await sessionStateChangedHandler?(session.id, false)
        }

        return sessionIDs
    }

    // MARK: - BLE Reconnection

    /// Called when BLE connection is re-established.
    /// Re-authenticates sessions that were connected before BLE loss.
    /// - Parameter sessionIDs: Session IDs from `handleBLEDisconnection()`.
    ///   If empty (e.g., after app restart), no sessions are re-authenticated;
    ///   the user can manually reconnect.
    public func handleBLEReconnection(sessionIDs: Set<UUID>) async {
        guard !isReauthenticating else {
            logger.info("Skipping re-auth: already in progress")
            return
        }

        guard !sessionIDs.isEmpty else { return }

        // Fetch current session state for each ID
        var sessionsToReauth: [RemoteNodeSessionDTO] = []
        for id in sessionIDs {
            if let session = try? await dataStore.fetchRemoteNodeSession(id: id) {
                sessionsToReauth.append(session)
            } else {
                logger.warning("Session \(id) not found for re-auth, skipping")
            }
        }

        guard !sessionsToReauth.isEmpty else { return }

        logger.info("BLE reconnection: re-authenticating \(sessionsToReauth.count) session(s)")
        isReauthenticating = true
        defer { isReauthenticating = false }

        await withTaskGroup(of: Void.self) { group in
            for remoteSession in sessionsToReauth {
                group.addTask { [self] in
                    let previousPermission = remoteSession.permissionLevel
                    do {
                        let result = try await self.login(sessionID: remoteSession.id)
                        let newPermission = result.permissionLevel
                        if newPermission < previousPermission {
                            self.logger.warning(
                                "Re-auth returned degraded permission for session \(remoteSession.id): "
                                + "\(previousPermission) -> \(newPermission), marking disconnected"
                            )
                            try? await self.dataStore.markSessionDisconnected(remoteSession.id)
                            await self.sessionStateChangedHandler?(remoteSession.id, false)
                        }
                    } catch {
                        self.logger.warning("Re-auth failed for session \(remoteSession.id): \(error)")
                        do {
                            try await self.dataStore.markSessionDisconnected(remoteSession.id)
                        } catch {
                            self.logger.error("Failed to persist disconnected state for session \(remoteSession.id): \(error)")
                        }
                        await self.sessionStateChangedHandler?(remoteSession.id, false)
                    }
                }
            }
        }
    }

    // MARK: - Cleanup

    /// Stop all keep-alive timers and cancel pending login timeouts (call on app termination)
    public func stopAllKeepAlives() {
        for task in keepAliveTasks.values {
            task.cancel()
        }
        keepAliveTasks.removeAll()

        for task in pendingLoginTimeoutTasks.values {
            task.cancel()
        }
        pendingLoginTimeoutTasks.removeAll()
    }

}
