import Foundation
import MC1Services
import OSLog

/// Speculative warm path for a closed conversation's shared `ChatCoordinator`.
/// Claims a `.prime` writer, loads contacts before a channel bake, populates
/// via `ChatTimelinePopulator`, and warms preview metadata for the primed
/// tail. Not `@Observable` and not a stream subscriber — discarded when the
/// prime task completes.
///
/// A prime skips `decodeLegacyPreviewImages` (`postApply: nil`) — the live open
/// runs the legacy decode — and logs populate failures, having no error surface
/// of its own.
@MainActor
final class ChatTimelinePrimer {
  /// Newest rows whose link/image URLs are warmed after a successful populate.
  private static let previewWarmTailLimit = 10

  private let logger = Logger(subsystem: "com.mc1", category: "ChatTimelinePrimer")

  /// Provider bundle for the prime path. Six providers → nested struct per
  /// the view-model DI rule. App-lifetime `linkPreviewCache` is a plain
  /// init parameter, not a provider.
  struct Dependencies {
    var registry: @MainActor () -> ChatCoordinatorRegistry?
    var dataStore: @MainActor () -> DataStore?
    var reactionService: @MainActor () -> ReactionService?
    /// Live connected device's node name — `connectedDevice?.nodeName`, never
    /// a `"Me"` fallback. Nil (disconnected) means skip indexing outgoing
    /// channel messages when building the reaction scope.
    var connectedDeviceNodeName: @MainActor () -> String?
    var inlineImageDimensionsStore: @MainActor () -> InlineImageDimensionsStore?
    var prefetchDataStore: @MainActor () -> (any PersistenceStoreProtocol)?
  }

  private let dependencies: Dependencies
  private let linkPreviewCache: (any LinkPreviewCaching)?
  private let bake = ChatMessageBakeState()
  private var prefetcher: InlineImagePrefetcher?
  private var timelineWriter: ChatTimelineWriter?
  private weak var coordinator: ChatCoordinator?
  private var envInputs: EnvInputs = .default
  private var primedConversation: ChatConversationType?
  private var senderTables: ChatSenderTables = .empty

  /// Scope preferences read by the image/card prewarm so channel vs DM
  /// auto-resolve toggles stay independent. Internal so tests can inject a
  /// scratch `UserDefaults` suite.
  var linkPreviewPreferences = LinkPreviewPreferences()

  init(
    dependencies: Dependencies,
    linkPreviewCache: (any LinkPreviewCaching)?
  ) {
    self.dependencies = dependencies
    self.linkPreviewCache = linkPreviewCache
    bake.bindInlineImageDimensionsStore(dependencies.inlineImageDimensionsStore)
    configurePrefetcher()
  }

  /// Primes `conversation` into its registry coordinator. No-ops when a live
  /// interactive owner already holds the writer (bind denied).
  func prime(_ conversation: ChatConversationType, envInputs: EnvInputs) async {
    self.envInputs = envInputs
    primedConversation = conversation

    guard let registry = dependencies.registry() else { return }
    let resolved = registry.coordinator(for: conversation.coordinatorID)
    // An invalidation arriving after `prime` returns cannot re-bake: the
    // weak self hook has nilled out. The next open's full rebuild repairs it.
    timelineWriter = resolved.bindWriter(
      owner: self,
      role: .prime,
      renderItemRebuilder: { [weak self] messageID in
        self?.rebakeRow(messageID)
      },
      renderStateInvalidated: { [weak self] in
        self?.rebakeIfCurrent()
      }
    )
    coordinator = resolved
    guard let timelineWriter else { return }

    switch conversation {
    case .dm:
      // DM bubbles never show the sender row.
      senderTables = .empty
    case let .channel(channel):
      // Contacts before the bake so `senderResolutionFor` resolves names.
      senderTables = await fetchSenderTables(radioID: channel.radioID)
    }

    let reactions: ChatTimelinePopulator.ReactionIndexingContext? = {
      guard let reactionService = dependencies.reactionService() else { return nil }
      let scope: ReactionIndexScope = switch conversation {
      case let .dm(contact):
        .direct(contact)
      case let .channel(channel):
        .channel(channel, localNodeName: dependencies.connectedDeviceNodeName())
      }
      return ChatTimelinePopulator.ReactionIndexingContext(
        reactionService: reactionService,
        scope: scope,
        rebakeRow: { [weak self] messageID in
          self?.rebakeRow(messageID)
        }
      )
    }()

    let outcome = await ChatTimelinePopulator.populate(
      conversation,
      writer: timelineWriter,
      dataStore: dependencies.dataStore(),
      bake: bake,
      envInputs: envInputs,
      senderTables: senderTables,
      reactions: reactions,
      postApply: nil
    )

    switch outcome {
    case .loaded:
      await prewarmRecentPreviews()
    case .cancelled, .unavailable:
      break
    case let .failed(error):
      logger.error("Prime failed for \(String(describing: conversation.coordinatorID)): \(error)")
    }
  }

  // MARK: - Private

  private func configurePrefetcher() {
    guard let linkPreviewCache,
          let dimensionsStore = dependencies.inlineImageDimensionsStore(),
          let prefetchDataStore = dependencies.prefetchDataStore() else {
      prefetcher = nil
      return
    }
    prefetcher = InlineImagePrefetcher(
      imageCache: InlineImageCache.shared,
      linkPreviewCache: linkPreviewCache,
      dimensionsStore: dimensionsStore,
      dataStore: prefetchDataStore
    )
  }

  private func fetchSenderTables(radioID: UUID) async -> ChatSenderTables {
    guard let dataStore = dependencies.dataStore() else { return .empty }
    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      return ChatSenderTables(
        contacts: contacts,
        nicknamesByLoweredName: MessageBubbleConfiguration.buildNicknameLookup(from: contacts)
      )
    } catch {
      logger.warning("Failed to load contacts for channel prime: \(error.localizedDescription)")
      return .empty
    }
  }

  /// Warm link-preview metadata (and inline-image dimensions) for the newest
  /// page rows so a later open builds cards synchronously from the caches at
  /// their final height. A no-op without a configured prefetcher or with
  /// previews off. `LinkPreviewCache` dedups in-flight and cached URLs, so
  /// repeat calls cost one dictionary hit per URL.
  ///
  /// This path reaches the network without a `MalwareDomainFilter` check; only
  /// the per-cell fetch paths consult it.
  private func prewarmRecentPreviews() async {
    guard let prefetcher, envInputs.previewsEnabled,
          let messages = coordinator?.messages else { return }
    let isChannel = switch primedConversation {
    case .channel: true
    case .dm, nil: false
    }
    let allowImageProbes = linkPreviewPreferences.shouldAutoResolve(isChannelMessage: isChannel)
    for message in messages.suffix(Self.previewWarmTailLimit)
      where !LinkPreviewService.extractAllURLs(in: message.text).isEmpty {
      await prefetcher.prefetch(
        urlsIn: message.text,
        isChannelMessage: isChannel,
        allowImageProbes: allowImageProbes
      )
    }
  }

  private func rebakeIfCurrent() {
    guard let timelineWriter, timelineWriter.isCurrent,
          let coordinator else { return }
    bake.bakeAll(
      messages: coordinator.messages,
      writer: timelineWriter,
      envInputs: envInputs,
      senderTables: senderTables,
      postApply: nil
    )
  }

  private func rebakeRow(_ messageID: UUID) {
    guard let timelineWriter, timelineWriter.isCurrent,
          let coordinator,
          let message = coordinator.messagesByID[messageID] else { return }
    let previous: MessageDTO? = {
      guard let index = coordinator.messages.firstIndex(where: { $0.id == messageID }),
            index > 0 else { return nil }
      return coordinator.messages[index - 1]
    }()
    let tables = senderTables
    timelineWriter.updateRenderItem(id: messageID) { _ in
      MessageFragmentBuilder.makeItem(
        for: message,
        inputs: bake.makeBuildInputs(
          for: message,
          previous: previous,
          envInputs: envInputs,
          senderTables: tables
        ),
        envInputs: envInputs
      )
    }
  }
}
