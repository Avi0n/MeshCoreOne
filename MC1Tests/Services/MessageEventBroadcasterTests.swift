import Testing
import Foundation
import MeshCore
import MC1Services
@testable import MC1

// MARK: - Test Transport

/// Minimal transport stub for creating a ServiceContainer in tests.
private actor StubTransport: MeshTransport {
    var isConnected: Bool { false }
    var receivedData: AsyncStream<Data> {
        AsyncStream { $0.finish() }
    }
    func connect() async throws {}
    func disconnect() async {}
    func send(_ data: Data) async throws {}
}

@Suite("MessageEventBroadcaster Tests")
@MainActor
struct MessageEventBroadcasterTests {

    // MARK: - Default State

    @Test("Default state has nil service references and zero counters")
    func defaultState() {
        let broadcaster = MessageEventBroadcaster()

        #expect(broadcaster.events(after: 0).events.isEmpty)
        #expect(broadcaster.events(after: 0).droppedEvents == false)
        #expect(broadcaster.latestMessage == nil)
        #expect(broadcaster.newMessageCount == 0)
        #expect(broadcaster.sessionStateChangeCount == 0)
        #expect(broadcaster.messageService == nil)
        #expect(broadcaster.remoteNodeService == nil)
        #expect(broadcaster.dataStore == nil)
        #expect(broadcaster.roomServerService == nil)
        #expect(broadcaster.binaryProtocolService == nil)
    }

    // MARK: - Handler Methods

    @Test("handleDirectMessage sets event and increments counter")
    func handleDirectMessage() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()
        let contact = ContactDTO.stub()

        broadcaster.handleDirectMessage(message, from: contact)

        #expect(broadcaster.events(after: 0).events == [.directMessageReceived(message: message, contact: contact)])
        #expect(broadcaster.latestMessage == message)
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleChannelMessage sets event and increments counter")
    func handleChannelMessage() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()

        broadcaster.handleChannelMessage(message, channelIndex: 3)

        #expect(broadcaster.events(after: 0).events == [.channelMessageReceived(message: message, channelIndex: 3)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleRoomMessage sets event and increments counter")
    func handleRoomMessage() {
        let broadcaster = MessageEventBroadcaster()
        let sessionID = UUID()
        let message = RoomMessageDTO.stub(sessionID: sessionID)

        broadcaster.handleRoomMessage(message)

        #expect(broadcaster.events(after: 0).events == [.roomMessageReceived(message: message, sessionID: sessionID)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleAcknowledgement sets event and increments counter")
    func handleAcknowledgement() {
        let broadcaster = MessageEventBroadcaster()

        broadcaster.handleAcknowledgement(ackCode: 0xDEAD)

        #expect(broadcaster.events(after: 0).events == [.messageStatusUpdated(ackCode: 0xDEAD)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleMessageFailed sets event and increments counter")
    func handleMessageFailed() {
        let broadcaster = MessageEventBroadcaster()
        let id = UUID()

        broadcaster.handleMessageFailed(messageID: id)

        #expect(broadcaster.events(after: 0).events == [.messageFailed(messageID: id)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleMessageRetrying sets event with attempt info")
    func handleMessageRetrying() {
        let broadcaster = MessageEventBroadcaster()
        let id = UUID()

        broadcaster.handleMessageRetrying(messageID: id, attempt: 2, maxAttempts: 3)

        #expect(broadcaster.events(after: 0).events == [.messageRetrying(messageID: id, attempt: 2, maxAttempts: 3)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleRoutingChanged sets event with routing info")
    func handleRoutingChanged() {
        let broadcaster = MessageEventBroadcaster()
        let contactID = UUID()

        broadcaster.handleRoutingChanged(contactID: contactID, isFlood: true)

        #expect(broadcaster.events(after: 0).events == [.routingChanged(contactID: contactID, isFlood: true)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleHeardRepeatRecorded sets event with count")
    func handleHeardRepeatRecorded() {
        let broadcaster = MessageEventBroadcaster()
        let id = UUID()

        broadcaster.handleHeardRepeatRecorded(messageID: id, count: 5)

        #expect(broadcaster.events(after: 0).events == [.heardRepeatRecorded(messageID: id, count: 5)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleReactionReceived sets event with summary")
    func handleReactionReceived() {
        let broadcaster = MessageEventBroadcaster()
        let id = UUID()

        broadcaster.handleReactionReceived(messageID: id, summary: "👍x3")

        #expect(broadcaster.events(after: 0).events == [.reactionReceived(messageID: id, summary: "👍x3")])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleRoomMessageStatusUpdated sets event")
    func handleRoomMessageStatusUpdated() {
        let broadcaster = MessageEventBroadcaster()
        let id = UUID()

        broadcaster.handleRoomMessageStatusUpdated(messageID: id)

        #expect(broadcaster.events(after: 0).events == [.roomMessageStatusUpdated(messageID: id)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleRoomMessageFailed sets event")
    func handleRoomMessageFailed() {
        let broadcaster = MessageEventBroadcaster()
        let id = UUID()

        broadcaster.handleRoomMessageFailed(messageID: id)

        #expect(broadcaster.events(after: 0).events == [.roomMessageFailed(messageID: id)])
        #expect(broadcaster.newMessageCount == 1)
    }

    @Test("handleSessionStateChanged increments session counter")
    func handleSessionStateChanged() {
        let broadcaster = MessageEventBroadcaster()
        let sessionID = UUID()

        broadcaster.handleSessionStateChanged(sessionID: sessionID, isConnected: true)

        #expect(broadcaster.sessionStateChangeCount == 1)
    }

    @Test("handleUnknownSender sets event without incrementing message count")
    func handleUnknownSender() {
        let broadcaster = MessageEventBroadcaster()
        let prefix = Data([0xAB, 0xCD])

        broadcaster.handleUnknownSender(keyPrefix: prefix)

        #expect(broadcaster.events(after: 0).events == [.unknownSender(keyPrefix: prefix)])
        #expect(broadcaster.newMessageCount == 0)
    }

    @Test("handleError sets event without incrementing message count")
    func handleError() {
        let broadcaster = MessageEventBroadcaster()

        broadcaster.handleError("test error")

        #expect(broadcaster.events(after: 0).events == [.error("test error")])
        #expect(broadcaster.newMessageCount == 0)
    }

    // MARK: - Counter Accumulation

    @Test("Multiple events accumulate newMessageCount and pendingEvents")
    func counterAccumulation() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()
        let contact = ContactDTO.stub()

        broadcaster.handleDirectMessage(message, from: contact)
        broadcaster.handleChannelMessage(message, channelIndex: 0)
        broadcaster.handleAcknowledgement(ackCode: 1)

        #expect(broadcaster.newMessageCount == 3)
        #expect(broadcaster.events(after: 0).events == [
            .directMessageReceived(message: message, contact: contact),
            .channelMessageReceived(message: message, channelIndex: 0),
            .messageStatusUpdated(ackCode: 1)
        ])
    }

    // MARK: - Cursor-Based Event Consumption

    @Test("events(after:) returns events and advances cursor")
    func eventsAfterCursorBasic() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()

        broadcaster.handleChannelMessage(message, channelIndex: 1)
        broadcaster.handleAcknowledgement(ackCode: 42)

        let (events, newCursor, droppedEvents) = broadcaster.events(after: 0)

        #expect(events == [
            .channelMessageReceived(message: message, channelIndex: 1),
            .messageStatusUpdated(ackCode: 42)
        ])
        #expect(newCursor == 2)
        #expect(droppedEvents == false)

        // Using the new cursor returns only subsequent events
        broadcaster.handleAcknowledgement(ackCode: 99)
        let (laterEvents, _, laterDropped) = broadcaster.events(after: newCursor)
        #expect(laterEvents == [.messageStatusUpdated(ackCode: 99)])
        #expect(laterDropped == false)
    }

    @Test("Multiple views with independent cursors each see all events")
    func multipleConsumersIndependentCursors() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()

        broadcaster.handleChannelMessage(message, channelIndex: 0)
        broadcaster.handleAcknowledgement(ackCode: 1)

        let (view1Events, _, _) = broadcaster.events(after: 0)
        let (view2Events, _, _) = broadcaster.events(after: 0)

        #expect(view1Events == view2Events)
    }

    @Test("events(after:) on empty log returns empty array")
    func eventsAfterEmpty() {
        let broadcaster = MessageEventBroadcaster()

        let (events, newCursor, droppedEvents) = broadcaster.events(after: 0)

        #expect(events.isEmpty)
        #expect(newCursor == 0)
        #expect(droppedEvents == false)
    }

    @Test("Cursor initialized at currentEventSequence skips stale events")
    func cursorSkipsStaleEvents() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()

        // Simulate events arriving before a view mounts
        broadcaster.handleChannelMessage(message, channelIndex: 0)
        broadcaster.handleAcknowledgement(ackCode: 1)

        // View mounts and captures current sequence as its cursor
        let cursor = broadcaster.currentEventSequence

        // New event arrives after mount
        broadcaster.handleAcknowledgement(ackCode: 2)

        let (events, _, _) = broadcaster.events(after: cursor)
        #expect(events == [.messageStatusUpdated(ackCode: 2)])
    }

    @Test("Event log caps at 50 entries")
    func eventLogCapsAtFifty() {
        let broadcaster = MessageEventBroadcaster()

        for i in 0..<60 {
            broadcaster.handleAcknowledgement(ackCode: UInt32(i))
        }

        // Reading from cursor 0 should only see the last 50 events (sequences 10-59)
        let (events, _, droppedEvents) = broadcaster.events(after: 0)
        #expect(events.count == 50)
        #expect(droppedEvents == true)

        // The oldest event should be ackCode 10 (first 10 were pruned)
        #expect(events.first == .messageStatusUpdated(ackCode: 10))
    }

    @Test("droppedEvents is false when cursor is within event log range")
    func droppedEventsFalseWhenCursorInRange() {
        let broadcaster = MessageEventBroadcaster()

        for i in 0..<60 {
            broadcaster.handleAcknowledgement(ackCode: UInt32(i))
        }

        // Cursor 10 matches the oldest retained event (sequence 10), so nothing was dropped
        let (events, _, droppedEvents) = broadcaster.events(after: 10)
        #expect(events.count == 50)
        #expect(droppedEvents == false)
    }

    @Test("droppedEvents is true when mixed event types overflow the buffer")
    func droppedEventsWithMixedEventTypes() {
        let broadcaster = MessageEventBroadcaster()
        let message = MessageDTO.stub()
        let contact = ContactDTO.stub()
        let contactID = UUID()

        // Fill past the 50-event cap with a mix of event types
        for i in 0..<20 {
            broadcaster.handleDirectMessage(message, from: contact)
            broadcaster.handleRoutingChanged(contactID: contactID, isFlood: i.isMultiple(of: 2))
            broadcaster.handleChannelMessage(message, channelIndex: UInt8(i % 5))
        }
        // 60 total events: 20 direct + 20 routing + 20 channel

        let (events, _, droppedEvents) = broadcaster.events(after: 0)
        #expect(events.count == 50)
        #expect(droppedEvents == true)

        // Verify retained events contain all three types (the last 50 of 60)
        #expect(events.contains(where: {
            if case .directMessageReceived = $0 { return true }; return false
        }))
        #expect(events.contains(where: {
            if case .routingChanged = $0 { return true }; return false
        }))
        #expect(events.contains(where: {
            if case .channelMessageReceived = $0 { return true }; return false
        }))
    }

    @Test("Multiple session state changes accumulate counter")
    func sessionStateAccumulation() {
        let broadcaster = MessageEventBroadcaster()

        broadcaster.handleSessionStateChanged(sessionID: UUID(), isConnected: true)
        broadcaster.handleSessionStateChanged(sessionID: UUID(), isConnected: false)
        broadcaster.handleSessionStateChanged(sessionID: UUID(), isConnected: true)

        #expect(broadcaster.sessionStateChangeCount == 3)
    }

    // MARK: - wireServices Integration

    @Test("wireServices assigns all service references")
    func wireServicesAssignsReferences() async throws {
        let broadcaster = MessageEventBroadcaster()
        let session = MeshCoreSession(transport: StubTransport())
        let container = try PersistenceStore.createContainer(inMemory: true)
        let services = ServiceContainer(session: session, modelContainer: container)

        await broadcaster.wireServices(
            services,
            onConversationsChanged: {},
            onReactionReceived: { _ in }
        )

        #expect(broadcaster.messageService != nil)
        #expect(broadcaster.remoteNodeService != nil)
        #expect(broadcaster.dataStore != nil)
        #expect(broadcaster.roomServerService != nil)
        #expect(broadcaster.binaryProtocolService != nil)
    }
}

// MARK: - Test Stubs

private extension MessageDTO {
    static func stub(
        id: UUID = UUID(),
        text: String = "test",
        direction: MessageDirection = .incoming
    ) -> MessageDTO {
        MessageDTO(
            id: id,
            radioID: UUID(),
            contactID: nil,
            channelIndex: nil,
            text: text,
            timestamp: UInt32(Date().timeIntervalSince1970),
            createdAt: Date(),
            direction: direction,
            status: .delivered,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }
}

private extension ContactDTO {
    static func stub(
        id: UUID = UUID(),
        name: String = "Test Contact"
    ) -> ContactDTO {
        ContactDTO(
            id: id,
            radioID: UUID(),
            publicKey: Data(repeating: 0xAA, count: ProtocolLimits.publicKeySize),
            name: name,
            typeRawValue: 0,
            flags: 0,
            outPathLength: 1,
            outPath: Data([0x01]),
            lastAdvertTimestamp: UInt32(Date().timeIntervalSince1970),
            latitude: 0,
            longitude: 0,
            lastModified: UInt32(Date().timeIntervalSince1970),
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0
        )
    }
}

private extension RoomMessageDTO {
    static func stub(
        id: UUID = UUID(),
        sessionID: UUID = UUID(),
        text: String = "room test"
    ) -> RoomMessageDTO {
        RoomMessageDTO(
            sessionID: sessionID,
            authorKeyPrefix: Data(repeating: 0xBB, count: 6),
            authorName: "TestSender",
            text: text,
            timestamp: UInt32(Date().timeIntervalSince1970)
        )
    }
}
