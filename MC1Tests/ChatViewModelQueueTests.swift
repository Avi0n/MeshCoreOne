import Testing
import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import MeshCore

// MARK: - Mock Link Preview Cache

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

    func isFetching(_ url: URL) async -> Bool {
        false
    }

    func cachedPreview(for url: URL) async -> LinkPreviewDataDTO? {
        nil
    }
}

// MARK: - Mock Transport

private actor MockTransport: MeshTransport {
    func connect() async throws {}
    func disconnect() async {}
    func send(_ data: Data) async throws {}

    var receivedData: AsyncStream<Data> {
        AsyncStream { _ in }
    }

    var isConnected: Bool {
        true
    }
}

// MARK: - Test Context

/// Bundles all dependencies needed for ChatViewModel queue tests.
private struct TestContext: @unchecked Sendable {
    let container: ModelContainer
    let dataStore: PersistenceStore
    let session: MeshCoreSession
    let messageService: MessageService
    let linkPreviewCache: MockLinkPreviewCache
}

// MARK: - Tests

@Suite("ChatViewModel Queue Tests")
@MainActor
struct ChatViewModelQueueTests {

    /// Creates an in-memory data store seeded with a device.
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
        let linkPreviewCache = MockLinkPreviewCache()

        return TestContext(
            container: container,
            dataStore: dataStore,
            session: session,
            messageService: messageService,
            linkPreviewCache: linkPreviewCache
        )
    }

    /// Creates a contact in the given context and returns its DTO.
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

    private static func enqueue(
        on viewModel: ChatViewModel,
        messageID: UUID,
        contactID: UUID
    ) async {
        let envelope = DirectMessageEnvelope(messageID: messageID, contactID: contactID)
        await viewModel.dmSendQueue?.enqueue(envelope)
    }

    @Test("Queue starts empty")
    func queueStartsEmpty() async {
        let viewModel = ChatViewModel()
        let count = await viewModel.dmSendQueue?.count ?? 0
        #expect(count == 0)
    }

    @Test("Send message persists a pending row")
    func sendMessagePersistsPendingRow() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)
        #expect(viewModel.dmSendQueue != nil)

        let (contact, contactDTO) = try await Self.makeContact(context: ctx)
        viewModel.currentContact = contactDTO

        await viewModel.sendMessage(text: "Hello world")

        // Race-safe assertion: the queue may already have drained by the time we
        // observe (SendQueue drain runs in the actor's own Task). Verify the
        // user-visible side effect — a pending DB row.
        let messages = try await ctx.dataStore.fetchMessages(contactID: contact.id)
        #expect(messages.count == 1, "sendMessage must persist a pending row")
        #expect(messages[0].text == "Hello world")
    }

    @Test("Process queue sends messages in order")
    func processQueueSendsMessagesInOrder() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)
        #expect(viewModel.dmSendQueue != nil)

        let (contact, contactDTO) = try await Self.makeContact(context: ctx)
        viewModel.currentContact = contactDTO

        let msg1 = try await ctx.messageService.createPendingMessage(text: "First", to: contactDTO)
        let msg2 = try await ctx.messageService.createPendingMessage(text: "Second", to: contactDTO)
        let msg3 = try await ctx.messageService.createPendingMessage(text: "Third", to: contactDTO)

        await Self.enqueue(on: viewModel, messageID: msg1.id, contactID: contact.id)
        await Self.enqueue(on: viewModel, messageID: msg2.id, contactID: contact.id)
        await Self.enqueue(on: viewModel, messageID: msg3.id, contactID: contact.id)

        await viewModel.dmSendQueue?.awaitDrainCompletion()

        let finalCount = await viewModel.dmSendQueue?.count ?? -1
        #expect(finalCount == 0)

        let messages = try await ctx.dataStore.fetchMessages(contactID: contact.id)
        #expect(messages.count == 3)
        #expect(messages[0].text == "First")
        #expect(messages[1].text == "Second")
        #expect(messages[2].text == "Third")
    }

    @Test("Queue continues after failure")
    func queueContinuesAfterFailure() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)
        #expect(viewModel.dmSendQueue != nil)

        let (contact, contactDTO) = try await Self.makeContact(context: ctx)
        viewModel.currentContact = contactDTO

        let msg1 = try await ctx.messageService.createPendingMessage(text: "First", to: contactDTO)
        let msg2 = try await ctx.messageService.createPendingMessage(text: "Second", to: contactDTO)
        let msg3 = try await ctx.messageService.createPendingMessage(text: "Third", to: contactDTO)

        await Self.enqueue(on: viewModel, messageID: msg1.id, contactID: contact.id)
        await Self.enqueue(on: viewModel, messageID: msg2.id, contactID: contact.id)
        await Self.enqueue(on: viewModel, messageID: msg3.id, contactID: contact.id)

        await viewModel.dmSendQueue?.awaitDrainCompletion()

        let finalCount = await viewModel.dmSendQueue?.count ?? -1
        #expect(finalCount == 0)
    }

    @Test("Messages go to correct contact even after navigating away")
    func messagesGoToCorrectContactAfterNavigatingAway() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)
        #expect(viewModel.dmSendQueue != nil)

        let (alice, aliceDTO) = try await Self.makeContact(context: ctx, name: "Alice", keyByte: 2)
        let (bob, _) = try await Self.makeContact(context: ctx, name: "Bob", keyByte: 3)
        let bobDTO = try #require(try await ctx.dataStore.fetchContact(id: bob.id))

        viewModel.currentContact = aliceDTO

        let msg1 = try await ctx.messageService.createPendingMessage(text: "Hello Alice", to: aliceDTO)
        let msg2 = try await ctx.messageService.createPendingMessage(text: "How are you?", to: aliceDTO)

        await Self.enqueue(on: viewModel, messageID: msg1.id, contactID: alice.id)
        await Self.enqueue(on: viewModel, messageID: msg2.id, contactID: alice.id)

        // User navigates to Bob's chat before queue finishes
        viewModel.currentContact = bobDTO

        await viewModel.dmSendQueue?.awaitDrainCompletion()

        let aliceMessages = try await ctx.dataStore.fetchMessages(contactID: alice.id)
        let bobMessages = try await ctx.dataStore.fetchMessages(contactID: bob.id)

        #expect(aliceMessages.count == 2, "Messages should go to Alice")
        #expect(aliceMessages[0].text == "Hello Alice")
        #expect(aliceMessages[1].text == "How are you?")
        #expect(bobMessages.count == 0, "Bob should have no messages")
    }
}
