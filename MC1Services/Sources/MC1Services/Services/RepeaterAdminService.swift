import Foundation
import MeshCore
import os

// MARK: - Neighbor Sort Order

/// Sort order options for neighbor queries
public enum NeighborSortOrder: UInt8, Sendable {
    case newestFirst = 0
    case oldestFirst = 1
    case strongestFirst = 2
    case weakestFirst = 3
}

// MARK: - Repeater Admin Service

/// Service for repeater admin interactions.
/// Handles connecting as admin, viewing status/telemetry/neighbors, and sending CLI commands.
public actor RepeaterAdminService {

    // MARK: - Properties

    private let session: MeshCoreSession
    private let remoteNodeService: RemoteNodeService
    private let dataStore: PersistenceStore
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "RepeaterAdmin")
    private let auditLogger = CommandAuditLogger()

    /// Handler for neighbor responses
    public var neighboursResponseHandler: (@Sendable (NeighboursResponse) async -> Void)?

    /// Handler for telemetry responses
    public var telemetryResponseHandler: (@Sendable (TelemetryResponse) async -> Void)?

    /// Handler for status responses
    public var statusResponseHandler: (@Sendable (StatusResponse) async -> Void)?

    /// Handler for CLI text responses
    public var cliResponseHandler: (@Sendable (ContactMessage, ContactDTO) async -> Void)?

    /// Default pubkey prefix length for neighbor queries.
    public static let defaultPubkeyPrefixLength: UInt8 = 6

    // MARK: - Initialization

    public init(
        session: MeshCoreSession,
        remoteNodeService: RemoteNodeService,
        dataStore: PersistenceStore
    ) {
        self.session = session
        self.remoteNodeService = remoteNodeService
        self.dataStore = dataStore
    }

    // MARK: - Admin Connection

    /// Connect to a repeater as admin by creating a session and authenticating.
    /// - Parameter onTimeoutKnown: Optional callback invoked with timeout in seconds once firmware responds.
    public func connectAsAdmin(
        radioID: UUID,
        contact: ContactDTO,
        password: String?,
        rememberPassword: Bool = true,
        pathLength: UInt8 = 0,
        onTimeoutKnown: (@Sendable (Int) async -> Void)? = nil
    ) async throws -> RemoteNodeSessionDTO {
        let remoteSession = try await remoteNodeService.createSession(
            radioID: radioID,
            contact: contact,
            password: password,
            rememberPassword: rememberPassword
        )

        // Login to the repeater with appropriate timeout
        _ = try await remoteNodeService.login(
            sessionID: remoteSession.id,
            password: password,
            pathLength: pathLength,
            onTimeoutKnown: onTimeoutKnown
        )

        // Store password only after successful login
        if let password, rememberPassword {
            try await remoteNodeService.storePassword(password, forNodeKey: contact.publicKey)
        }

        guard let updatedSession = try await dataStore.fetchRemoteNodeSession(id: remoteSession.id) else {
            throw RemoteNodeError.sessionNotFound
        }
        return updatedSession
    }

    /// Disconnect from a repeater by sending logout and removing the session.
    public func disconnect(sessionID: UUID, publicKey: Data) async throws {
        try await remoteNodeService.logout(sessionID: sessionID)
        try await remoteNodeService.removeSession(id: sessionID, publicKey: publicKey)
    }

    // MARK: - Neighbors (Repeater-Specific)

    /// Request neighbors list from repeater.
    public func requestNeighbors(
        sessionID: UUID,
        count: UInt8 = 20,
        offset: UInt16 = 0,
        orderBy: NeighborSortOrder = .newestFirst,
        pubkeyPrefixLength: UInt8 = defaultPubkeyPrefixLength,
        timeout: Duration? = nil
    ) async throws -> NeighboursResponse {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID),
              remoteSession.isRepeater else {
            throw RemoteNodeError.sessionNotFound
        }

        // Log neighbors request
        await auditLogger.logNeighborsRequest(publicKey: remoteSession.publicKey, count: count, offset: offset)

        do {
            let effectiveTimeout = timeout ?? RemoteOperationTimeoutPolicy.binaryMaximum
            return try await withTimeout(effectiveTimeout, operationName: "remoteNeighbours") {
                try await self.session.requestNeighbours(
                    from: remoteSession.publicKey,
                    count: count,
                    offset: offset,
                    orderBy: orderBy.rawValue,
                    pubkeyPrefixLength: pubkeyPrefixLength
                )
            }
        } catch is TimeoutError {
            throw RemoteNodeError.timeout
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    /// Fetch all neighbors with automatic pagination.
    public func fetchAllNeighbors(
        sessionID: UUID,
        orderBy: NeighborSortOrder = .newestFirst,
        pubkeyPrefixLength: UInt8 = defaultPubkeyPrefixLength
    ) async throws -> NeighboursResponse {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID),
              remoteSession.isRepeater else {
            throw RemoteNodeError.sessionNotFound
        }

        do {
            return try await session.fetchAllNeighbours(
                from: remoteSession.publicKey,
                orderBy: orderBy.rawValue,
                pubkeyPrefixLength: pubkeyPrefixLength
            )
        } catch let error as MeshCoreError {
            throw RemoteNodeError.sessionError(error)
        }
    }

    // MARK: - Status

    /// Request status from a repeater.
    public func requestStatus(sessionID: UUID, timeout: Duration? = nil) async throws -> StatusResponse {
        try await remoteNodeService.requestStatus(sessionID: sessionID, timeout: timeout)
    }

    // MARK: - Telemetry

    /// Request telemetry from a repeater.
    public func requestTelemetry(sessionID: UUID, timeout: Duration? = nil) async throws -> TelemetryResponse {
        try await remoteNodeService.requestTelemetry(sessionID: sessionID, timeout: timeout)
    }

    // MARK: - Owner Info

    /// Request owner info from a repeater using binary protocol.
    public func requestOwnerInfo(sessionID: UUID, timeout: Duration? = nil) async throws -> OwnerInfoResponse {
        try await remoteNodeService.requestOwnerInfo(sessionID: sessionID, timeout: timeout)
    }

    // MARK: - CLI Commands

    /// Send a CLI command to a repeater and wait for response (admin only).
    /// Uses content-based matching for structured CLI responses.
    public func sendCommand(
        sessionID: UUID,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        try await remoteNodeService.sendCLICommand(
            sessionID: sessionID,
            command: command,
            timeout: timeout
        )
    }

    /// Send a raw CLI command using FIFO response matching (admin only).
    /// Used by CLI tool for passthrough where any response is accepted.
    public func sendRawCommand(
        sessionID: UUID,
        command: String,
        timeout: Duration = .seconds(10)
    ) async throws -> String {
        try await remoteNodeService.sendRawCLICommand(
            sessionID: sessionID,
            command: command,
            timeout: timeout
        )
    }

    // MARK: - Session Queries

    /// Fetch all repeater sessions for a device.
    public func fetchRepeaterSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] {
        let sessions = try await dataStore.fetchRemoteNodeSessions(radioID: radioID)
        return sessions.filter { $0.isRepeater }
    }

    /// Check if a contact is a known repeater with an active session.
    public func getConnectedSession(publicKeyPrefix: Data) async throws -> RemoteNodeSessionDTO? {
        guard let remoteSession = try await dataStore.fetchRemoteNodeSessionByPrefix(publicKeyPrefix),
              remoteSession.isRepeater && remoteSession.isConnected else {
            return nil
        }
        return remoteSession
    }

    // MARK: - Handler Invocation

    /// Invoke the status response handler safely from actor context
    public func invokeStatusHandler(_ status: StatusResponse) async {
        // Log status response
        await auditLogger.logStatusResponse(
            target: .repeater,
            publicKey: status.publicKeyPrefix,
            batteryMv: status.batteryMillivolts,
            uptimeSec: status.uptimeSeconds
        )

        guard let handler = statusResponseHandler else {
            let prefixHex = status.publicKeyPrefix.map { String(format: "%02x", $0) }.joined()
            logger.debug("No status handler registered for response from \(prefixHex), ignoring")
            return
        }
        await handler(status)
    }

    /// Invoke the neighbours response handler safely from actor context
    public func invokeNeighboursHandler(_ response: NeighboursResponse) async {
        // Log neighbors response
        await auditLogger.logNeighborsResponse(
            publicKey: response.publicKeyPrefix,
            totalCount: Int(response.totalCount),
            returnedCount: response.neighbours.count
        )

        guard let handler = neighboursResponseHandler else {
            logger.debug("No neighbours handler registered, ignoring response with \(response.neighbours.count) neighbours")
            return
        }
        await handler(response)
    }

    /// Invoke the telemetry response handler safely from actor context
    public func invokeTelemetryHandler(_ response: TelemetryResponse) async {
        // Log telemetry response
        await auditLogger.logTelemetryResponse(
            target: .repeater,
            publicKey: response.publicKeyPrefix,
            pointCount: response.dataPoints.count
        )

        guard let handler = telemetryResponseHandler else {
            logger.debug("No telemetry handler registered, ignoring response")
            return
        }
        await handler(response)
    }

    /// Invoke the CLI response handler safely from actor context
    public func invokeCLIHandler(_ message: ContactMessage, fromContact contact: ContactDTO) async {
        // Log CLI response (full content)
        await auditLogger.logCLIResponse(publicKey: contact.publicKey, response: message.text)

        guard let handler = cliResponseHandler else {
            logger.debug("No CLI handler registered, ignoring response from \(contact.displayName)")
            return
        }
        await handler(message, contact)
    }

    // MARK: - Handler Setters

    /// Set handler for status responses
    public func setStatusHandler(_ handler: @escaping @Sendable (StatusResponse) async -> Void) {
        self.statusResponseHandler = handler
    }

    /// Set handler for neighbours responses
    public func setNeighboursHandler(_ handler: @escaping @Sendable (NeighboursResponse) async -> Void) {
        self.neighboursResponseHandler = handler
    }

    /// Set handler for telemetry responses
    public func setTelemetryHandler(_ handler: @escaping @Sendable (TelemetryResponse) async -> Void) {
        self.telemetryResponseHandler = handler
    }

    /// Set handler for CLI responses
    public func setCLIHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO) async -> Void) {
        self.cliResponseHandler = handler
    }

    /// Clear all handlers (called when view disappears)
    public func clearHandlers() {
        self.statusResponseHandler = nil
        self.neighboursResponseHandler = nil
        self.telemetryResponseHandler = nil
        self.cliResponseHandler = nil
    }
}
