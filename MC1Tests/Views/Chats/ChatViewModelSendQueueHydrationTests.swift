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

private struct TestContext: @unchecked Sendable {
    let container: ModelContainer
    let dataStore: PersistenceStore
    let session: MeshCoreSession
    let messageService: MessageService
    let linkPreviewCache: MockLinkPreviewCache
    let transport: MockTransport
}

@Suite("ChatViewModel Send Queue Hydration")
@MainActor
struct ChatViewModelSendQueueHydrationTests {

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
            linkPreviewCache: MockLinkPreviewCache(),
            transport: transport
        )
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

    /// Mirrors the production send-closure shape: success path purges by messageID
    /// after the awaiting send call, and onError purges. A CancellationError
    /// thrown from the send closure should bypass both purges (per SendQueue
    /// contract — the cancel branch re-inserts and returns, without invoking
    /// onError). The persisted row must still be present.
    @Test("CancellationError preserves the persisted PendingSend row")
    func cancellationPreservesRow() async throws {
        let ctx = try await Self.makeTestContext()
        let radioID = UUID()
        let messageID = UUID()
        let contactID = UUID()
        let dto = PendingSendDTO(
            id: UUID(),
            radioID: radioID,
            messageID: messageID,
            kind: .dm,
            contactID: contactID,
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: 1,
            enqueuedAt: Date()
        )
        try await ctx.dataStore.upsertPendingSend(dto)

        let dataStore = ctx.dataStore
        let queue = SendQueue<DirectMessageEnvelope>(
            send: { _ in
                throw CancellationError()
            },
            onError: { _, envelope in
                try? await dataStore.deletePendingSendsForMessage(messageID: envelope.messageID)
            },
            onDrain: { _ in }
        )

        let envelope = DirectMessageEnvelope(messageID: messageID, contactID: contactID)
        await queue.enqueue(envelope)
        await queue.awaitDrainCompletion()

        let rows = try await ctx.dataStore.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 1)
        #expect(rows.first?.messageID == messageID)

        // Break the always-throws-CancellationError respawn cycle. Without
        // this the queue's taskCompleted respawns drain on every iteration
        // and the actor is held alive past the test scope.
        await queue.cancelDrain()
    }

    /// Hydration cancelled before its loop drains all rows must remove the
    /// radioID from `hydratedRadios`, so a follow-up configure on the same
    /// radio retries. Without the remove, a mid-flight radio switch would
    /// leave persisted rows stranded until process restart.
    @Test("Cancelling hydration mid-flight allows the same radio to re-hydrate")
    func cancelledHydrationAllowsRetry() async throws {
        let ctx = try await Self.makeTestContext()
        let (_, contactDTO) = try await Self.makeContact(context: ctx)
        let message = try await ctx.messageService.createPendingMessage(text: "Hello", to: contactDTO)
        let radioID = contactDTO.radioID
        let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
        let dto = PendingSendDTO(envelope: envelope, radioID: radioID)
        try await ctx.dataStore.upsertPendingSend(dto)

        let viewModel = ChatViewModel()
        viewModel.configure(
            dataStore: ctx.dataStore,
            messageService: ctx.messageService,
            linkPreviewCache: ctx.linkPreviewCache
        )

        // Trigger hydration, then cancel before the task body can
        // observe the fetched rows. The first Task.isCancelled check
        // inside the loop fires and removes radioID from the set.
        viewModel.hydrateSendQueues(radioID: radioID)
        viewModel.hydrationTask?.cancel()
        await viewModel.hydrationTask?.value

        #expect(!viewModel.hydratedRadios.contains(radioID),
                "Cancelled hydration must remove the radioID from hydratedRadios")

        let rowsAfterCancel = try await ctx.dataStore.fetchPendingSends(radioID: radioID)
        #expect(!rowsAfterCancel.isEmpty,
                "Persisted row should survive a cancelled hydration")

        viewModel.hydrateSendQueues(radioID: radioID)
        await viewModel.hydrationTask?.value
        await viewModel.dmSendQueue?.awaitDrainCompletion()

        let final = try await ctx.dataStore.fetchPendingSends(radioID: radioID)
        #expect(final.isEmpty, "Re-hydration after cancel must drain the row")
    }

    /// Simulates process death: vm1 enqueues a persisted DM, then
    /// `cancelPendingDrain()` releases the SendQueue so vm1 can exit scope.
    /// The persisted PendingSend row remains. A fresh vm2 configures with
    /// the same PersistenceStore + a different MessageService graph;
    /// hydration replays the row and the drain reattempts the send.
    @Test("Pending sends survive ChatViewModel teardown and re-hydrate")
    func crashRecoverySurvivesTeardown() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)

        let device = Device(
            publicKey: Data(repeating: 1, count: 32),
            nodeName: "Test Device"
        )
        try container.mainContext.insert(device)
        try container.mainContext.save()

        let radioID = device.id
        let contact = Contact(
            radioID: radioID,
            publicKey: Data(repeating: 2, count: 32),
            name: "Test Contact"
        )
        try container.mainContext.insert(contact)
        try container.mainContext.save()
        let contactDTO = try #require(try await dataStore.fetchContact(id: contact.id))

        let transport1 = MockTransport()
        let session1 = MeshCoreSession(transport: transport1)
        let messageService1 = MessageService(session: session1, dataStore: dataStore)
        let message = try await messageService1.createPendingMessage(text: "Hello", to: contactDTO)

        let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)

        do {
            let vm1 = ChatViewModel()
            vm1.configure(
                dataStore: dataStore,
                messageService: messageService1,
                linkPreviewCache: MockLinkPreviewCache(),
                activeRadioID: radioID
            )
            await vm1.hydrationTask?.value

            // Persist directly so the row is on disk regardless of whether
            // enqueueDM races with the cancelPendingDrain below.
            let dto = PendingSendDTO(envelope: envelope, radioID: radioID)
            try await dataStore.upsertPendingSend(dto)

            await vm1.cancelPendingDrain()
        }

        let rowsAfterDrop = try await dataStore.fetchPendingSends(radioID: radioID)
        #expect(rowsAfterDrop.count == 1, "PendingSend must survive ChatViewModel teardown")

        let transport2 = MockTransport()
        let session2 = MeshCoreSession(transport: transport2)
        let messageService2 = MessageService(session: session2, dataStore: dataStore)

        let vm2 = ChatViewModel()
        vm2.configure(
            dataStore: dataStore,
            messageService: messageService2,
            linkPreviewCache: MockLinkPreviewCache(),
            activeRadioID: radioID
        )
        await vm2.hydrationTask?.value
        await vm2.dmSendQueue?.awaitDrainCompletion()

        let rowsAfterDrain = try await dataStore.fetchPendingSends(radioID: radioID)
        #expect(rowsAfterDrain.isEmpty, "Hydration drain should have cleared the PendingSend row")
    }

    /// Inserts persisted PendingSend rows for two radios. Configuring vm
    /// with radioA's id should drain (and clear) only radioA's rows;
    /// radioB's rows must remain on disk so a future configure with
    /// radioB's id can hydrate them.
    @Test("Hydrating one radio does not pick up rows from another radio")
    func multiRadioIsolation() async throws {
        let ctx = try await Self.makeTestContext()

        let (_, contactDTO) = try await Self.makeContact(context: ctx)
        let message = try await ctx.messageService.createPendingMessage(text: "Hello", to: contactDTO)

        let radioA = contactDTO.radioID
        let radioB = UUID()

        let envelopeA = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
        let dtoA = PendingSendDTO(envelope: envelopeA, radioID: radioA)

        // Fictional row for radioB — no real message needed; we only assert it survives.
        let dtoB = PendingSendDTO(
            id: UUID(),
            radioID: radioB,
            messageID: UUID(),
            kind: .dm,
            contactID: UUID(),
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: 1,
            enqueuedAt: Date()
        )

        try await ctx.dataStore.upsertPendingSend(dtoA)
        try await ctx.dataStore.upsertPendingSend(dtoB)

        let vm = ChatViewModel()
        vm.configure(
            dataStore: ctx.dataStore,
            messageService: ctx.messageService,
            linkPreviewCache: MockLinkPreviewCache(),
            activeRadioID: radioA
        )
        await vm.hydrationTask?.value
        await vm.dmSendQueue?.awaitDrainCompletion()

        let remainingA = try await ctx.dataStore.fetchPendingSends(radioID: radioA)
        let remainingB = try await ctx.dataStore.fetchPendingSends(radioID: radioB)

        #expect(remainingA.isEmpty, "radioA's row should have drained out")
        #expect(remainingB.count == 1, "radioB's row must not have been touched")
        #expect(remainingB.first?.id == dtoB.id)
    }

    /// Inserts a real Contact + Message + PendingSend row, then verifies
    /// configure(...) drains via the existing message-status pipeline.
    @Test("configure hydrates dm queue from persisted PendingSend rows")
    func hydrateDM() async throws {
        let ctx = try await Self.makeTestContext()

        let (_, contactDTO) = try await Self.makeContact(context: ctx)
        let message = try await ctx.messageService.createPendingMessage(text: "Hello", to: contactDTO)

        let radioID = contactDTO.radioID
        let dto = PendingSendDTO(
            id: UUID(),
            radioID: radioID,
            messageID: message.id,
            kind: .dm,
            contactID: contactDTO.id,
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: 1,
            enqueuedAt: Date()
        )
        try await ctx.dataStore.upsertPendingSend(dto)

        let viewModel = ChatViewModel()
        viewModel.configure(
            dataStore: ctx.dataStore,
            messageService: ctx.messageService,
            linkPreviewCache: ctx.linkPreviewCache,
            activeRadioID: radioID
        )

        await viewModel.hydrationTask?.value
        await viewModel.dmSendQueue?.awaitDrainCompletion()

        let remaining = try await ctx.dataStore.fetchPendingSends(radioID: radioID)
        #expect(remaining.isEmpty, "Hydration drain should have cleared the PendingSend row")
    }
}
