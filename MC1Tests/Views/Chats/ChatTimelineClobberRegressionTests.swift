import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// A cold prime `ChatViewModel` (navigation prefetch, arrival-time refresh)
/// sharing a live conversation's `ChatCoordinator` must never bake items
/// from its own empty preview state over the live view model's loaded
/// items: `ChatTimelineWriter` drops stale writes at the coordinator, and
/// the request-path self-heal repairs any row that still desyncs.
@Suite("Chat timeline clobber regression")
@MainActor
struct ChatTimelineClobberRegressionTests {
  private func makeRegistry() throws -> ChatCoordinatorRegistry {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    return ChatCoordinatorRegistry(dataStore: dataStore)
  }

  private func makeLinkMessage(radioID: UUID, contactID: UUID) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: nil,
      text: "check this out https://example.com/article",
      timestamp: 1000,
      createdAt: Date(timeIntervalSince1970: 1000),
      direction: .incoming,
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

  /// Binds `viewModel` to `coordinator` the way `bindCoordinator` does:
  /// writer and rebuild hooks installed as one act.
  private func bind(_ viewModel: ChatViewModel, to coordinator: ChatCoordinator, role: ChatWriterRole) {
    viewModel.attachCoordinator(coordinator)
    viewModel.timelineWriter = coordinator.bindWriter(
      owner: viewModel,
      role: role,
      renderItemRebuilder: { [weak viewModel] messageID in
        viewModel?.rebuildDisplayItem(for: messageID)
      },
      renderStateInvalidated: { [weak viewModel] in
        viewModel?.buildItems()
      }
    )
  }

  @Test
  func `a stale prime view model cannot bake its cold state over the live timeline`() throws {
    let registry = try makeRegistry()
    let radioID = UUID()
    let contactID = UUID()
    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))
    let message = makeLinkMessage(radioID: radioID, contactID: contactID)

    // The hazardous ordering: the prime binds first (conversation closed),
    // the user then opens the chat, and the prime's in-flight work lands
    // late.
    let primeVM = ChatViewModel()
    bind(primeVM, to: coordinator, role: .prime)
    #expect(primeVM.timelineWriter != nil)

    let liveVM = ChatViewModel()
    bind(liveVM, to: coordinator, role: .interactive)
    let liveWriter = try #require(liveVM.timelineWriter)
    #expect(liveWriter.isCurrent)

    // Live view model appends the row and settles its preview state; the
    // baked item no longer carries the shimmer fragment.
    liveVM.appendMessageIfNew(message)
    liveVM.bake.previewStates[message.id] = .noPreview
    liveVM.rebuildDisplayItem(for: message.id)
    let settledItem = try #require(coordinator.renderState.items.first { $0.id == message.id })
    let renderStateIDAfterSettle = coordinator.renderStateID

    // The stale prime's late rebuild: cold previewStates would bake the
    // shimmer back. The stale writer must drop it at the coordinator.
    primeVM.rebuildDisplayItem(for: message.id)

    let itemAfter = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(itemAfter == settledItem)
    #expect(coordinator.renderStateID == renderStateIDAfterSettle)
  }

  @Test
  func `a prime bind is denied while the conversation is open`() throws {
    let registry = try makeRegistry()
    let radioID = UUID()
    let contactID = UUID()
    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))
    let message = makeLinkMessage(radioID: radioID, contactID: contactID)

    let liveVM = ChatViewModel()
    bind(liveVM, to: coordinator, role: .interactive)
    liveVM.appendMessageIfNew(message)
    let itemBefore = try #require(coordinator.renderState.items.first { $0.id == message.id })

    // A prime arriving while the chat is open gets no writer, and its
    // rebuild attempts are inert.
    let primeVM = ChatViewModel()
    bind(primeVM, to: coordinator, role: .prime)
    #expect(primeVM.timelineWriter == nil)

    primeVM.rebuildDisplayItem(for: message.id)
    let itemAfter = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(itemAfter == itemBefore)

    // The live view model keeps both its hooks and its write access.
    #expect(liveVM.timelineWriter?.isCurrent == true)
  }

  @Test
  func `request-path self-heal rebakes a row that desynced from settled preview state`() throws {
    let registry = try makeRegistry()
    let radioID = UUID()
    let contactID = UUID()
    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))
    let message = makeLinkMessage(radioID: radioID, contactID: contactID)

    let liveVM = ChatViewModel()
    bind(liveVM, to: coordinator, role: .interactive)

    // Bake the row while the preview state is `.idle`: the item carries
    // the shimmer fragment and asks for a fetch.
    liveVM.appendMessageIfNew(message)
    let shimmerItem = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(shimmerItem.shouldRequestPreviewFetch)

    // State settles without the item being rebaked. The next fetch
    // request must repair the row instead of silently skipping.
    liveVM.bake.previewStates[message.id] = .noPreview
    liveVM.requestPreviewFetch(for: message.id)

    let repairedItem = try #require(coordinator.renderState.items.first { $0.id == message.id })
    #expect(repairedItem != shimmerItem)
    #expect(!repairedItem.shouldRequestPreviewFetch)
  }

  @Test
  func `first load of a fresh view model does not cancel in-flight preview fetches`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let contactID = UUID()

    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { dataStore }))
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)

    // A warm-coordinator cell fired its fetch before the load task ran,
    // matching the open-time ordering: cells render from the warm
    // coordinator on the first frame, `performInitialLoad` runs afterwards.
    let message = makeLinkMessage(radioID: radioID, contactID: contactID)
    viewModel.bake.previewStates[message.id] = .loading
    let fetchStandIn = Task<Void, Never> {
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 20_000_000)
      }
    }
    viewModel.previewFetchTasks[message.id] = fetchStandIn

    // First-ever load on this view model: not a conversation switch, so it
    // must not clear preview state or cancel the in-flight fetch.
    let contact = ContactDTO(from: Contact(
      id: contactID,
      radioID: radioID,
      publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: "Fixture",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    ))
    _ = await viewModel.primeInitialMessages(for: contact)

    #expect(!fetchStandIn.isCancelled)
    #expect(viewModel.bake.previewStates[message.id] == .loading)
    #expect(viewModel.previewFetchTasks[message.id] != nil)
    fetchStandIn.cancel()

    // Loading a DIFFERENT conversation afterwards is a switch and must
    // clear.
    let otherContact = ContactDTO(from: Contact(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: 0xCD, count: ProtocolLimits.publicKeySize),
      name: "Other",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    ))
    _ = await viewModel.primeInitialMessages(for: otherContact)
    #expect(viewModel.bake.previewStates[message.id] == nil)
  }

  @Test
  func `an orphaned loading state resets so the fetch can re-fire`() throws {
    let registry = try makeRegistry()
    let radioID = UUID()
    let contactID = UUID()
    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))
    let message = makeLinkMessage(radioID: radioID, contactID: contactID)

    let liveVM = ChatViewModel()
    bind(liveVM, to: coordinator, role: .interactive)
    liveVM.appendMessageIfNew(message)

    // `.loading` with no in-flight task can be reached through in-flight
    // dedup stranding. The request path must treat it as orphaned rather
    // than deadlocking in shimmer.
    liveVM.bake.previewStates[message.id] = .loading
    liveVM.requestPreviewFetch(for: message.id)

    #expect(liveVM.bake.previewStates[message.id] != .loading)
  }
}
