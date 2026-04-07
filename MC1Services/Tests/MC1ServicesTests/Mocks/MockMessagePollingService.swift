import Foundation
import MeshCore
@testable import MC1Services

/// Mock implementation of MessagePollingServiceProtocol for testing.
///
/// Configure the mock by setting the stub properties before calling methods.
/// Track method calls by examining the recorded invocations.
public actor MockMessagePollingService: MessagePollingServiceProtocol {

    // MARK: - Stubs

    /// Result to return from pollAllMessages
    public var stubbedPollAllMessagesResult: Result<Int, Error> = .success(0)

    // MARK: - Recorded Invocations

    public struct PollAllMessagesInvocation: Sendable {
        public let messageDelay: Duration
        public let breathingInterval: Int
        public let breathingDuration: Duration
    }

    public private(set) var pollAllMessagesInvocations: [PollAllMessagesInvocation] = []
    public private(set) var waitForPendingHandlersInvocations: Int = 0

    // MARK: - Initialization

    public init() {}

    // MARK: - Protocol Methods

    public func pollAllMessages(
        messageDelay: Duration = .zero,
        breathingInterval: Int = 0,
        breathingDuration: Duration = .zero
    ) async throws -> Int {
        pollAllMessagesInvocations.append(PollAllMessagesInvocation(
            messageDelay: messageDelay,
            breathingInterval: breathingInterval,
            breathingDuration: breathingDuration
        ))
        switch stubbedPollAllMessagesResult {
        case .success(let count):
            return count
        case .failure(let error):
            throw error
        }
    }

    public func waitForPendingHandlers(timeout: Duration) async -> Bool {
        waitForPendingHandlersInvocations += 1
        return true
    }

    // MARK: - Captured Handlers

    /// Captured contact message handler (set via setContactMessageHandler)
    public private(set) var capturedContactMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

    /// Captured channel message handler (set via setChannelMessageHandler)
    public private(set) var capturedChannelMessageHandler: (@Sendable (ChannelMessage, ChannelDTO?) async -> Void)?

    /// Captured signed message handler (set via setSignedMessageHandler)
    public private(set) var capturedSignedMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

    /// Captured CLI message handler (set via setCLIMessageHandler)
    public private(set) var capturedCLIMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

    // MARK: - Handler Setter Methods (matching MessagePollingService)

    public func setContactMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {
        capturedContactMessageHandler = handler
    }

    public func setChannelMessageHandler(_ handler: @escaping @Sendable (ChannelMessage, ChannelDTO?) async -> Void) {
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
        pollAllMessagesInvocations = []
        waitForPendingHandlersInvocations = 0
        capturedContactMessageHandler = nil
        capturedChannelMessageHandler = nil
        capturedSignedMessageHandler = nil
        capturedCLIMessageHandler = nil
    }
}
