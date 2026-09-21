import MC1Services
import SwiftUI

/// The conversation list rendered as a `ScrollView` + `LazyVStack` rather than a `List`.
/// `List` is backed by `UpdateCoalescingCollectionView`, whose batch-consistency assertion
/// is violated when the selected row is deleted; a `LazyVStack` has no collection view, so
/// that crash cannot occur.
struct ConversationListContent: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  private let viewModel: ChatViewModel
  private let favoriteConversations: [Conversation]
  private let otherConversations: [Conversation]
  private let selectedRoute: ChatRoute?
  private let onSelect: (ChatRoute) -> Void
  private let hasLoadedOnce: Bool
  private let emptyStateMessage: (title: String, description: String, systemImage: String)
  private let onDeleteConversation: (Conversation) -> Void
  @Binding private var selectedFilter: ChatFilter

  /// Leading inset for the inter-row divider, aligning it under the row text past the avatar
  /// (row horizontal padding 16 + avatar 44 + avatar-to-text spacing 12).
  private static let rowSeparatorLeadingInset: CGFloat = 72

  init(
    viewModel: ChatViewModel,
    favoriteConversations: [Conversation],
    otherConversations: [Conversation],
    selectedFilter: Binding<ChatFilter>,
    hasLoadedOnce: Bool,
    emptyStateMessage: (title: String, description: String, systemImage: String),
    selectedRoute: ChatRoute?,
    onSelect: @escaping (ChatRoute) -> Void,
    onDeleteConversation: @escaping (Conversation) -> Void
  ) {
    self.viewModel = viewModel
    self.favoriteConversations = favoriteConversations
    self.otherConversations = otherConversations
    self.selectedRoute = selectedRoute
    self.onSelect = onSelect
    _selectedFilter = selectedFilter
    self.hasLoadedOnce = hasLoadedOnce
    self.emptyStateMessage = emptyStateMessage
    self.onDeleteConversation = onDeleteConversation
  }

  var body: some View {
    Group {
      if !hasLoadedOnce {
        loadingBody
      } else {
        TimelineView(.everyMinute) { context in
          loadedBody(referenceDate: context.date)
        }
      }
    }
    // Warm the top registry-capacity conversations after load so open lands settled.
    // Keep warm work off LazyVStack rows: appear/disappear would start and cancel tasks while scrolling.
    .task(id: hasLoadedOnce) {
      guard hasLoadedOnce else { return }
      await prewarmTopConversations()
    }
  }

  private var loadingBody: some View {
    ScrollView {
      LazyVStack(spacing: 0) {
        Section {} header: { filterHeader }
      }
    }
    .overlay { ProgressView() }
  }

  private func loadedBody(referenceDate: Date) -> some View {
    ScrollView {
      LazyVStack(spacing: 0) {
        Section {
          if hasNoConversations {
            emptyState
          } else {
            rows(referenceDate: referenceDate)
          }
        } header: {
          filterHeader
        }
      }
    }
    .swipeActionsContainerIfAvailable()
  }

  private var filterHeader: some View {
    ChatFilterPicker(selection: $selectedFilter)
      .frame(maxWidth: .infinity)
      .pinnedFilterHeaderBackground(theme)
  }

  private var hasNoConversations: Bool {
    favoriteConversations.isEmpty && otherConversations.isEmpty
  }

  private var emptyState: some View {
    ContentUnavailableView {
      Label(emptyStateMessage.title, systemImage: emptyStateMessage.systemImage)
    } description: {
      Text(emptyStateMessage.description)
    } actions: {
      if selectedFilter != .all {
        Button(L10n.Chats.Chats.Filter.clear) {
          selectedFilter = .all
        }
      }
    }
    .containerRelativeFrame([.horizontal, .vertical])
  }

  /// One unified section, favorites first by concatenation, with an inset divider between rows.
  private func rows(referenceDate: Date) -> some View {
    let ordered = favoriteConversations + otherConversations
    return ForEach(Array(ordered.enumerated()), id: \.element.id) { index, conversation in
      rowView(conversation, referenceDate: referenceDate)
        .transition(.opacity)
      if index < ordered.count - 1 {
        Divider().padding(.leading, Self.rowSeparatorLeadingInset)
      }
    }
  }

  /// Warms the top conversations on first load, up to the registry's LRU
  /// capacity. Staggered so the per-conversation fetch and item build don't land
  /// on the main actor in one burst; `prefetchConversation` no-ops for any that
  /// are already warm.
  private func prewarmTopConversations() async {
    let ordered = favoriteConversations + otherConversations
    for conversation in ordered.prefix(ChatCoordinatorRegistry.defaultCapacity) {
      guard !Task.isCancelled else { return }
      warm(conversation)
      try? await Task.sleep(for: .milliseconds(30))
    }
  }

  /// Kicks off the coordinator warm for one conversation. Rooms have no
  /// coordinator; skipped.
  private func warm(_ conversation: Conversation) {
    guard let conversationType = ChatRoute(conversation: conversation).chatConversationType else { return }
    appState.prefetchConversation(
      conversationType,
      envInputs: appState.chatEnvInputs(
        for: conversationType,
        themeID: theme.id,
        isDark: colorScheme == .dark,
        isHighContrast: colorSchemeContrast == .increased,
        contentSizeCategory: AppearanceToken.contentSizeCategoryToken(dynamicTypeSize)
      )
    )
  }

  @ViewBuilder
  private func rowView(_ conversation: Conversation, referenceDate: Date) -> some View {
    let route = ChatRoute(conversation: conversation)
    ConversationSelectionRow(
      conversation: conversation,
      viewModel: viewModel,
      referenceDate: referenceDate,
      isSelected: selectedRoute == route,
      onSelect: { onSelect(route) },
      onDelete: { onDeleteConversation(conversation) }
    )
  }
}

// MARK: - Row Layout

private enum ConversationRowLayout {
  static let horizontalPadding: CGFloat = 16
  static let verticalPadding: CGFloat = 6
}

/// Renders a conversation's row body, shared by the selection and navigation rows.
private struct ConversationRowLabel: View {
  let conversation: Conversation
  let viewModel: ChatViewModel
  let referenceDate: Date

  var body: some View {
    Group {
      switch conversation {
      case let .direct(contact):
        ConversationRow(contact: contact, viewModel: viewModel, referenceDate: referenceDate)
      case let .channel(channel):
        ChannelConversationRow(channel: channel, viewModel: viewModel, referenceDate: referenceDate)
      case let .room(session):
        RoomConversationRow(session: session, viewModel: viewModel, referenceDate: referenceDate)
      }
    }
    .padding(.horizontal, ConversationRowLayout.horizontalPadding)
    .padding(.vertical, ConversationRowLayout.verticalPadding)
    .frame(maxWidth: .infinity, alignment: .leading)
    .contentShape(.rect)
  }
}

// MARK: - Extracted Rows

private struct ConversationSelectionRow: View {
  let conversation: Conversation
  let viewModel: ChatViewModel
  let referenceDate: Date
  let isSelected: Bool
  let onSelect: () -> Void
  let onDelete: () -> Void

  private var isDeleting: Bool {
    viewModel.deletingIDs.contains(conversation.id)
  }

  var body: some View {
    Button(action: onSelect) {
      ConversationRowLabel(conversation: conversation, viewModel: viewModel, referenceDate: referenceDate)
    }
    .buttonStyle(.plain)
    .selectedRowHighlight(isSelected: isSelected)
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .deletingRowOverlay(isDeleting: isDeleting)
    .conversationContextMenu(conversation: conversation, viewModel: viewModel, onDelete: onDelete)
    .conversationSwipeActions(conversation: conversation, viewModel: viewModel, onDelete: onDelete)
  }
}
