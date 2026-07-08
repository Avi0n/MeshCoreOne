import Foundation
import MC1Services
import SwiftUI

extension AppState {
  /// Builds the dependency bundle a chat `ChatViewModel` needs. Shared by the
  /// live conversation view and the navigation-time prefetch so the two cannot
  /// drift into wiring different services.
  @MainActor
  func makeChatViewModelDependencies() -> ChatViewModel.Dependencies {
    ChatViewModel.Dependencies(
      dataStore: { self.offlineDataStore },
      messageService: { self.services?.messageService },
      notificationService: { self.services?.notificationService },
      channelService: { self.services?.channelService },
      roomServerService: { self.services?.roomServerService },
      contactService: { self.services?.contactService },
      syncCoordinator: { self.syncCoordinator },
      connectionState: { self.connectionState },
      connectedDevice: { self.connectedDevice },
      currentRadioID: { self.currentRadioID },
      session: { self.services?.session },
      reactionService: { self.services?.reactionService },
      chatSendQueueService: { self.services?.chatSendQueueService },
      inlineImageDimensionsStore: { self.services?.inlineImageDimensionsStore },
      prefetchDataStore: { self.services?.dataStore }
    )
  }

  /// Builds the `EnvInputs` snapshot the chat view model bakes into `MessageItem`s.
  /// Reads the user-preference toggles from `UserDefaults.standard` (matching the
  /// `@AppStorage` reads at render time) and takes the four true-environment values
  /// that only exist inside a SwiftUI view. Shared by `ChatConversationView` and the
  /// navigation-time prefetch so a prefetched timeline bakes identical items and the
  /// on-open rebuild is a no-op swap rather than a content flash.
  @MainActor
  func chatEnvInputs(
    for conversation: ChatConversationType?,
    themeID: String,
    isDark: Bool,
    isHighContrast: Bool,
    contentSizeCategory: String
  ) -> EnvInputs {
    let defaults = UserDefaults.standard
    func bool(_ key: AppStorageKey, _ fallback: Bool) -> Bool {
      defaults.object(forKey: key.rawValue) as? Bool ?? fallback
    }

    let showMapPreviews = bool(.showMapPreviewThumbnails, AppStorageKey.defaultShowMapPreviewThumbnails)
      && !(conversation?.suppressesMapPreviews ?? false)

    return EnvInputs(
      showInlineImages: bool(.showInlineImages, AppStorageKey.defaultShowInlineImages),
      autoPlayGIFs: bool(.autoPlayGIFs, AppStorageKey.defaultAutoPlayGIFs),
      showIncomingPath: bool(.showIncomingPath, AppStorageKey.defaultShowIncomingPath),
      showIncomingHopCount: bool(.showIncomingHopCount, AppStorageKey.defaultShowIncomingHopCount),
      showIncomingRegion: bool(.showIncomingRegion, AppStorageKey.defaultShowIncomingRegion),
      showIncomingSendTime: bool(.showIncomingSendTime, AppStorageKey.defaultShowIncomingSendTime),
      previewsEnabled: bool(.linkPreviewsEnabled, AppStorageKey.defaultLinkPreviewsEnabled),
      isHighContrast: isHighContrast,
      isDark: isDark,
      showMapPreviews: showMapPreviews,
      isOffline: !offlineMapService.isNetworkAvailable,
      currentUserName: localNodeName,
      themeID: themeID,
      contentSizeCategory: contentSizeCategory
    )
  }

  /// Warms the shared `ChatCoordinator` for `conversation` before its view is
  /// pushed, so the conversation renders populated on the first frame instead of
  /// popping in a frame after the push transition (the cold-open jump). The
  /// coordinator persists in the registry, so a re-open reuses the warm entry —
  /// this no-ops when it is already loaded.
  ///
  /// Fire-and-forget on a throwaway view model: only the shared coordinator (held
  /// by the registry) outlives the prime, and `ChatConversationView` rebinds it on
  /// open. The prime deliberately skips notification suppression, unread clearing,
  /// and flood-scope pushes — those belong to the real open, not a speculative warm.
  @MainActor
  func prefetchConversation(_ conversation: ChatConversationType, envInputs: EnvInputs) {
    guard let registry = ensureChatCoordinatorRegistry() else { return }

    // Already warm: the registry entry survives from a prior prefetch or open.
    if let existing = registry.existingCoordinator(for: conversation.coordinatorID),
       existing.renderState.phase == .loaded {
      return
    }

    let viewModel = ChatViewModel()
    Task { @MainActor in
      viewModel.configure(
        dependencies: self.makeChatViewModelDependencies(),
        onNavigateToMap: nil,
        linkPreviewCache: nil,
        chatCoordinatorRegistry: registry,
        conversation: conversation
      )
      viewModel.applyEnvInputs(envInputs)

      switch conversation {
      case let .dm(contact):
        await viewModel.primeInitialMessages(for: contact)
      case let .channel(channel):
        // Contacts first so contactNameSet is populated before buildChannelSenders runs.
        await viewModel.loadAllContacts(radioID: channel.radioID)
        await viewModel.primeInitialChannelMessages(for: channel)
      }
    }
  }
}
