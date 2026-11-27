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
/// Handles authentication and commands for sensors, repeaters, and room servers.
public actor RemoteNodeService {
  // MARK: - Properties

  let session: any RemoteAccessSessionOps & SessionEventStreaming & ContactSessionOps
  let clock: any Clock<Duration>
  let dataStore: any PersistenceStoreProtocol
  let keychainService: KeychainService
  let contactRadioOwner: ContactRadioOwner
  let textRulesPreference: @Sendable () async -> TextRulesPreference?
  let receiveLifetime: ReceiveScope.Lifetime
  let radioID: UUID?
  let defaults: UserDefaults
  let logger = PersistentLogger(subsystem: "com.mc1", category: "RemoteNode")
  let auditLogger = CommandAuditLogger()

  var isRetired = false
  var sensorConnectAttempts: [UUID: UUID] = [:]

  /// CLI slot ownership and FIFO admission keyed by the response's public key prefix.
  var cliSlots: [Data: RemoteCLISlot] = [:]
  static let cliPublicKeyPrefixLength = 6

  /// Cycling counter for CLI wire prefixes ("00|" through "FF|").
  var cliPrefixCounter: UInt8 = 0

  /// Keep-alive timer tasks
  var keepAliveTasks: [UUID: Task<Void, Never>] = [:]
  var retiredKeepAliveTasks: [Task<Void, Never>] = []
  private var retirementTask: Task<Void, Never>?

  /// Recovery and refresh outlive timer restarts on this Link record.
  struct RoomKeepAliveSchedule {
    let sessionID: UUID
    let lifetime = ReceiveScope.Lifetime()
    let recoverySubmitted = OSAllocatedUnfairLock(initialState: false)
    var recovery: Task<Void, Error>?
    var refresh: Task<Void, Error>?
  }

  var roomKeepAliveSchedules: [UUID: RoomKeepAliveSchedule] = [:]

  /// Keep-alive intervals per session (from login response, in seconds)
  /// Default to 90 seconds if not specified
  var keepAliveIntervals: [UUID: Duration] = [:]
  static let defaultKeepAliveInterval: Duration = .seconds(90)

  /// Reentrancy guard for Link reconnection handling
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

  #if DEBUG
    /// Pauses before the store reads or creates a session.
    var roomReuseHoldForTesting: (@Sendable () async -> Void)?

    func setRoomReuseHoldForTesting(_ hold: (@Sendable () async -> Void)?) {
      roomReuseHoldForTesting = hold
    }
  #endif

  // MARK: - Initialization

  init(
    session: any RemoteAccessSessionOps & SessionEventStreaming & ContactSessionOps,
    clock: any Clock<Duration> = ContinuousClock(),
    dataStore: any PersistenceStoreProtocol,
    keychainService: KeychainService,
    contactRadioOwner: ContactRadioOwner = ContactRadioOwner(),
    textRulesPreference: @escaping @Sendable () async -> TextRulesPreference? = { nil },
    receiveLifetime: ReceiveScope.Lifetime = .init(),
    radioID: UUID? = nil,
    defaults: UserDefaults = .standard
  ) {
    self.session = session
    self.clock = clock
    self.dataStore = dataStore
    self.keychainService = keychainService
    self.contactRadioOwner = contactRadioOwner
    self.textRulesPreference = textRulesPreference
    self.receiveLifetime = receiveLifetime
    self.radioID = radioID
    self.defaults = defaults
  }

  deinit {
    eventMonitorTask?.cancel()
    for task in keepAliveTasks.values {
      task.cancel()
    }
  }

  // MARK: - Event Monitoring

  public func startEventMonitoring() {
    guard !isRetired else { return }
    eventMonitorTask?.cancel()

    eventMonitorTask = Task(priority: .utility) { [weak self] in
      guard let self else { return }
      let filter = EventFilter { event in
        switch event {
        case let .contactMessageReceived(info) where info.textType == cliResponseTextType:
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
  public func stopEventMonitoring() async {
    let worker = eventMonitorTask
    eventMonitorTask = nil
    worker?.cancel()
    await worker?.value
  }

  /// Handle incoming MeshCore event
  private func handleEvent(_ event: MeshEvent) async {
    if case let .contactMessageReceived(message) = event,
       message.textType == cliResponseTextType {
      handleCLIResponse(message)
    }
  }

  /// Returns the next cycling CLI wire prefix ("00|" through "FF|").
  func makeCLIWirePrefix() -> String {
    defer { cliPrefixCounter &+= 1 }
    return String(format: "%02X%@", cliPrefixCounter, String(CLIResponse.echoPrefixSeparator))
  }

  static func timeInterval(for duration: Duration) -> TimeInterval {
    let (seconds, attoseconds) = duration.components
    return TimeInterval(seconds) + TimeInterval(attoseconds) / 1e18
  }

  // MARK: - Session Management

  /// Create a new session for a remote node.
  public func createSession(
    radioID: UUID,
    contact: ContactDTO
  ) async throws -> RemoteNodeSessionDTO {
    try await createSession(radioID: radioID, contact: contact, sensorAttemptID: nil)
  }

  func createSession(
    radioID: UUID,
    contact: ContactDTO,
    sensorAttemptID: UUID?
  ) async throws -> RemoteNodeSessionDTO {
    try validateSessionCreation(contactID: contact.id, sensorAttemptID: sensorAttemptID)
    guard let role = RemoteNodeRole(contactType: contact.type) else {
      throw RemoteNodeError.invalidResponse
    }

    guard contact.publicKey.count == ProtocolLimits.publicKeySize else {
      throw RemoteNodeError.loginFailed("Invalid public key length: expected \(ProtocolLimits.publicKeySize) bytes, got \(contact.publicKey.count)")
    }

    let pubKeyHex = contact.publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()
    #if DEBUG
      if let hold = roomReuseHoldForTesting {
        await hold()
      }
    #endif
    try validateSessionCreation(contactID: contact.id, sensorAttemptID: sensorAttemptID)
    let saved = try await dataStore.reuseOrCreateRemoteNodeSession(
      RoomSessionPresentation(
        radioID: radioID,
        publicKey: contact.publicKey,
        name: contact.displayName,
        role: role,
        latitude: contact.latitude,
        longitude: contact.longitude
      )
    )
    try validateSessionCreation(contactID: contact.id, sensorAttemptID: sensorAttemptID)
    logger.info("createSession: saved session \(saved.id) for \(pubKeyHex), isConnected=\(saved.isConnected)")
    return saved
  }

  private func validateSessionCreation(contactID: UUID, sensorAttemptID: UUID?) throws {
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    if let sensorAttemptID, sensorConnectAttempts[contactID] != sensorAttemptID { throw RemoteNodeError.cancelled }
  }

  /// Remove a session and its associated data
  public func removeSession(id: UUID, publicKey: Data) async throws {
    stopKeepAlive(sessionID: id)
    try await keychainService.deletePassword(forNodeKey: publicKey)
    try await dataStore.deleteRemoteNodeSession(id: id)
    for (key, schedule) in roomKeepAliveSchedules where schedule.sessionID == id {
      schedule.lifetime.retire()
      schedule.recovery?.cancel()
      schedule.refresh?.cancel()
      roomKeepAliveSchedules.removeValue(forKey: key)
    }
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

  public func stopAllKeepAlives() {
    for task in keepAliveTasks.values {
      task.cancel()
      retiredKeepAliveTasks.append(task)
    }
    keepAliveTasks.removeAll()
  }

  func beginRetirement() {
    guard !isRetired else { return }
    isRetired = true
    sensorConnectAttempts.removeAll()
    stopAllKeepAlives()
    let slots = cliSlots.values
    cliSlots.removeAll()
    for var slot in slots {
      slot.cancelAll()
    }
  }

  func retire() async {
    if let retirementTask {
      await retirementTask.value
      return
    }

    beginRetirement()
    let workers = retiredKeepAliveTasks
    retiredKeepAliveTasks.removeAll()
    let drain = Task {
      await stopEventMonitoring()
      for worker in workers {
        await worker.value
      }
    }
    retirementTask = drain
    await drain.value
  }
}
