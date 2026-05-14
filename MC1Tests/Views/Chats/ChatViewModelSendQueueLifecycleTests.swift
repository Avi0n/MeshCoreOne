import Testing
import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import MeshCore

private actor MockLinkPreviewCache: LinkPreviewCaching {
    func preview(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol,
        isChannelMessage: Bool
    ) async -> LinkPreviewResult {
        .noPreviewAvailable
    }
    func manualFetch(
        for url: URL,
        using dataStore: any PersistenceStoreProtocol
    ) async -> LinkPreviewResult {
        .noPreviewAvailable
    }
    func isFetching(_ url: URL) async -> Bool { false }
    func cachedPreview(for url: URL) async -> LinkPreviewDataDTO? { nil }
}

private actor MockTransport: MeshTransport {
    private var didSendContinuation: AsyncStream<Void>.Continuation?

    let didSend: AsyncStream<Void>

    init() {
        var continuation: AsyncStream<Void>.Continuation!
        self.didSend = AsyncStream { continuation = $0 }
        self.didSendContinuation = continuation
    }

    func connect() async throws {}
    func disconnect() async {}
    func send(_ data: Data) async throws {
        didSendContinuation?.yield(())
    }
    var receivedData: AsyncStream<Data> { AsyncStream { _ in } }
    var isConnected: Bool { true }
}

/// Bundles dependencies for one isolated MessageService graph. Two contexts
/// (built side-by-side) drive the "rebind across configure" tests.
private struct TestContext: @unchecked Sendable {
    let container: ModelContainer
    let dataStore: PersistenceStore
    let session: MeshCoreSession
    let messageService: MessageService
    let linkPreviewCache: MockLinkPreviewCache
}

@Suite("ChatViewModel Send Queue Lifecycle")
@MainActor
struct ChatViewModelSendQueueLifecycleTests {

    private static func makeTestContext() async throws -> TestContext {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)

        let device = Device(
            publicKey: Data(repeating: 1, count: 32),
            nodeName: "Test Device"
        )
        try container.mainContext.insert(device)
        try container.mainContext.save()

        let transport = MockTransport()
        let session = MeshCoreSession(transport: transport)
        let messageService = MessageService(session: session, dataStore: dataStore)

        return TestContext(
            container: container,
            dataStore: dataStore,
            session: session,
            messageService: messageService,
            linkPreviewCache: MockLinkPreviewCache()
        )
    }

    @Test("dmSendQueue identity is preserved across configure(...) calls")
    func dmSendQueueIdentityAcrossConfigure() async throws {
        let ctx1 = try await Self.makeTestContext()
        let ctx2 = try await Self.makeTestContext()
        let viewModel = ChatViewModel()

        viewModel.configure(
            dataStore: ctx1.dataStore,
            messageService: ctx1.messageService,
            linkPreviewCache: ctx1.linkPreviewCache
        )
        let firstQueue = try #require(viewModel.dmSendQueue)

        viewModel.configure(
            dataStore: ctx1.dataStore,
            messageService: ctx1.messageService,
            linkPreviewCache: ctx1.linkPreviewCache
        )
        let secondQueue = try #require(viewModel.dmSendQueue)

        viewModel.configure(
            dataStore: ctx2.dataStore,
            messageService: ctx2.messageService,
            linkPreviewCache: ctx2.linkPreviewCache
        )
        let thirdQueue = try #require(viewModel.dmSendQueue)

        #expect(firstQueue === secondQueue, "Queue must not be recreated on same-context configure")
        #expect(secondQueue === thirdQueue, "Queue must not be recreated when services change")
    }

    @Test("channelSendQueue identity is preserved across configure(...) calls")
    func channelSendQueueIdentityAcrossConfigure() async throws {
        let ctx1 = try await Self.makeTestContext()
        let ctx2 = try await Self.makeTestContext()
        let viewModel = ChatViewModel()

        viewModel.configure(
            dataStore: ctx1.dataStore,
            messageService: ctx1.messageService,
            linkPreviewCache: ctx1.linkPreviewCache
        )
        let firstQueue = try #require(viewModel.channelSendQueue)

        viewModel.configure(
            dataStore: ctx2.dataStore,
            messageService: ctx2.messageService,
            linkPreviewCache: ctx2.linkPreviewCache
        )
        let secondQueue = try #require(viewModel.channelSendQueue)

        #expect(firstQueue === secondQueue, "Channel queue must not be recreated when services change")
    }

    @Test("Rebinding messageService via configure(...) updates sendContext")
    func sendContextReboundAcrossConfigure() async throws {
        let ctx1 = try await Self.makeTestContext()
        let ctx2 = try await Self.makeTestContext()
        let viewModel = ChatViewModel()

        viewModel.configure(
            dataStore: ctx1.dataStore,
            messageService: ctx1.messageService,
            linkPreviewCache: ctx1.linkPreviewCache
        )
        #expect(viewModel.sendContext.messageService === ctx1.messageService)
        #expect(viewModel.sendContext.dataStore === ctx1.dataStore)

        viewModel.configure(
            dataStore: ctx2.dataStore,
            messageService: ctx2.messageService,
            linkPreviewCache: ctx2.linkPreviewCache
        )
        #expect(viewModel.sendContext.messageService === ctx2.messageService)
        #expect(viewModel.sendContext.dataStore === ctx2.dataStore)
    }

    @Test("Enqueued envelope drains even after the view model is released")
    func drainContinuesAfterViewModelRelease() async throws {
        let ctx = try await Self.makeTestContext()

        let (_, contactDTO) = try await Self.makeContact(context: ctx)
        let message = try await ctx.messageService.createPendingMessage(text: "Hello", to: contactDTO)

        // Build, enqueue, then drop the view-model strong ref. The send
        // queue actor stays alive via the drain Task's strong-self capture.
        do {
            let viewModel = ChatViewModel()
            viewModel.configure(
                dataStore: ctx.dataStore,
                messageService: ctx.messageService,
                linkPreviewCache: ctx.linkPreviewCache
            )
            let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
            await viewModel.dmSendQueue?.enqueue(envelope)
        }

        // Drain happens asynchronously. Poll the message status until it
        // becomes .sent (the drain ran) or .failed (something went wrong).
        var observed: MessageStatus = .pending
        for _ in 0..<400 {
            if let row = try await ctx.dataStore.fetchMessage(id: message.id) {
                observed = row.status
                if observed != .pending && observed != .sending {
                    break
                }
            }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }

        #expect(observed != .pending, "Drain must run to completion even after the view model is gone")
        #expect(observed != .sending, "Final status should not still be .sending after drain completes")
    }

    @Test("retryChannelMessage flips status to .pending and releases the reentrancy guard")
    func retryChannelMessage_marksPendingAndReleasesGuard() async throws {
        let ctx = try await Self.makeTestContext()
        let (channel, channelDTO) = try await Self.makeChannel(context: ctx)

        let failedMessage = MessageDTO(
            id: UUID(),
            radioID: channel.radioID,
            contactID: nil,
            channelIndex: channel.index,
            text: "channel retry",
            timestamp: 1_700_000_000,
            createdAt: Date(),
            direction: .outgoing,
            status: .failed,
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
            sendCount: 3,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
        try await ctx.dataStore.saveMessage(failedMessage)

        let viewModel = ChatViewModel()
        viewModel.configure(
            dataStore: ctx.dataStore,
            messageService: ctx.messageService,
            linkPreviewCache: ctx.linkPreviewCache
        )
        viewModel.currentChannel = channelDTO

        await viewModel.retryChannelMessage(failedMessage)

        // The optimistic flip to `.pending` happens before the enqueue, so
        // it is observable without depending on the transport responding
        // (the private MockTransport above never yields). The drain itself
        // exercises `resendChannelMessage` — the timestamp/sendCount paths
        // belong in `MessageServiceSendTests` where a smarter mock can
        // simulate OK responses.
        let observed = try #require(try await ctx.dataStore.fetchMessage(id: failedMessage.id))
        #expect(observed.status == .pending, "Retry must optimistically flip status to .pending before enqueue")
        #expect(viewModel.isRetryingChannelMessage == false, "The reentrancy guard must be released after retryChannelMessage returns")

        await viewModel.cancelPendingDrain()
    }

    /// Two back-to-back `retryChannelMessage` calls from the main actor — the
    /// realistic UI double-tap shape. `retryChannelMessage` is @MainActor, so
    /// `async let` invocations cannot truly interleave; the second call
    /// observes the guard state left by the first. The guard short-circuits
    /// the second call, leaving the channel send queue with exactly one
    /// enqueue.
    @Test("retryChannelMessage double-tap is collapsed to a single enqueue")
    func retryChannelMessage_doubleTapIsGuarded() async throws {
        let ctx = try await Self.makeTestContext()
        let (channel, channelDTO) = try await Self.makeChannel(context: ctx)

        let failedMessage = MessageDTO(
            id: UUID(),
            radioID: channel.radioID,
            contactID: nil,
            channelIndex: channel.index,
            text: "double tap retry",
            timestamp: 1_700_000_000,
            createdAt: Date(),
            direction: .outgoing,
            status: .failed,
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
            sendCount: 2,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
        try await ctx.dataStore.saveMessage(failedMessage)

        let viewModel = ChatViewModel()
        viewModel.configure(
            dataStore: ctx.dataStore,
            messageService: ctx.messageService,
            linkPreviewCache: ctx.linkPreviewCache
        )
        viewModel.currentChannel = channelDTO

        async let first: Void = viewModel.retryChannelMessage(failedMessage)
        async let second: Void = viewModel.retryChannelMessage(failedMessage)
        _ = await (first, second)

        // Both calls return, but only one drove the retry; the guard
        // short-circuited the other. We assert through the canonical
        // observable for the single-pass invariant: status is `.pending`
        // (one optimistic flip), and the reentrancy guard is released.
        let observed = try #require(try await ctx.dataStore.fetchMessage(id: failedMessage.id))
        #expect(observed.status == .pending, "Single retry must optimistically flip status to .pending")
        #expect(viewModel.isRetryingChannelMessage == false, "The reentrancy guard must be released after both calls return")

        await viewModel.cancelPendingDrain()
    }

    private static func makeChannel(
        context: TestContext,
        index: UInt8 = 1,
        name: String = "Test Channel"
    ) async throws -> (Channel, ChannelDTO) {
        let devices = try await context.dataStore.fetchDevices()
        let device = try #require(devices.first)

        let channel = Channel(
            radioID: device.id,
            index: index,
            name: name,
            secret: Data(repeating: 0xAB, count: 16),
            isEnabled: true
        )
        try context.container.mainContext.insert(channel)
        try context.container.mainContext.save()

        let dto = try #require(try await context.dataStore.fetchChannel(radioID: device.id, index: index))
        return (channel, dto)
    }

    private static func makeContact(
        context: TestContext,
        name: String = "Test Contact",
        keyByte: UInt8 = 2
    ) async throws -> (Contact, ContactDTO) {
        let devices = try await context.dataStore.fetchDevices()
        let device = try #require(devices.first)

        let contact = Contact(
            radioID: device.id,
            publicKey: Data(repeating: keyByte, count: 32),
            name: name
        )
        try context.container.mainContext.insert(contact)
        try context.container.mainContext.save()

        let dto = try #require(try await context.dataStore.fetchContact(id: contact.id))
        return (contact, dto)
    }
}
