import CoreGraphics
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Exercises the synchronous preview seeding in the item-build path. A fresh
/// view model (every open, prewarm, or refresh) must not build link-bearing
/// rows with cold caches: that bakes the preview fragment as a zero-height
/// `EmptyView` whose later restoration to a card visibly reflows the list on
/// every chat open.
@Suite("ChatViewModel preview seeding")
@MainActor
struct ChatViewModelPreviewSeedTests {
  private func makeMessage(text: String) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: text,
      timestamp: 1000,
      createdAt: Date(timeIntervalSince1970: 1000),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: "Sender",
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  @Test
  func `build inputs detect the message URL synchronously`() {
    let viewModel = ChatViewModel()
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    let message = makeMessage(text: "look at https://example.com/article")

    let inputs = viewModel.makeBuildInputs(for: message, previous: nil)

    #expect(inputs.cachedURL == URL(string: "https://example.com/article"))
    #expect(viewModel.bake.cachedURLs[message.id] != nil,
            "detection must be recorded so later rebuilds skip the scan")
  }

  @Test
  func `build inputs rehydrate a decoded preview in the same call`() throws {
    let viewModel = ChatViewModel()
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    // Unique URL: DecodedPreviewCache is a process singleton shared by tests.
    let urlString = "https://example.com/\(UUID().uuidString)"
    let message = makeMessage(text: "see \(urlString)")
    let url = try #require(URL(string: urlString))

    DecodedPreviewCache.shared.store(
      CachedDecodedPreview(
        dto: LinkPreviewDataDTO(url: urlString, title: "Example", imageWidth: 1200, imageHeight: 630),
        hero: nil,
        icon: nil
      ),
      for: url
    )

    let inputs = viewModel.makeBuildInputs(for: message, previous: nil)

    #expect(inputs.previewState == .loaded,
            "a decoded-cache hit must paint .loaded in the same build, skipping the shimmer")
    #expect(viewModel.bake.loadedPreviews[message.id]?.title == "Example")
  }

  @Test
  func `build inputs resolve the remembered hero aspect for the shimmer`() async throws {
    let storeURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("PreviewSeedTests-\(UUID().uuidString).json")
    let dimensionsStore = InlineImageDimensionsStore(fileURL: storeURL)
    defer { try? FileManager.default.removeItem(at: storeURL) }

    let urlString = "https://example.com/\(UUID().uuidString)"
    let url = try #require(URL(string: urlString))
    await dimensionsStore.save(url: url, size: CGSize(width: 1200, height: 630))

    let viewModel = ChatViewModel()
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    viewModel.configure(
      dependencies: ChatViewModel.Dependencies(
        dataStore: { nil },
        messageService: { nil },
        notificationService: { nil },
        channelService: { nil },
        roomServerService: { nil },
        contactService: { nil },
        syncCoordinator: { nil },
        connectionState: { .disconnected },
        connectedDevice: { nil },
        currentRadioID: { nil },
        session: { nil },
        reactionService: { nil },
        chatSendQueueService: { nil },
        inlineImageDimensionsStore: { dimensionsStore },
        prefetchDataStore: { nil }
      ),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: nil,
      conversation: nil
    )
    let message = makeMessage(text: "see \(urlString)")

    let inputs = viewModel.makeBuildInputs(for: message, previous: nil)

    let aspect = try #require(inputs.previewHeroAspect)
    #expect(abs(aspect - 1200.0 / 630.0) < 0.001,
            "the loading shimmer must reserve the remembered hero footprint")
  }

  @Test
  func `messages without URLs record the negative result and stay fragment-free`() {
    let viewModel = ChatViewModel()
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    let message = makeMessage(text: "no links here")

    let inputs = viewModel.makeBuildInputs(for: message, previous: nil)

    #expect(inputs.cachedURL == nil)
    // The dictionary is `[UUID: URL?]`, so a stored negative result is the double
    // optional `.some(nil)`: the key is present (outer `.some`) while the detected
    // URL is nil (inner `.none`). Rebuilds key on presence to skip re-scanning.
    #expect(viewModel.bake.cachedURLs[message.id] != nil,
            "the detected-no-URL sentinel must be stored so rebuilds skip re-scanning")
    #expect(viewModel.bake.cachedURLs[message.id] == .some(nil))
  }
}
