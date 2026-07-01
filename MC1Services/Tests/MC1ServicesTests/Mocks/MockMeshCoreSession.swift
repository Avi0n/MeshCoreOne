import Foundation
import MeshCore

/// Mock implementation of MeshCoreSessionProtocol for testing.
///
/// Configure the mock by setting the stub properties before calling methods.
/// Track method calls by examining the recorded invocations.
public actor MockMeshCoreSession: MeshCoreSessionProtocol {
  // MARK: - Connection State

  public var connectionState: AsyncStream<ConnectionState> {
    AsyncStream { continuation in
      continuation.yield(stubbedConnectionState)
      continuation.finish()
    }
  }

  /// Self info to return from currentSelfInfo. Configure via `setCurrentSelfInfo(_:)`.
  public private(set) var currentSelfInfo: SelfInfo?

  // MARK: - Event Streaming

  private struct EventSubscription {
    let filter: EventFilter?
    let continuation: AsyncStream<MeshEvent>.Continuation
  }

  /// Active `events()` subscriptions, keyed so a terminated stream can deregister itself.
  private var eventSubscriptions: [UUID: EventSubscription] = [:]

  public func events() async -> AsyncStream<MeshEvent> {
    makeEventStream(filter: nil)
  }

  public func events(filter: EventFilter) async -> AsyncStream<MeshEvent> {
    makeEventStream(filter: filter)
  }

  private func makeEventStream(filter: EventFilter?) -> AsyncStream<MeshEvent> {
    let id = UUID()
    let (stream, continuation) = AsyncStream<MeshEvent>.makeStream()
    eventSubscriptions[id] = EventSubscription(filter: filter, continuation: continuation)
    continuation.onTermination = { [weak self] _ in
      guard let self else { return }
      Task { await self.removeEventSubscription(id: id) }
    }
    return stream
  }

  private func removeEventSubscription(id: UUID) {
    eventSubscriptions[id] = nil
  }

  /// Yields an event to every active `events()` subscriber whose filter matches.
  public func yieldEvent(_ event: MeshEvent) {
    for subscription in eventSubscriptions.values where subscription.filter?.matches(event) != false {
      subscription.continuation.yield(event)
    }
  }

  /// Finishes every active `events()` stream, ending subscribers' for-await loops.
  public func finishEventStreams() {
    for subscription in eventSubscriptions.values {
      subscription.continuation.finish()
    }
    eventSubscriptions.removeAll()
  }

  // MARK: - Stubs

  /// The connection state to return from connectionState stream
  public var stubbedConnectionState: ConnectionState = .disconnected

  /// Result to return from sendMessage
  public var stubbedSendMessageResult: Result<MessageSentInfo, Error> = .success(
    MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000)
  )

  /// Result to return from sendChannelMessage
  public var stubbedSendChannelMessageError: Error?

  /// Contacts to return from getContacts
  public var stubbedContacts: [MeshContact] = []

  /// Error to throw from getContacts
  public var stubbedGetContactsError: Error?

  /// Contact to return from getContact (by public key)
  public var stubbedContact: MeshContact?

  /// Error to throw from getContact
  public var stubbedGetContactError: Error?

  /// Error to throw from addContact
  public var stubbedAddContactError: Error?

  /// Error to throw from removeContact
  public var stubbedRemoveContactError: Error?

  /// Error to throw from resetPath
  public var stubbedResetPathError: Error?

  /// Result to return from sendPathDiscovery
  public var stubbedSendPathDiscoveryResult: Result<MessageSentInfo, Error> = .success(
    MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 5000)
  )

  /// Channel info to return from getChannel, keyed by index
  public var stubbedChannels: [UInt8: ChannelInfo] = [:]

  /// Error to throw from getChannel
  public var stubbedGetChannelError: Error?

  /// Error to throw from setChannel
  public var stubbedSetChannelError: Error?

  /// Event to return from waitForEvent (nil simulates a timeout)
  public var stubbedWaitForEventResult: MeshEvent?

  /// Result to return from getMessage
  public var stubbedGetMessageResult: Result<MessageResult, Error> = .success(.noMoreMessages)

  /// Results to return from successive `sendLogin` calls, consumed FIFO.
  public var stubbedSendLoginResults: [Result<MessageSentInfo, Error>] = []

  // MARK: - Recorded Invocations

  public struct SendMessageInvocation: Sendable, Equatable {
    public let destination: Data
    public let text: String
    public let timestamp: Date
    public let attempt: UInt8
  }

  public struct SendChannelMessageInvocation: Sendable, Equatable {
    public let channel: UInt8
    public let text: String
    public let timestamp: Date
  }

  public struct AddContactInvocation: Sendable, Equatable {
    public let contact: MeshContact
  }

  public struct SendLoginInvocation: Sendable, Equatable {
    public let destination: Data
    public let password: String
  }

  public struct SetChannelInvocation: Sendable, Equatable {
    public let index: UInt8
    public let name: String
    public let secret: Data
  }

  public struct WaitForEventInvocation: Sendable {
    public let filter: EventFilter
    public let timeout: TimeInterval?
  }

  public private(set) var sendMessageInvocations: [SendMessageInvocation] = []
  public private(set) var sendChannelMessageInvocations: [SendChannelMessageInvocation] = []
  public private(set) var getContactsInvocations: [Date?] = []
  public private(set) var getContactPublicKeys: [Data] = []
  public private(set) var addContactInvocations: [AddContactInvocation] = []
  public private(set) var sendLoginInvocations: [SendLoginInvocation] = []
  public private(set) var removeContactPublicKeys: [Data] = []
  public private(set) var resetPathPublicKeys: [Data] = []
  public private(set) var sendPathDiscoveryDestinations: [Data] = []
  public private(set) var getChannelIndices: [UInt8] = []
  public private(set) var setChannelInvocations: [SetChannelInvocation] = []
  public private(set) var waitForEventInvocations: [WaitForEventInvocation] = []
  public private(set) var getMessageTimeouts: [TimeInterval?] = []
  public private(set) var startAutoMessageFetchingCallCount = 0
  public private(set) var stopAutoMessageFetchingCallCount = 0

  // MARK: - Initialization

  public init() {}

  // MARK: - Test Configuration

  /// Sets the contacts returned by `getContacts(since:)`. Actor isolation forbids writing the
  /// stub property directly from a test, so configuration goes through this isolated setter.
  public func setStubbedContacts(_ contacts: [MeshContact]) {
    stubbedContacts = contacts
  }

  /// Sets the self info returned by `currentSelfInfo`, through an isolated setter
  /// for the same actor-isolation reason as `setStubbedContacts`.
  public func setCurrentSelfInfo(_ selfInfo: SelfInfo?) {
    currentSelfInfo = selfInfo
  }

  /// Sets the results returned by successive `sendLogin` calls (isolated setter).
  public func setSendLoginResults(_ results: [Result<MessageSentInfo, Error>]) {
    stubbedSendLoginResults = results
  }

  /// Sets the error thrown by `addContact` (isolated setter).
  public func setAddContactError(_ error: Error?) {
    stubbedAddContactError = error
  }

  // MARK: - Protocol Methods

  public func sendMessage(to destination: Data, text: String, timestamp: Date, attempt: UInt8) async throws -> MessageSentInfo {
    sendMessageInvocations.append(SendMessageInvocation(destination: destination, text: text, timestamp: timestamp, attempt: attempt))
    switch stubbedSendMessageResult {
    case let .success(info):
      return info
    case let .failure(error):
      throw error
    }
  }

  public func sendChannelMessage(channel: UInt8, text: String, timestamp: Date) async throws {
    sendChannelMessageInvocations.append(SendChannelMessageInvocation(channel: channel, text: text, timestamp: timestamp))
    if let error = stubbedSendChannelMessageError {
      throw error
    }
  }

  public func getContacts(since lastModified: Date?) async throws -> [MeshContact] {
    getContactsInvocations.append(lastModified)
    if let error = stubbedGetContactsError {
      throw error
    }
    return stubbedContacts
  }

  public func getContact(publicKey: Data) async throws -> MeshContact? {
    getContactPublicKeys.append(publicKey)
    if let error = stubbedGetContactError {
      throw error
    }
    return stubbedContact
  }

  public func addContact(_ contact: MeshContact) async throws {
    addContactInvocations.append(AddContactInvocation(contact: contact))
    if let error = stubbedAddContactError {
      throw error
    }
  }

  public func removeContact(publicKey: Data) async throws {
    removeContactPublicKeys.append(publicKey)
    if let error = stubbedRemoveContactError {
      throw error
    }
  }

  public func resetPath(publicKey: Data) async throws {
    resetPathPublicKeys.append(publicKey)
    if let error = stubbedResetPathError {
      throw error
    }
  }

  public func sendPathDiscovery(to destination: Data) async throws -> MessageSentInfo {
    sendPathDiscoveryDestinations.append(destination)
    switch stubbedSendPathDiscoveryResult {
    case let .success(info):
      return info
    case let .failure(error):
      throw error
    }
  }

  public func getChannel(index: UInt8) async throws -> ChannelInfo {
    getChannelIndices.append(index)
    if let error = stubbedGetChannelError {
      throw error
    }
    if let channel = stubbedChannels[index] {
      return channel
    }
    // Return a default empty channel
    return ChannelInfo(index: index, name: "", secret: Data(repeating: 0, count: 16))
  }

  public func getChannels(indices: [UInt8]) async throws -> (received: [ChannelInfo], missing: [UInt8]) {
    getChannelIndices.append(contentsOf: indices)
    if let error = stubbedGetChannelError {
      throw error
    }
    // The firmware answers every requested index, so the mock returns one per request.
    let received = indices.map { index in
      stubbedChannels[index] ?? ChannelInfo(index: index, name: "", secret: Data(repeating: 0, count: 16))
    }
    return (received: received, missing: [])
  }

  public func setChannel(index: UInt8, name: String, secret: Data) async throws {
    setChannelInvocations.append(SetChannelInvocation(index: index, name: name, secret: secret))
    if let error = stubbedSetChannelError {
      throw error
    }
  }

  public func shareContact(publicKey: Data) async throws {
    // Stub - not used in current tests
  }

  public func exportContact(publicKey: Data?) async throws -> String {
    // Stub - not used in current tests
    ""
  }

  public func importContact(cardData: Data) async throws {
    // Stub - not used in current tests
  }

  public func changeContactFlags(_ contact: MeshContact, flags: ContactFlags) async throws {
    // Stub - not used in current tests
  }

  public func waitForEvent(filter: EventFilter, timeout: TimeInterval?) async -> MeshEvent? {
    waitForEventInvocations.append(WaitForEventInvocation(filter: filter, timeout: timeout))
    return stubbedWaitForEventResult
  }

  public func getMessage(timeout: TimeInterval?) async throws -> MessageResult {
    getMessageTimeouts.append(timeout)
    switch stubbedGetMessageResult {
    case let .success(result):
      return result
    case let .failure(error):
      throw error
    }
  }

  public func startAutoMessageFetching() async {
    startAutoMessageFetchingCallCount += 1
  }

  public func stopAutoMessageFetching() {
    stopAutoMessageFetchingCallCount += 1
  }

  // MARK: - Test Helpers

  /// Error thrown by remote-access methods that a given test has not configured.
  enum NotStubbed: Error { case method(String) }

  /// Resets all recorded invocations
  public func reset() {
    sendMessageInvocations = []
    sendChannelMessageInvocations = []
    getContactsInvocations = []
    getContactPublicKeys = []
    addContactInvocations = []
    sendLoginInvocations = []
    removeContactPublicKeys = []
    resetPathPublicKeys = []
    sendPathDiscoveryDestinations = []
    getChannelIndices = []
    setChannelInvocations = []
    waitForEventInvocations = []
    getMessageTimeouts = []
    startAutoMessageFetchingCallCount = 0
    stopAutoMessageFetchingCallCount = 0
  }
}

// MARK: - RemoteAccessSessionOps

extension MockMeshCoreSession: RemoteAccessSessionOps {
  public func sendLogin(to destination: Data, password: String) async throws -> MessageSentInfo {
    sendLoginInvocations.append(SendLoginInvocation(destination: destination, password: password))
    guard !stubbedSendLoginResults.isEmpty else { throw NotStubbed.method("sendLogin") }
    switch stubbedSendLoginResults.removeFirst() {
    case let .success(info): return info
    case let .failure(error): throw error
    }
  }

  public func sendLogout(to destination: Data) async throws {}

  public func sendCommand(to destination: Data, command: String, timestamp: Date) async throws -> MessageSentInfo {
    throw NotStubbed.method("sendCommand")
  }

  public func sendKeepAlive(to publicKey: Data, syncSince: UInt32) async throws -> MessageSentInfo {
    throw NotStubbed.method("sendKeepAlive")
  }

  public func requestOwnerInfo(from publicKey: Data) async throws -> OwnerInfoResponse {
    throw NotStubbed.method("requestOwnerInfo")
  }

  public func requestStatus(from publicKey: Data, type: ContactType) async throws -> StatusResponse {
    throw NotStubbed.method("requestStatus")
  }

  public func requestTelemetry(from publicKey: Data) async throws -> TelemetryResponse {
    throw NotStubbed.method("requestTelemetry")
  }

  public func requestNeighbours(
    from publicKey: Data,
    count: UInt8,
    offset: UInt16,
    orderBy: UInt8,
    pubkeyPrefixLength: UInt8
  ) async throws -> NeighboursResponse {
    throw NotStubbed.method("requestNeighbours")
  }

  public func sendMessageWithRetry(
    to destination: Data,
    text: String,
    timestamp: Date,
    maxAttempts: Int,
    floodAfter: Int,
    maxFloodAttempts: Int,
    timeout: TimeInterval?
  ) async throws -> MessageSentInfo? {
    throw NotStubbed.method("sendMessageWithRetry")
  }
}
