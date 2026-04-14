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

    @Test("Queue starts empty")
    func queueStartsEmpty() {
        let viewModel = ChatViewModel()
        #expect(viewModel.sendQueueCount == 0)
        #expect(viewModel.isProcessingQueue == false)
    }

    @Test("Send message queues the message")
    func sendMessageQueuesMessage() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)

        let (_, contactDTO) = try await Self.makeContact(context: ctx)
        viewModel.currentContact = contactDTO

        await viewModel.sendMessage(text: "Hello world")

        #expect(viewModel.sendQueueCount == 1)
    }

    @Test("Process queue sends messages in order")
    func processQueueSendsMessagesInOrder() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)

        let (contact, contactDTO) = try await Self.makeContact(context: ctx)
        viewModel.currentContact = contactDTO

        let msg1 = try await ctx.messageService.createPendingMessage(text: "First", to: contactDTO)
        let msg2 = try await ctx.messageService.createPendingMessage(text: "Second", to: contactDTO)
        let msg3 = try await ctx.messageService.createPendingMessage(text: "Third", to: contactDTO)

        viewModel.enqueueMessage(msg1.id, contactID: contact.id)
        viewModel.enqueueMessage(msg2.id, contactID: contact.id)
        viewModel.enqueueMessage(msg3.id, contactID: contact.id)

        #expect(viewModel.sendQueueCount == 3)

        await viewModel.processQueueForTesting()

        #expect(viewModel.sendQueueCount == 0)
        #expect(viewModel.isProcessingQueue == false)

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

        let (contact, contactDTO) = try await Self.makeContact(context: ctx)
        viewModel.currentContact = contactDTO

        let msg1 = try await ctx.messageService.createPendingMessage(text: "First", to: contactDTO)
        let msg2 = try await ctx.messageService.createPendingMessage(text: "Second", to: contactDTO)
        let msg3 = try await ctx.messageService.createPendingMessage(text: "Third", to: contactDTO)

        viewModel.enqueueMessage(msg1.id, contactID: contact.id)
        viewModel.enqueueMessage(msg2.id, contactID: contact.id)
        viewModel.enqueueMessage(msg3.id, contactID: contact.id)

        await viewModel.processQueueForTesting()

        #expect(viewModel.sendQueueCount == 0)
        #expect(viewModel.isProcessingQueue == false)
    }

    @Test("Messages go to correct contact even after navigating away")
    func messagesGoToCorrectContactAfterNavigatingAway() async throws {
        let ctx = try await Self.makeTestContext()
        let viewModel = ChatViewModel()
        viewModel.configure(dataStore: ctx.dataStore, messageService: ctx.messageService, linkPreviewCache: ctx.linkPreviewCache)

        let (alice, aliceDTO) = try await Self.makeContact(context: ctx, name: "Alice", keyByte: 2)
        let (bob, _) = try await Self.makeContact(context: ctx, name: "Bob", keyByte: 3)
        let bobDTO = try #require(try await ctx.dataStore.fetchContact(id: bob.id))

        viewModel.currentContact = aliceDTO

        let msg1 = try await ctx.messageService.createPendingMessage(text: "Hello Alice", to: aliceDTO)
        let msg2 = try await ctx.messageService.createPendingMessage(text: "How are you?", to: aliceDTO)

        viewModel.enqueueMessage(msg1.id, contactID: alice.id)
        viewModel.enqueueMessage(msg2.id, contactID: alice.id)

        // User navigates to Bob's chat before queue finishes
        viewModel.currentContact = bobDTO

        await viewModel.processQueueForTesting()

        let aliceMessages = try await ctx.dataStore.fetchMessages(contactID: alice.id)
        let bobMessages = try await ctx.dataStore.fetchMessages(contactID: bob.id)

        #expect(aliceMessages.count == 2, "Messages should go to Alice")
        #expect(aliceMessages[0].text == "Hello Alice")
        #expect(aliceMessages[1].text == "How are you?")
        #expect(bobMessages.count == 0, "Bob should have no messages")
    }
}
