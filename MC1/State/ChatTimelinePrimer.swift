import Foundation
import MC1Services
import OSLog

/// Speculative warm path for a closed conversation's shared `ChatCoordinator`.
/// Drives a `.prime`-role `ChatTimeline`: claims the writer, loads contacts
/// before a channel bake, populates the first page, and warms preview
/// metadata for the primed tail. Not `@Observable` and not a stream
/// subscriber; discarded when the prime task completes.
///
/// A prime runs no legacy preview decode (the live open owns that) and logs
/// populate failures, having no error surface of its own.
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
  private let timeline = ChatTimeline(role: .prime)
  private var prefetcher: InlineImagePrefetcher?
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
    timeline.bake.bindInlineImageDimensionsStore(dependencies.inlineImageDimensionsStore)
    configurePrefetcher()
  }

  /// Primes `conversation` into its registry coordinator. No-ops when a live
  /// interactive owner already holds the writer (bind denied).
  func prime(_ conversation: ChatConversationType, envInputs: EnvInputs) async {
    timeline.envInputs = envInputs

    guard let registry = dependencies.registry() else { return }
    let resolved = registry.coordinator(for: conversation.coordinatorID)
    // The timeline's rebake hooks capture it weakly, so an invalidation
    // arriving after this primer is discarded cannot re-bake; the next
    // open's full rebuild repairs the items.
    let bound = timeline.bind(
      resolved,
      dataStore: { [weak self] in self?.dependencies.dataStore() },
      senderTables: { [weak self] in self?.senderTables ?? .empty },
      postApply: nil
    )
    guard bound else { return }

    switch conversation {
    case .dm:
      // DM bubbles never show the sender row.
      senderTables = .empty
    case let .channel(channel):
      // Contacts before the bake so `senderResolutionFor` resolves names.
      senderTables = await fetchSenderTables(radioID: channel.radioID)
    }

    let reactions = dependencies.reactionService().map { service in
      let scope: ReactionIndexScope = switch conversation {
      case let .dm(contact):
        .direct(contact)
      case let .channel(channel):
        .channel(channel, localNodeName: dependencies.connectedDeviceNodeName())
      }
      return ChatTimeline.ReactionIndexing(service: service, scope: scope)
    }

    let outcome = await timeline.open(conversation, reactions: reactions)

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
    guard let prefetcher, timeline.envInputs.previewsEnabled else { return }
    let messages = timeline.messages
    guard !messages.isEmpty else { return }
    let isChannel = switch timeline.conversation {
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
}
