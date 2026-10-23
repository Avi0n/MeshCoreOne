import CoreGraphics
import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// Messages appear immediately with preview placeholders. Background image
/// probes and metadata fetches update the existing rows after they resolve.
@Suite("ChatViewModel admission flow")
@MainActor
struct ChatViewModelAdmissionTests {
  // MARK: - Plain text path

  @Test
  func `Plain-text message admits immediately without invoking prefetcher`() async {
    let viewModel = makeBoundViewModel()
    let imageCache = SlowImageProber(delay: .milliseconds(50))
    let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(50))
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let message = makeMessage(text: "hello world, nothing to fetch")
    viewModel.admitIncomingMessage(message, isChannelMessage: false)

    #expect(viewModel.messages.count == 1)
    let probed = await imageCache.probedURLs
    let previewed = await linkCache.fetchedURLs
    #expect(probed.isEmpty)
    #expect(previewed.isEmpty)
  }

  // MARK: - Link-content-off fast path

  @Test
  func `Link-content-off URL message admits immediately without probing or previewing`() async {
    let viewModel = makeBoundViewModel()
    // Master toggle off skips background probes and metadata fetches.
    viewModel.envInputs = makeEnv(previewsEnabled: false)
    let imageCache = SlowImageProber(delay: .milliseconds(50))
    let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(50))
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let message = makeMessage(text: "see https://example.com/cat.png")
    viewModel.admitIncomingMessage(message, isChannelMessage: false)

    #expect(viewModel.messages.count == 1)
    let probed = await imageCache.probedURLs
    let previewed = await linkCache.fetchedURLs
    #expect(probed.isEmpty)
    #expect(previewed.isEmpty)
  }

  // MARK: - Background prefetch

  @Test
  func `URL-bearing message admits immediately and starts image prefetch`() async {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)
    let imageCache = SlowImageProber(delay: .milliseconds(50))
    let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(50))
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let message = makeMessage(text: "see https://example.com/cat.png")
    viewModel.admitIncomingMessage(message, isChannelMessage: false)

    #expect(viewModel.messages.count == 1)
    #expect(await waitUntil { await !imageCache.probedURLs.isEmpty })
    let probed = await imageCache.probedURLs
    #expect(probed.map(\.absoluteString) == ["https://example.com/cat.png"])
  }

  // MARK: - Nil prefetcher path

  @Test
  func `Missing prefetcher falls back to direct append`() {
    let viewModel = makeBoundViewModel()
    viewModel.prefetcher = nil

    let message = makeMessage(text: "https://example.com/cat.png")
    viewModel.admitIncomingMessage(message, isChannelMessage: false)

    #expect(viewModel.messages.count == 1)
  }

  // MARK: - Dimension resolution rebuilds

  @Test
  func `Dimension resolution rebuilds matching message`() async throws {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)

    let url = try #require(URL(string: "https://example.com/cat.png"))
    let message = makeMessage(text: "look \(url.absoluteString)")
    viewModel.appendMessageIfNew(message)
    viewModel.bake.cachedURLs[message.id] = url

    let store = InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
    bind(store, to: viewModel)
    await store.save(url: url, size: CGSize(width: 200, height: 100))

    await viewModel.handleDimensionResolution(url)

    let item = viewModel.items.first { $0.id == message.id }
    #expect(item != nil)
    guard let item else { return }
    let inlineFragment = item.content.compactMap { fragment -> InlineImage? in
      if case let .inlineImage(image) = fragment { return image }
      return nil
    }.first
    #expect(inlineFragment?.cachedAspect == 2.0)
  }

  // MARK: - Pending preview admission

  @Test(arguments: [false, true])
  func `Pending shared preview cannot hold up message admission or the next message`(
    isChannelMessage: Bool
  ) async throws {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)
    configureConversation(viewModel, isChannelMessage: isChannelMessage)
    let metadataFetcher = GatedAdmissionMetadataFetcher()
    let linkCache = LinkPreviewCache(
      service: metadataFetcher,
      preferences: viewModel.linkPreviewPreferences
    )
    let store = makeStore()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    viewModel.configureForTesting(dependencies: .testDefaults(
      dataStore: { dataStore },
      inlineImageDimensionsStore: { store }
    ))
    viewModel.linkPreviewCache = linkCache
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: SlowImageProber(delay: .zero),
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: dataStore
    )

    let url = try #require(URL(string: "https://example.com/\(UUID().uuidString)/slow-page"))
    await store.save(url: url, size: CGSize(width: 200, height: 100))
    let linkMessage = makeMessage(text: url.absoluteString, channel: viewModel.currentChannel)
    let nextMessage = makeMessage(text: "after the link", channel: viewModel.currentChannel)
    viewModel.admitIncomingMessage(linkMessage, isChannelMessage: isChannelMessage)
    viewModel.admitIncomingMessage(nextMessage, isChannelMessage: isChannelMessage)
    #expect(viewModel.messages.map(\.id) == [linkMessage.id, nextMessage.id],
            "Both messages must be admitted while the shared metadata fetch is still pending")
    let preview = viewModel.items.first { $0.id == linkMessage.id }?.content.compactMap { fragment -> LinkPreviewFragmentState? in
      if case let .linkPreview(state) = fragment { state } else { nil }
    }.first
    #expect(preview?.mode == .loading(url))
    #expect(preview?.heroAspectHint == 2.0)
    #expect(viewModel.items.first { $0.id == linkMessage.id }?.shouldRequestPreviewFetch == true)

    #expect(await waitUntil { await metadataFetcher.fetchedURLs == [url] })
    #expect(await linkCache.isFetching(url))
    viewModel.requestPreviewFetch(for: linkMessage.id)
    #expect(await waitUntil { viewModel.bake.previewStates[linkMessage.id] == .loading })

    await metadataFetcher.release()
    let result = await linkCache.preview(for: url, using: dataStore, isChannelMessage: isChannelMessage)
    if case let .loaded(preview) = result {
      #expect(preview.title == "Resolved preview")
    } else {
      Issue.record("The shared fetch must still warm the preview cache after immediate admission")
    }
    #expect(await waitUntil {
      viewModel.items.first { $0.id == linkMessage.id }?.content.contains {
        if case let .linkPreview(state) = $0, case let .loaded(preview, _, _) = state.mode {
          return preview.title == "Resolved preview"
        }
        return false
      } == true
    })
    #expect(await metadataFetcher.fetchedURLs == [url])
    #expect(viewModel.items.first { $0.id == linkMessage.id }?.shouldRequestPreviewFetch == false)
    #expect(viewModel.messages.map(\.id) == [linkMessage.id, nextMessage.id])
  }

  @Test(arguments: [false, true])
  func `Pending image probe cannot hold up message admission or the next message`(
    isChannelMessage: Bool
  ) async throws {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)
    configureConversation(viewModel, isChannelMessage: isChannelMessage)
    let imageCache = GatedAdmissionImageProber()
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: SlowLinkPreviewFetcher(delay: .zero),
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let url = try #require(URL(string: "https://example.com/\(UUID().uuidString)/pending.png"))
    let imageMessage = makeMessage(text: url.absoluteString, channel: viewModel.currentChannel)
    let nextMessage = makeMessage(text: "after the image", channel: viewModel.currentChannel)
    viewModel.admitIncomingMessage(imageMessage, isChannelMessage: isChannelMessage)
    viewModel.admitIncomingMessage(nextMessage, isChannelMessage: isChannelMessage)
    #expect(viewModel.messages.map(\.id) == [imageMessage.id, nextMessage.id])
    let image = viewModel.items.first { $0.id == imageMessage.id }?.content.compactMap { fragment -> InlineImage? in
      if case let .inlineImage(image) = fragment { image } else { nil }
    }.first
    #expect(image != nil)

    #expect(await waitUntil { await imageCache.probedURLs == [url] })
    await imageCache.release()
    #expect(await waitUntil { await imageCache.hasFinished })
    #expect(viewModel.messages.map(\.id) == [imageMessage.id, nextMessage.id])
  }

  @Test(arguments: ["plain text", "https://example.com/cancelled-page"])
  func `Cancelled caller does not append a message or start prefetch`(text: String) async {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)
    let imageCache = SlowImageProber(delay: .zero)
    let linkCache = SlowLinkPreviewFetcher(delay: .zero)
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let message = makeMessage(text: text)
    let admission = Task {
      viewModel.admitIncomingMessage(message, isChannelMessage: false)
    }
    admission.cancel()
    await admission.value
    #expect(viewModel.messages.isEmpty)
    #expect(await imageCache.probedURLs.isEmpty)
    #expect(await linkCache.fetchedURLs.isEmpty)
  }

  // MARK: - Giphy short-code coverage

  @Test
  func `Giphy g:abc short-code starts background image prefetch`() async {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)

    let imageCache = SlowImageProber(delay: .milliseconds(5))
    let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(5))
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let message = makeMessage(text: "g:abc123")
    viewModel.admitIncomingMessage(message, isChannelMessage: false)

    #expect(viewModel.messages.count == 1)
    #expect(await waitUntil { await !imageCache.probedURLs.isEmpty })
    let probed = await imageCache.probedURLs
    // Giphy short-codes expand to direct .gif URLs and hit the probe path.
    #expect(probed.map(\.absoluteString) == ["https://media.giphy.com/media/abc123/giphy.gif"])
  }

  // MARK: - Image-extension URL that serves a page

  @Test
  func `Page-serving image URL reloads as a card on re-entry, not a stranded shimmer`() throws {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)

    // pasteboard-style link: the path carries an image extension, but the host
    // serves an HTML landing page. The unique host isolates the process-wide
    // `InlineImageCache` verdict from other tests.
    let url = try #require(URL(string: "https://pasteboard.co/\(UUID().uuidString).png"))
    // First entry discovered the reroute and loaded the card; both facts outlive
    // chat teardown in process-lifetime caches.
    InlineImageCache.shared.markServesHTMLPage(url)

    let message = makeMessage(text: "shot \(url.absoluteString)")
    viewModel.appendMessageIfNew(message)

    // Re-entry state: the per-VM reroute set was cleared, but the loaded card is
    // restored from the surviving preview cache.
    viewModel.bake.cachedURLs[message.id] = url
    viewModel.bake.previewStates[message.id] = .loaded
    viewModel.bake.loadedPreviews[message.id] = LinkPreviewDataDTO(url: url.absoluteString, title: "Pasteboard")
    viewModel.rebuildDisplayItem(for: message.id)

    let item = try #require(viewModel.items.first { $0.id == message.id })
    let hasInlineImage = item.content.contains { if case .inlineImage = $0 { true } else { false } }
    let cardMode = item.content.compactMap { fragment -> LinkPreviewFragmentState.Mode? in
      if case let .linkPreview(state) = fragment { state.mode } else { nil }
    }.first

    // Must render the loaded card, never an inline image: an inline image with
    // no decoded ref parks at `.loading` forever with no fetch path back.
    #expect(!hasInlineImage)
    if case .loaded = cardMode {} else {
      Issue.record("Expected a loaded link-preview card, got \(String(describing: cardMode))")
    }
  }

  // MARK: - Outgoing prefetch dispatch

  @Test
  func `Outgoing prefetch dispatches when URL present`() async {
    let viewModel = makeBoundViewModel()
    enableLinkMedia(viewModel)
    let imageCache = SlowImageProber(delay: .milliseconds(10))
    let linkCache = SlowLinkPreviewFetcher(delay: .milliseconds(10))
    let store = makeStore()
    bind(store, to: viewModel)
    viewModel.prefetcher = InlineImagePrefetcher(
      imageCache: imageCache,
      linkPreviewCache: linkCache,
      dimensionsStore: store,
      dataStore: AdmissionStubDataStore()
    )

    let message = makeMessage(
      text: "outgoing https://example.com/dog.png",
      direction: .outgoing
    )
    viewModel.appendMessageIfNew(message)
    viewModel.schedulePrefetchForMessage(message, isChannelMessage: false)
    #expect(viewModel.messages.count == 1)

    let deadline = Date().addingTimeInterval(2)
    var probed: [URL] = []
    while Date() < deadline {
      probed = await imageCache.probedURLs
      if !probed.isEmpty { break }
      try? await Task.sleep(for: .milliseconds(25))
    }
    #expect(probed.map(\.absoluteString) == ["https://example.com/dog.png"])
  }

  // MARK: - Helpers

  private func waitUntil(_ condition: () async -> Bool) async -> Bool {
    let waitTimeout: Duration = .seconds(1)
    let pollInterval: Duration = .milliseconds(10)
    let deadline = ContinuousClock.now + waitTimeout
    while ContinuousClock.now < deadline {
      if await condition() { return true }
      try? await Task.sleep(for: pollInterval)
    }
    return await condition()
  }

  private func makeBoundViewModel() -> ChatViewModel {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)
    return viewModel
  }

  /// `EnvInputs` with only the `previewsEnabled` master toggle varied; the rest
  /// mirror `EnvInputs.default`.
  private func makeEnv(previewsEnabled: Bool) -> EnvInputs {
    EnvInputs(
      autoPlayGIFs: true,
      showIncomingPath: false,
      showIncomingHopCount: false,
      showIncomingRegion: false,
      showIncomingHeardCount: false,
      showIncomingSendTime: false,
      previewsEnabled: previewsEnabled,
      isHighContrast: false,
      isDark: false,
      mapBasemapIsDark: false,
      showMapPreviews: false,
      isOffline: false,
      currentUserName: "Me",
      themeID: EnvInputs.defaultThemeID,
      contentSizeCategory: EnvInputs.defaultContentSizeCategory,
      preferredLanguageCode: EnvInputs.defaultPreferredLanguageCode
    )
  }

  /// Turns on the master toggle and DM/channel auto-resolve so the receive-time
  /// prefetch runs and image-dimension probes fire. Both gates (the master
  /// check in the callers and the `allowImageProbes` scope check) are on. Uses
  /// a scratch `UserDefaults` suite so scope never leaks into `.standard`.
  private func enableLinkMedia(_ viewModel: ChatViewModel) {
    viewModel.envInputs = makeEnv(previewsEnabled: true)
    let suite = UserDefaults(suiteName: "AdmissionTests-\(UUID().uuidString)")!
    suite.set(true, forKey: AppStorageKey.linkPreviewsEnabled.rawValue)
    suite.set(true, forKey: AppStorageKey.linkPreviewsAutoResolveDM.rawValue)
    suite.set(true, forKey: AppStorageKey.linkPreviewsAutoResolveChannels.rawValue)
    viewModel.linkPreviewPreferences = LinkPreviewPreferences(defaults: suite)
  }

  private func makeStore() -> InlineImageDimensionsStore {
    InlineImageDimensionsStore(fileURL: Self.makeTempDimensionsURL())
  }

  private func configureConversation(_ viewModel: ChatViewModel, isChannelMessage: Bool) {
    viewModel.linkPreviewPreferences.autoResolveDM = !isChannelMessage
    viewModel.linkPreviewPreferences.autoResolveChannels = isChannelMessage
    if isChannelMessage {
      let channelIndex: UInt8 = 1
      viewModel.currentChannel = ChannelDTO(
        id: UUID(),
        radioID: UUID(),
        index: channelIndex,
        name: "Preview test channel",
        secret: Data(),
        isEnabled: true,
        lastMessageDate: nil,
        unreadCount: 0
      )
    }
  }

  /// Installs the store through `configure` so the provider-backed
  /// `inlineImageDimensionsStore` property serves it, matching production wiring.
  private func bind(_ store: InlineImageDimensionsStore, to viewModel: ChatViewModel) {
    viewModel.configureForTesting(
      dependencies: .testDefaults(inlineImageDimensionsStore: { store })
    )
  }

  private func makeMessage(
    text: String,
    direction: MessageDirection = .incoming,
    channel: ChannelDTO? = nil
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: channel?.radioID ?? UUID(),
      contactID: channel == nil ? UUID() : nil,
      channelIndex: channel?.index,
      text: text,
      timestamp: 1000,
      createdAt: Date(timeIntervalSince1970: 1000),
      direction: direction,
      status: direction == .outgoing ? .pending : .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: direction == .incoming ? "Sender" : nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  private static func makeTempDimensionsURL() -> URL {
    let directory = FileManager.default.temporaryDirectory
      .appending(path: "ChatViewModelAdmissionTests-\(UUID().uuidString)")
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory.appending(path: "dimensions.json")
  }

  private actor GatedAdmissionMetadataFetcher: LinkMetadataFetching {
    private(set) var fetchedURLs: [URL] = []
    private var continuation: CheckedContinuation<Void, Never>?
    private var isReleased = false

    func fetchMetadata(for url: URL) async -> LinkPreviewMetadata? {
      fetchedURLs.append(url)
      if !isReleased {
        await withCheckedContinuation { continuation = $0 }
      }
      return LinkPreviewMetadata(url: url, title: "Resolved preview", imageData: nil, iconData: nil)
    }

    func release() {
      isReleased = true
      continuation?.resume()
      continuation = nil
    }
  }

  private actor GatedAdmissionImageProber: InlineImageDimensionProbing {
    private(set) var probedURLs: [URL] = []
    private(set) var hasFinished = false
    private var continuation: CheckedContinuation<Void, Never>?
    private var isReleased = false

    func probeImageDimensions(url: URL) async -> CGSize? {
      probedURLs.append(url)
      if !isReleased {
        await withCheckedContinuation { continuation = $0 }
      }
      hasFinished = true
      return nil
    }

    func release() {
      isReleased = true
      continuation?.resume()
      continuation = nil
    }
  }
}

// MARK: - Stubs

private actor SlowImageProber: InlineImageDimensionProbing {
  private let delay: Duration
  private(set) var probedURLs: [URL] = []

  init(delay: Duration) {
    self.delay = delay
  }

  func probeImageDimensions(url: URL) async -> CGSize? {
    probedURLs.append(url)
    try? await Task.sleep(for: delay)
    return nil
  }
}

private actor SlowLinkPreviewFetcher: LinkPreviewCaching {
  private let delay: Duration
  private(set) var fetchedURLs: [URL] = []

  init(delay: Duration) {
    self.delay = delay
  }

  func preview(
    for url: URL,
    using dataStore: any PersistenceStoreProtocol,
    isChannelMessage: Bool
  ) async -> LinkPreviewResult {
    fetchedURLs.append(url)
    try? await Task.sleep(for: delay)
    return .noPreviewAvailable
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

private actor AdmissionStubDataStore: PersistenceStoreProtocol {
  // MARK: - Link Preview Data

  func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? {
    nil
  }

  func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {}

  // MARK: - Required Protocol Stubs

  func setInboundHopCount(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?) async throws {}
  func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool {
    false
  }

  func fetchMessage(deduplicationKey: String, radioID: UUID) async throws -> MessageDTO? {
    nil
  }

  func saveMessage(_ dto: MessageDTO) async throws {}
  func fetchMessage(id: UUID) async throws -> MessageDTO? {
    nil
  }

  func fetchMessages(contactID: UUID, limit: Int, offset: Int) async throws -> [MessageDTO] {
    []
  }

  func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int, offset: Int) async throws -> [MessageDTO] {
    []
  }

  func fetchMessageWindow(
    contactID: UUID,
    anchorSortDate: Date?,
    floorLimit: Int
  ) async throws -> (messages: [MessageDTO], hasMore: Bool) {
    ([], false)
  }

  func fetchMessageWindow(
    radioID: UUID,
    channelIndex: UInt8,
    anchorSortDate: Date?,
    floorLimit: Int
  ) async throws -> (messages: [MessageDTO], hasMore: Bool) {
    ([], false)
  }

  func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]] {
    [:]
  }

  func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]] {
    [:]
  }

  func updateMessageStatus(id: UUID, status: MessageStatus) async throws {}
  func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws {}
  func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
  func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws {}
  func updateMessageLinkPreview(id: UUID, url: String?, title: String?, imageData: Data?, iconData: Data?, fetched: Bool) throws {}

  func fetchContacts(radioID: UUID) async throws -> [ContactDTO] {
    []
  }

  func fetchConversations(radioID: UUID) async throws -> [ContactDTO] {
    []
  }

  func fetchContact(id: UUID) async throws -> ContactDTO? {
    nil
  }

  func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? {
    nil
  }

  func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO? {
    nil
  }

  @discardableResult func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> (id: UUID, isNew: Bool) {
    (id: UUID(), isNew: true)
  }

  func saveContact(_ dto: ContactDTO) async throws {}
  func deleteContact(id: UUID) async throws {}
  @discardableResult
  func touchContactHeard(radioID: UUID, publicKey: Data, at date: Date) async throws -> Bool {
    false
  }

  func updateContactLastMessage(contactID: UUID, date: Date?) async throws {}
  func incrementUnreadCount(contactID: UUID) async throws {}
  func clearUnreadCount(contactID: UUID) async throws {}

  func markMentionSeen(messageID: UUID) async throws {}
  func incrementUnreadMentionCount(contactID: UUID) async throws {}
  func decrementUnreadMentionCount(contactID: UUID) async throws {}
  func clearUnreadMentionCount(contactID: UUID) async throws {}
  func incrementChannelUnreadMentionCount(channelID: UUID) async throws {}
  func decrementChannelUnreadMentionCount(channelID: UUID) async throws {}
  func clearChannelUnreadMentionCount(channelID: UUID) async throws {}
  func fetchUnseenMentionIDs(contactID: UUID) async throws -> [UUID] {
    []
  }

  func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) async throws -> [UUID] {
    []
  }

  func deleteMessagesForContact(contactID: UUID) async throws {}
  func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO] {
    []
  }

  func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws {}
  func deleteBlockedChannelSender(radioID: UUID, name: String) async throws {}
  func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws {}
  func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO] {
    []
  }

  func fetchChannels(radioID: UUID) async throws -> [ChannelDTO] {
    []
  }

  func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? {
    nil
  }

  func fetchChannel(id: UUID) async throws -> ChannelDTO? {
    nil
  }

  @discardableResult func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID {
    UUID()
  }

  func saveChannel(_ dto: ChannelDTO) async throws {}
  func deleteChannel(id: UUID) async throws {}
  func updateChannelLastMessage(channelID: UUID, date: Date?) async throws {}
  func incrementChannelUnreadCount(channelID: UUID) async throws {}
  func clearChannelUnreadCount(channelID: UUID) async throws {}
  func clearChannelUnreadCount(radioID: UUID, index: UInt8) async throws {}

  func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO] {
    []
  }

  func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO? {
    nil
  }

  func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO {
    SavedTracePathDTO(id: UUID(), radioID: radioID, name: name, pathBytes: pathBytes, hashSize: hashSize, createdDate: Date(), runs: [])
  }

  func updateSavedTracePathName(id: UUID, name: String) async throws {}
  func deleteSavedTracePath(id: UUID) async throws {}
  func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws {}

  func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String) async throws -> MessageDTO? {
    nil
  }

  func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {}
  func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] {
    []
  }

  func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool {
    false
  }

  func incrementMessageHeardRepeats(id: UUID) async throws -> Int {
    0
  }

  func adoptIncomingPathIfUnknown(id: UUID, pathNodes: Data, pathLength: UInt8) async throws -> Bool {
    false
  }

  func deleteMessageRepeats(messageID: UUID) async throws {}
  func incrementMessageSendCount(id: UUID) async throws -> Int {
    0
  }

  func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws {}

  func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}
  func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
    []
  }

  func countDebugLogEntries() async throws -> Int {
    0
  }

  func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {}
  func clearDebugLogEntries() async throws {}

  func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]] {
    [:]
  }

  func findRxLogEntry(radioID: UUID, channelIndex: UInt8?, senderTimestamp: UInt32) async throws -> RxLogEntryDTO? {
    nil
  }

  func fetchRxLogEntries(
    radioID: UUID,
    channelIndex: UInt8,
    senderTimestamp: UInt32
  ) async throws -> [RxLogEntryDTO] {
    []
  }

  func findRxLogEntryBySenderPrefix(radioID: UUID, senderPrefixByte: UInt8, receivedSince: Date) async throws -> RxLogEntryDTO? {
    nil
  }

  func saveRoomMessage(_ dto: RoomMessageDTO) async throws {}
  func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO? {
    nil
  }

  func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO] {
    []
  }

  func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool {
    false
  }

  func updateRoomMessageStatus(id: UUID, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?) async throws {}
  func updateRoomMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {}
  func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws {}

  func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
    fatalError("Not implemented")
  }

  func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO] {
    []
  }

  func deleteDiscoveredNode(id: UUID) async throws {}
  func clearDiscoveredNodes(radioID: UUID) async throws {}
  func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data> {
    Set()
  }

  func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO] {
    []
  }

  func saveReaction(_ dto: ReactionDTO) async throws {}
  func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool {
    false
  }

  func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws {}
  func deleteReactionsForMessage(messageID: UUID) async throws {}
  func findChannelMessageForReaction(radioID: UUID, channelIndex: UInt8, parsedReaction: ParsedReaction, localNodeName: String?, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? {
    nil
  }

  func fetchChannelMessageCandidates(radioID: UUID, channelIndex: UInt8, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] {
    []
  }

  func fetchDMMessageCandidates(radioID: UUID, contactID: UUID, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> [MessageDTO] {
    []
  }

  func findDMMessageForReaction(radioID: UUID, contactID: UUID, messageHash: String, timestampWindow: ClosedRange<UInt32>, limit: Int) async throws -> MessageDTO? {
    nil
  }

  func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws {}
  func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws {}
  func fetchDevice(id: UUID) async throws -> DeviceDTO? {
    nil
  }

  func fetchDevice(radioID: UUID) async throws -> DeviceDTO? {
    nil
  }

  func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) async throws {}
  func fetchRemoteNodeSession(id: UUID) async throws -> RemoteNodeSessionDTO? {
    nil
  }

  func fetchRemoteNodeSession(publicKey: Data) async throws -> RemoteNodeSessionDTO? {
    nil
  }

  func markSessionDisconnected(_ sessionID: UUID) async throws {}
  func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool {
    false
  }

  func updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus) async throws -> Bool {
    false
  }

  func clearRetryingToSent(id: UUID) async throws -> Bool {
    false
  }

  func hasOutgoingSentDM(ackCode: UInt32) async throws -> Bool {
    false
  }

  func markMessageAsRead(id: UUID) async throws {}
  func incrementPendingSendAttemptCount(messageID: UUID) async throws -> Int? {
    nil
  }

  func saveDevice(_ dto: DeviceDTO) async throws {}
  func fetchRemoteNodeSessionByPrefix(_ prefix: Data) async throws -> RemoteNodeSessionDTO? {
    nil
  }

  func fetchRemoteNodeSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] {
    []
  }

  func fetchConnectedRemoteNodeSessions() async throws -> [RemoteNodeSessionDTO] {
    []
  }

  func saveRemoteNodeSessionDTO(_ dto: RemoteNodeSessionDTO) async throws {}
  func updateRemoteNodeSessionConnection(id: UUID, isConnected: Bool, permissionLevel: RoomPermissionLevel) async throws {}
  func cleanupDuplicateRemoteNodeSessions(publicKey: Data, keepID: UUID) async throws {}
  func deleteRemoteNodeSession(id: UUID) async throws {}
  func incrementRoomUnreadCount(_ sessionID: UUID) async throws {}
  func resetRoomUnreadCount(_ sessionID: UUID) async throws {}
  func findContactByPublicKey(_ publicKey: Data) async throws -> ContactDTO? {
    nil
  }

  func findContactNameByKeyPrefix(_ prefix: Data) async throws -> String? {
    nil
  }

  func saveRxLogEntry(_ dto: RxLogEntryDTO) async throws {}
  func fetchRxLogEntries(radioID: UUID, limit: Int) async throws -> [RxLogEntryDTO] {
    []
  }

  func clearRxLogEntries(radioID: UUID) async throws {}
  func pruneRxLogEntries(radioID: UUID, keepCount: Int, pruneThreshold: Int) async throws {}
  func fetchEntriesWithTransportCode(radioID: UUID, limit: Int) async throws -> [RxLogEntryDTO] {
    []
  }

  func fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Date) async throws -> [RxLogEntryDTO] {
    []
  }

  func batchUpdateRxLogRegion(updates: [(id: UUID, regionScope: String?, regionScopeMatches: [String])]) async throws {}
  func batchUpdateRxLogDecryption(_ updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)]) async throws {}
  @discardableResult
  func batchUpdateChannelMessageRegion(radioID: UUID, updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?, regionScopeMatches: [String])]) async throws -> [UUID] {
    []
  }

  @discardableResult
  func batchUpdateDMMessageRegion(radioID: UUID, updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?, regionScopeMatches: [String])]) async throws -> [UUID] {
    []
  }

  func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws {}

  // swiftlint:disable:next function_parameter_count
  func saveNodeStatusSnapshot(
    nodePublicKey: Data,
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16?,
    postPushCount: UInt16?
  ) async throws -> UUID {
    UUID()
  }

  func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? {
    nil
  }

  func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] {
    []
  }

  func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {}
  func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {}
  func recordNodeStatusSnapshot(nodePublicKey: Data, status: NodeStatusMetrics?, telemetry: [TelemetrySnapshotEntry]?, neighbors: [NeighborSnapshotEntry]?, location: NodeLocationFix?) async throws -> UUID {
    UUID()
  }

  func saveTelemetryOnlySnapshot(nodePublicKey: Data, telemetryEntries: [TelemetrySnapshotEntry]) async throws -> UUID {
    UUID()
  }

  func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {}

  func upsertPendingSend(_ dto: PendingSendDTO) async throws {}
  func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int {
    0
  }

  func fetchPendingSends(radioID: UUID) async throws -> [PendingSendDTO] {
    []
  }

  func deletePendingSend(id: UUID) async throws {}
  func deletePendingSendsForMessage(messageID: UUID) async throws {}
  func hasPendingSend(messageID: UUID) async throws -> Bool {
    false
  }
}
