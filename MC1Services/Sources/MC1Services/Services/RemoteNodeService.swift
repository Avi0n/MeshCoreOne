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

  /// The in-flight CLI request for a node. `acceptsAnyResponse` marks raw
  /// passthrough commands whose reply shape can't be validated. `wirePrefix`
  /// is the correlation token prepended to the command on the wire; firmware
  /// reflects it back at the start of the reply.
  struct PendingCLIRequest {
    let id: UUID
    let command: String
    let wirePrefix: String
    let acceptsAnyResponse: Bool
    let continuation: CheckedContinuation<String, Error>
  }

  /// The single in-flight CLI request per node, keyed by 6-byte public key
  /// prefix. Replies echoing the request's wire prefix are attributed
  /// deterministically; unprefixed replies (firmware that predates the echo)
  /// fall back to single-flight shape validation, and foreign-prefixed
  /// replies are stale by definition and dropped.
  var pendingCLIRequests: [Data: PendingCLIRequest] = [:]

  /// Cycling counter for CLI wire prefixes ("00|" through "FF|").
  var cliPrefixCounter: UInt8 = 0

  /// Clock drift measured at the last successful login, keyed by session ID.
  /// Positive means the remote node's clock is ahead of the connected radio.
  /// In-memory only; drift is re-measured on every login.
  var loginClockDrifts: [UUID: TimeInterval] = [:]

  /// A task queued for a node's CLI slot while another command is in flight.
  struct CLISlotWaiter {
    let id: UUID
    let continuation: CheckedContinuation<Void, Error>
  }

  /// Nodes whose CLI slot is held by an in-flight command.
  var cliSlotBusy: Set<Data> = []

  /// FIFO waiters for a node's CLI slot, keyed by 6-byte public key prefix.
  var cliSlotWaiters: [Data: [CLISlotWaiter]] = [:]

  /// A binary request kind whose late response can be salvaged after timeout.
  enum SalvageableBinaryRequest: Hashable, Sendable {
    case status
    case telemetry
    case neighbours
  }

  /// A late binary response recovered after its request timed out.
  public enum SalvagedBinaryResponse: Sendable {
    case status(StatusResponse)
    case telemetry(TelemetryResponse)
    case neighbours(NeighboursResponse)
  }

  struct SalvageKey: Hashable {
    let kind: SalvageableBinaryRequest
    let prefix: Data
  }

  /// Binary requests that timed out but may still be answered. Responses carry
  /// the node's key prefix, so a late arrival within the window is exactly
  /// attributable and salvaging it spares the airtime of a retry.
  var salvageableTimeouts: [SalvageKey: ContinuousClock.Instant] = [:]
  static let salvageWindow: Duration = .seconds(60)

  /// Delivers salvaged responses to the admin services; wired by `ServiceContainer`.
  var salvagedResponseRouter: (@Sendable (SalvagedBinaryResponse) async -> Void)?

  /// Reads the connected radio's clock; wired by `ServiceContainer`. Clock
  /// drift is measured against the radio, not the phone, because mesh packet
  /// timestamps come from the radio's RTC.
  var radioClockProvider: (@Sendable () async -> Date?)?

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
          true
        case let .contactMessageReceived(info) where info.textType == cliResponseTextType:
          true
        case .statusResponse, .telemetryResponse, .neighboursResponse:
          true
        default:
          false
        }
      }
      let events = await session.events(filter: filter)

      for await event in events {
        guard !Task.isCancelled else { break }
        await handleEvent(event)
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
    case let .loginSuccess(info):
      let prefixHex = info.publicKeyPrefix.map { String(format: "%02x", $0) }.joined()
      logger.info("loginSuccess received for prefix \(prefixHex)")
      let result = LoginResult(
        success: true,
        isAdmin: info.isAdmin,
        aclPermissions: info.permissions,
        publicKeyPrefix: info.publicKeyPrefix,
        serverTime: info.serverTime
      )
      await handleLoginResult(result, fromPublicKeyPrefix: info.publicKeyPrefix)

    case let .loginFailed(publicKeyPrefix):
      if let prefix = publicKeyPrefix {
        let result = LoginResult(
          success: false,
          isAdmin: false,
          aclPermissions: nil,
          publicKeyPrefix: prefix
        )
        await handleLoginResult(result, fromPublicKeyPrefix: prefix)
      }

    case let .contactMessageReceived(message):
      if message.textType == cliResponseTextType {
        handleCLIResponse(message)
      }

    case let .statusResponse(response):
      await salvageIfRecorded(.status, publicKeyPrefix: response.publicKeyPrefix, response: .status(response))

    case let .telemetryResponse(response):
      await salvageIfRecorded(.telemetry, publicKeyPrefix: response.publicKeyPrefix, response: .telemetry(response))

    case let .neighboursResponse(response):
      await salvageIfRecorded(
        .neighbours, publicKeyPrefix: response.publicKeyPrefix, response: .neighbours(response)
      )

    default:
      break
    }
  }

  // MARK: - Late Binary Response Salvage

  /// Wires the delivery target for salvaged responses.
  public func setSalvagedResponseRouter(
    _ router: @escaping @Sendable (SalvagedBinaryResponse) async -> Void
  ) {
    salvagedResponseRouter = router
  }

  /// Marks a timed-out request as still answerable within the salvage window.
  func recordSalvageableTimeout(_ kind: SalvageableBinaryRequest, publicKey: Data) {
    let now = ContinuousClock.now
    salvageableTimeouts = salvageableTimeouts.filter { $0.value > now }
    let key = SalvageKey(kind: kind, prefix: Data(publicKey.prefix(6)))
    salvageableTimeouts[key] = now.advanced(by: Self.salvageWindow)
  }

  /// Delivers a late response when its request is recorded as timed out.
  /// Solicited responses are never routed here twice: the entry exists only
  /// after the original request already failed.
  private func salvageIfRecorded(
    _ kind: SalvageableBinaryRequest,
    publicKeyPrefix: Data,
    response: SalvagedBinaryResponse
  ) async {
    let key = SalvageKey(kind: kind, prefix: Data(publicKeyPrefix.prefix(6)))
    guard let expiry = salvageableTimeouts.removeValue(forKey: key) else { return }
    guard expiry > ContinuousClock.now, let router = salvagedResponseRouter else { return }

    logger.info("Salvaged late \(String(describing: kind)) response after timeout")
    await router(response)
  }

  /// Handle CLI response from a contact message. The wire prefix echoed by
  /// firmware attributes a reply deterministically; an unprefixed reply falls
  /// back to single-flight shape validation for older firmware, and a reply
  /// echoing a different prefix belongs to an earlier command and is dropped.
  private func handleCLIResponse(_ message: ContactMessage) {
    let prefix = Data(message.senderPublicKeyPrefix.prefix(6))

    guard let pending = pendingCLIRequests[prefix] else {
      logger.debug("Unmatched CLI response (no pending request): \(message.text.prefix(50))")
      return
    }

    let responseText: String
    if let echoed = CLIResponse.splitEchoedPrefix(message.text) {
      guard echoed.prefix == pending.wirePrefix else {
        logger.warning(
          "Dropping stale CLI response with prefix \(echoed.prefix) while awaiting \(pending.wirePrefix)"
        )
        return
      }
      responseText = echoed.body
    } else {
      // Firmware without prefix echo: fall back to shape validation.
      guard pending.acceptsAnyResponse
        || CLIResponse.isPlausibleResponse(message.text, forQuery: pending.command) else {
        logger.warning(
          "Dropping CLI response that doesn't match pending '\(pending.command)': \(message.text.prefix(50))"
        )
        return
      }
      responseText = message.text
    }

    pendingCLIRequests[prefix] = nil
    pending.continuation.resume(returning: responseText)
  }

  /// Returns the next cycling CLI wire prefix ("00|" through "FF|").
  func makeCLIWirePrefix() -> String {
    defer { cliPrefixCounter &+= 1 }
    return String(format: "%02X%@", cliPrefixCounter, String(CLIResponse.echoPrefixSeparator))
  }

  // MARK: - Login Clock Drift

  /// Sets the closure used to read the connected radio's clock.
  public func setRadioClockProvider(_ provider: @escaping @Sendable () async -> Date?) {
    radioClockProvider = provider
  }

  /// Records the clock drift measured from a login response, relative to the
  /// radio's clock (falling back to the phone when the radio can't be read).
  func recordLoginClockDrift(sessionID: UUID, serverTime: Date?) async {
    guard let serverTime else { return }
    let reference = await radioClockProvider?() ?? Date()
    let drift = serverTime.timeIntervalSince(reference)
    loginClockDrifts[sessionID] = drift
    logger.info("Login clock drift for session \(sessionID): \(Int(drift))s")
  }

  /// The remote node's clock drift measured at its last login this connection.
  /// Positive means the node's clock is ahead of the connected radio.
  public func loginClockDrift(sessionID: UUID) -> TimeInterval? {
    loginClockDrifts[sessionID]
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

  func cancelPendingCLIRequest(for prefix: Data, requestID: UUID) {
    guard let cancelled = takePendingCLIRequest(for: prefix, requestID: requestID) else { return }
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
  var pendingLoginCount: Int {
    pendingLogins.count
  }
}
