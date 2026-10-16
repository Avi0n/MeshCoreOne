import Foundation
@testable import MC1Services
import MeshCore

/// Mock implementation of MessagePollingServiceProtocol for testing.
///
/// Configure the mock by setting the stub properties before calling methods.
/// Track method calls by examining the recorded invocations.
public actor MockMessagePollingService: MessagePollingServiceProtocol {
  // MARK: - Stubs

  /// Result to return from pollAllMessages
  public var stubbedPollAllMessagesResult: Result<Int, Error> = .success(0)

  // MARK: - Recorded Invocations

  public private(set) var pollAllMessagesCallCount: Int = 0
  public private(set) var waitForPendingHandlersInvocations: Int = 0
  public private(set) var startAutoFetchRadioIDs: [UUID] = []
  public private(set) var pauseAutoFetchCallCount: Int = 0
  public private(set) var resumeAutoFetchCallCount: Int = 0

  // MARK: - Initialization

  public init() {}

  // MARK: - Protocol Methods

  public func pollAllMessages() async throws -> Int {
    pollAllMessagesCallCount += 1
    switch stubbedPollAllMessagesResult {
    case let .success(count):
      return count
    case let .failure(error):
      throw error
    }
  }

  public func waitForPendingHandlers(timeout: Duration) async -> Bool {
    waitForPendingHandlersInvocations += 1
    return true
  }

  public func startAutoFetch(radioID: UUID) async {
    startAutoFetchRadioIDs.append(radioID)
  }

  public func pauseAutoFetch() async {
    pauseAutoFetchCallCount += 1
  }

  public func resumeAutoFetch() async {
    resumeAutoFetchCallCount += 1
  }

  // MARK: - Captured Handlers

  /// Captured contact message handler (set via setContactMessageHandler)
  public private(set) var capturedContactMessageHandler: (@Sendable (ContactMessage, ContactDTO?, DeliveryContext) async -> Void)?

  /// Captured channel message handler (set via setChannelMessageHandler)
  public private(set) var capturedChannelMessageHandler: (@Sendable (ChannelMessage, ChannelDTO?, DeliveryContext) async -> Void)?

  /// Captured signed message handler (set via setSignedMessageHandler)
  public private(set) var capturedSignedMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

  /// Captured CLI message handler (set via setCLIMessageHandler)
  public private(set) var capturedCLIMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

  // MARK: - Handler Setter Methods (matching MessagePollingService)

  public func setContactMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?, DeliveryContext) async -> Void) {
    capturedContactMessageHandler = handler
  }

  public func setChannelMessageHandler(_ handler: @escaping @Sendable (ChannelMessage, ChannelDTO?, DeliveryContext) async -> Void) {
    capturedChannelMessageHandler = handler
  }

  public func setSignedMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {
    capturedSignedMessageHandler = handler
  }

  public func setCLIMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {
    capturedCLIMessageHandler = handler
  }

  // MARK: - Test Helpers

  /// Resets all recorded invocations and captured handlers
  public func reset() {
    pollAllMessagesCallCount = 0
    waitForPendingHandlersInvocations = 0
    startAutoFetchRadioIDs = []
    pauseAutoFetchCallCount = 0
    resumeAutoFetchCallCount = 0
    capturedContactMessageHandler = nil
    capturedChannelMessageHandler = nil
    capturedSignedMessageHandler = nil
    capturedCLIMessageHandler = nil
  }

  public func setStubbedPollAllMessagesResult(_ result: Result<Int, Error>) {
    stubbedPollAllMessagesResult = result
  }
}
