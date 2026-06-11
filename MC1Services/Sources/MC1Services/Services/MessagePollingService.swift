import Foundation
import MeshCore
import os

// MARK: - Message Polling Errors

public enum MessagePollingError: Error, Sendable {
    case notConnected
    case pollingFailed
    case sessionError(MeshCoreError)
}

extension MessagePollingError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .notConnected: "Not connected to device."
        case .pollingFailed: "Message polling failed."
        case .sessionError(let e): e.localizedDescription
        }
    }
}

// MARK: - Message Polling Service

/// Service for polling messages from the mesh device.
/// Handles automatic message fetching and contact message routing.
public actor MessagePollingService {

    // MARK: - Properties

    private let session: MeshCoreSession
    private let dataStore: PersistenceStore
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "MessagePolling")

    /// Handler for incoming contact messages
    private var contactMessageHandler: (@Sendable (ContactMessage, ContactDTO?, DeliveryContext) async -> Void)?

    /// Handler for incoming channel messages
    private var channelMessageHandler: (@Sendable (ChannelMessage, ChannelDTO?, DeliveryContext) async -> Void)?

    /// Handler for signed messages (from room servers)
    private var signedMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

    /// Handler for CLI responses (textType = 0x01)
    private var cliMessageHandler: (@Sendable (ContactMessage, ContactDTO?) async -> Void)?

    /// Event monitoring task
    private var eventMonitorTask: Task<Void, Never>?

    /// Whether auto-fetch is currently enabled
    private var isAutoFetchEnabled = false

    /// Device ID for contact lookups
    private var currentRadioID: UUID?

    /// Count of message handlers currently executing
    /// Used to wait for sync-time handlers to complete before resuming notifications
    private var pendingHandlerCount: Int = 0

    /// Whether pollAllMessages() is actively polling and processing messages directly.
    /// When true, the event monitor skips message events to avoid double-processing.
    private var isPolling = false

    // MARK: - Initialization

    public init(
        session: MeshCoreSession,
        dataStore: PersistenceStore
    ) {
        self.session = session
        self.dataStore = dataStore
    }

    deinit {
        eventMonitorTask?.cancel()
    }

    // MARK: - Event Handlers

    /// Set handler for incoming contact messages
    public func setContactMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?, DeliveryContext) async -> Void) {
        contactMessageHandler = handler
    }

    /// Set handler for incoming channel messages
    public func setChannelMessageHandler(_ handler: @escaping @Sendable (ChannelMessage, ChannelDTO?, DeliveryContext) async -> Void) {
        channelMessageHandler = handler
    }

    /// Set handler for signed messages (from room servers)
    public func setSignedMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {
        signedMessageHandler = handler
    }

    /// Set handler for CLI responses (textType = 0x01)
    public func setCLIMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {
        cliMessageHandler = handler
    }

    /// Clears all message handlers. The wired handlers capture the owning
    /// `ServiceContainer` strongly, so leaving them in place keeps the whole
    /// service graph alive after the container is torn down on disconnect.
    public func clearMessageHandlers() {
        contactMessageHandler = nil
        channelMessageHandler = nil
        signedMessageHandler = nil
        cliMessageHandler = nil
    }

    /// Whether any message handler is currently wired.
    var hasMessageHandlersWired: Bool {
        contactMessageHandler != nil || channelMessageHandler != nil
            || signedMessageHandler != nil || cliMessageHandler != nil
    }

    // MARK: - Event Monitoring

    /// Start event monitoring for message handlers without enabling auto-fetch.
    /// Call this before sync to ensure handlers are ready for polled messages.
    /// - Parameter radioID: The radio ID for contact lookups
    public func startMessageEventMonitoring(radioID: UUID) {
        currentRadioID = radioID
        startEventMonitoring()
        logger.info("Message event monitoring started for radio \(radioID)")
    }

    /// Stop event monitoring (also stops auto-fetch if running)
    public func stopMessageEventMonitoring() async {
        if isAutoFetchEnabled {
            await stopAutoFetch()
        } else {
            stopEventMonitoring()
            currentRadioID = nil
        }
    }

    // MARK: - Auto-Fetch Control

    /// Start automatic message fetching for a device.
    /// This enables the session's auto-fetch feature and monitors for incoming messages.
    /// - Parameter radioID: The radio ID for contact lookups
    public func startAutoFetch(radioID: UUID) async {
        guard !isAutoFetchEnabled else { return }

        currentRadioID = radioID
        isAutoFetchEnabled = true

        // Start event monitoring if not already running
        if eventMonitorTask == nil {
            startEventMonitoring()
        }
        await session.startAutoMessageFetching()

        logger.info("Auto-fetch started for radio \(radioID)")
    }

    /// Stop automatic message fetching
    public func stopAutoFetch() async {
        guard isAutoFetchEnabled else { return }

        isAutoFetchEnabled = false

        // Stop session-level auto-fetch
        await session.stopAutoMessageFetching()

        // Stop event monitoring
        stopEventMonitoring()

        logger.info("Auto-fetch stopped")
    }

    /// Pause session-level auto-fetching without stopping event monitoring.
    /// Used during resync to prevent auto-fetch events from inflating handler counts.
    public func pauseAutoFetch() async {
        guard isAutoFetchEnabled else { return }
        await session.stopAutoMessageFetching()
    }

    /// Resume session-level auto-fetching after a pause.
    public func resumeAutoFetch() async {
        guard isAutoFetchEnabled else { return }
        await session.startAutoMessageFetching()
    }

    /// Check if auto-fetch is currently enabled
    public var isAutoFetching: Bool {
        isAutoFetchEnabled
    }

    // MARK: - Manual Polling

    /// Manually poll for one message from the device.
    /// - Returns: The message result (contact message, channel message, or no more messages)
    public func pollMessage() async throws -> MessageResult {
        do {
            return try await session.getMessage()
        } catch let error as MeshCoreError {
            throw MessagePollingError.sessionError(error)
        }
    }

    /// Poll all waiting messages from the device.
    /// - Returns: Count of messages retrieved
    public func pollAllMessages() async throws -> Int {
        isPolling = true
        defer { isPolling = false }
        var count = 0

        // One anchor for the whole drain so every backlog message shares a sort date
        // and forms a single contiguous block positioned at delivery time.
        let blockAnchor = Date()

        while true {
            let result = try await pollMessage()
            switch result {
            case .contactMessage(let msg):
                count += 1
                await handleContactMessage(msg, context: .initialSync(anchor: blockAnchor))
            case .channelMessage(let msg):
                count += 1
                await handleChannelMessage(msg, context: .initialSync(anchor: blockAnchor))
            case .channelDatagram:
                // Datagrams are not user-visible messages; drain queue without counting.
                // A follow-up plan will add a dedicated MC1Services listener for them.
                break
            case .noMoreMessages:
                return count
            }
        }
    }

    /// Wait for all pending message handlers to complete.
    /// Call this after pollAllMessages() to ensure all messages are fully processed
    /// before performing actions that depend on completion (like resuming notifications).
    public func waitForPendingHandlers(timeout: Duration) async -> Bool {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)

        while pendingHandlerCount > 0 {
            if clock.now >= deadline {
                return false
            }
            try? await Task.sleep(for: .milliseconds(10))
        }

        return true
    }

    // MARK: - Event Monitoring

    /// Start monitoring MeshCore events for messages
    private func startEventMonitoring() {
        eventMonitorTask?.cancel()

        eventMonitorTask = Task { [weak self] in
            guard let self else { return }
            let events = await session.events(filter: EventFilter.anyContactMessage.or(.anyChannelMessage))

            for await event in events {
                guard !Task.isCancelled else { break }
                await self.handleEvent(event)
            }
        }
    }

    /// Stop monitoring events
    private func stopEventMonitoring() {
        eventMonitorTask?.cancel()
        eventMonitorTask = nil
    }

    /// Handle incoming MeshCore event
    private func handleEvent(_ event: MeshEvent) async {
        switch event {
        case .contactMessageReceived(let message):
            guard !isPolling else { return }
            pendingHandlerCount += 1
            defer { pendingHandlerCount -= 1 }
            await handleContactMessage(message, context: .live)

        case .channelMessageReceived(let message):
            guard !isPolling else { return }
            pendingHandlerCount += 1
            defer { pendingHandlerCount -= 1 }
            await handleChannelMessage(message, context: .live)

        default:
            break
        }
    }

    // MARK: - Private Message Handlers

    /// Handle incoming contact message
    private func handleContactMessage(_ message: ContactMessage, context: DeliveryContext) async {
        guard let radioID = currentRadioID else {
            logger.warning("Received message but no radio ID set")
            await contactMessageHandler?(message, nil, context)
            return
        }

        // Look up the sender contact
        let contact = try? await dataStore.fetchContact(
            radioID: radioID,
            publicKeyPrefix: message.senderPublicKeyPrefix
        )

        // Route based on text type
        switch message.textType {
        case MeshTextType.cliData.rawValue:
            // CLI responses from repeaters (textType = 0x01)
            await cliMessageHandler?(message, contact)
        case MeshTextType.signedPlain.rawValue:
            // Signed messages from room servers (textType = 0x02)
            await signedMessageHandler?(message, contact)
        default:
            // Regular contact messages (textType = 0x00 or unknown)
            await contactMessageHandler?(message, contact, context)
        }
    }

    /// Handle incoming channel message
    private func handleChannelMessage(_ message: ChannelMessage, context: DeliveryContext) async {
        guard let radioID = currentRadioID else {
            logger.warning("Received channel message but no radio ID set")
            await channelMessageHandler?(message, nil, context)
            return
        }

        // Look up the channel
        let channel = try? await dataStore.fetchChannel(radioID: radioID, index: message.channelIndex)

        await channelMessageHandler?(message, channel, context)
    }
}

// MARK: - MessagePollingServiceProtocol Conformance

extension MessagePollingService: MessagePollingServiceProtocol {
    // Already implements pollAllMessages() -> Int
}

// MARK: - Message Text Type Constants

/// Text type identifiers for mesh messages
public enum MeshTextType: UInt8 {
    case plain = 0x00
    case cliData = 0x01
    case signedPlain = 0x02
}
