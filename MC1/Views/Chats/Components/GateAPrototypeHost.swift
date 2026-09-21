#if DEBUG
  import MC1Services
  import SwiftUI
  import UIKit

  /// DEBUG host for `-gateAPrototype`. Production `ContentView` mounts `MainTabView`.
  struct GateAPrototypeHost: View {
    @State private var model = GateAPrototypeModel()

    var body: some View {
      GateAPrototypeSplit(model: model)
        .task { await model.finishSeeding() }
    }
  }

  private enum GateANestedPage: Hashable {
    case info
  }

  @Observable
  @MainActor
  private final class GateAPrototypeModel {
    enum Layout {
      static let timelineCount = 40
      static let middleIndex = 20
      static let longTextRepeatCount = 24
      static let baseTimestamp: UInt32 = 1_700_000_000
    }

    var selectedTab: Int = AppTab.chats.rawValue
    var selectedRoute: ChatRoute?
    var columnVisibility: NavigationSplitViewVisibility = .all
    var preferredCompactColumn: NavigationSplitViewColumn = .sidebar
    var nestedPath: [GateANestedPage] = []
    var horizontalSizeClass: UserInterfaceSizeClass = .compact
    var isAtBottom = true
    var unreadCount = 0
    var scrollToBottomRequest = 0
    var composingText = ""
    var inputFocusRequest = 0
    var timelineItems: [MessageItem] = []

    let conversations: [ChatRoute]
    let chatViewModel: ChatViewModel
    let contact: ContactDTO
    let appState = AppState()
    private let coordinator: ChatCoordinator

    var tabBarVisibility: Visibility {
      ChatsSplitPresentation.tabBarVisibility(
        sizeClass: horizontalSizeClass,
        preferredColumn: preferredCompactColumn,
        hasSelection: selectedRoute != nil
      )
    }

    init() {
      let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
      let contact = Self.makeContact(name: "Alex", radioID: radioID)
      let other = Self.makeContact(name: "Sam", radioID: radioID)
      let channel = ChannelDTO(
        id: UUID(),
        radioID: radioID,
        index: 1,
        name: "General",
        secret: Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize),
        isEnabled: true,
        lastMessageDate: nil,
        unreadCount: 0
      )
      self.contact = contact
      conversations = [.direct(contact), .direct(other), .channel(channel)]
      chatViewModel = ChatViewModel()
      coordinator = ChatCoordinator.makeForTesting(
        conversationID: .dm(radioID: radioID, contactID: contact.id)
      )
      chatViewModel.bindCoordinatorForTesting(coordinator)

      var messages: [MessageDTO] = (0..<Layout.timelineCount).map { index in
        let incoming = index.isMultiple(of: 2)
        return Self.makeMessage(
          id: UUID(),
          contactID: contact.id,
          radioID: radioID,
          text: incoming ? "short incoming \(index)" : "short outgoing \(index)",
          direction: incoming ? .incoming : .outgoing,
          timestampOffset: index
        )
      }
      let longText = Array(repeating: "wrapping phrase", count: Layout.longTextRepeatCount)
        .joined(separator: " ")
      messages[Layout.middleIndex] = Self.makeMessage(
        id: UUID(),
        contactID: contact.id,
        radioID: radioID,
        text: longText,
        direction: .outgoing,
        timestampOffset: Layout.middleIndex
      )
      coordinator.replaceAllForTesting(messages)
      chatViewModel.buildItems()
      select(conversations[0])
    }

    func finishSeeding() async {
      await coordinator.buildItemsTask?.value
      coordinator.markLoadedForTesting()
      timelineItems = chatViewModel.items
    }

    func select(_ route: ChatRoute) {
      if selectedRoute?.conversationID != route.conversationID {
        nestedPath = []
      }
      selectedRoute = route
      if horizontalSizeClass == .compact {
        preferredCompactColumn = .detail
      }
    }

    func handlePreferredColumnChange() {
      switch ChatsSplitPresentation.preferredColumnAction(
        preferredColumn: preferredCompactColumn,
        sizeClass: horizontalSizeClass,
        nestedPathIsEmpty: nestedPath.isEmpty,
        hasSelection: selectedRoute != nil
      ) {
      case .clearRootSelection:
        selectedRoute = nil
      case .none:
        break
      }
    }

    func send(_ text: String) {
      let message = Self.makeMessage(
        id: UUID(),
        contactID: contact.id,
        radioID: contact.radioID,
        text: text,
        direction: .outgoing,
        timestampOffset: timelineItems.count
      )
      chatViewModel.appendMessageIfNew(message)
      timelineItems = chatViewModel.items
      scrollToBottomRequest += 1
    }

    private static func makeContact(name: String, radioID: UUID) -> ContactDTO {
      ContactDTO(
        id: UUID(),
        radioID: radioID,
        publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
        name: name,
        typeRawValue: ContactType.chat.rawValue,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        lastModified: 0,
        lastHeardTimestamp: nil,
        nickname: nil,
        isBlocked: false,
        isMuted: false,
        isFavorite: false,
        lastMessageDate: nil,
        unreadCount: 0
      )
    }

    private static func makeMessage(
      id: UUID,
      contactID: UUID,
      radioID: UUID,
      text: String,
      direction: MessageDirection,
      timestampOffset: Int
    ) -> MessageDTO {
      let timestamp = Layout.baseTimestamp + UInt32(timestampOffset * 60)
      return MessageDTO(
        id: id,
        radioID: radioID,
        contactID: contactID,
        channelIndex: nil,
        text: text,
        timestamp: timestamp,
        createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
        direction: direction,
        status: .sent,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        senderKeyPrefix: direction == .incoming ? Data([0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45]) : nil,
        senderNodeName: direction == .incoming ? "Alex" : nil,
        isRead: true,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
      )
    }
  }

  private struct GateAPrototypeSplit: View {
    @Bindable var model: GateAPrototypeModel
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
      TabView(selection: $model.selectedTab) {
        Tab(L10n.Localizable.Tabs.chats, systemImage: "message.fill", value: AppTab.chats.rawValue) {
          chatsSplit
        }
        Tab(L10n.Localizable.Tabs.nodes, systemImage: "flipphone", value: AppTab.nodes.rawValue) {
          Text(L10n.Localizable.Tabs.nodes)
        }
      }
      .toolbarVisibility(model.tabBarVisibility, for: .tabBar)
      .environment(\.appState, model.appState)
      .themedChrome(.default)
      .onAppear { syncTraits() }
      .onChange(of: sizeClass) { _, _ in syncTraits() }
    }

    private var chatsSplit: some View {
      NavigationSplitView(
        columnVisibility: $model.columnVisibility,
        preferredCompactColumn: $model.preferredCompactColumn
      ) {
        sidebar
      } detail: {
        NavigationStack(path: $model.nestedPath) {
          detailRoot
            .navigationDestination(for: GateANestedPage.self) { _ in
              Text("Info")
                .navigationTitle("Info")
                .background {
                  GateAIdentifierView(identifier: "gateA.nestedInfo")
                }
            }
        }
        .background {
          GateAIdentifierView(identifier: "gateA.detail")
        }
      }
      .navigationSplitViewStyle(.balanced)
      .onChange(of: model.preferredCompactColumn) { _, _ in
        model.handlePreferredColumnChange()
      }
      .onChange(of: model.nestedPath) { _, _ in
        model.handlePreferredColumnChange()
      }
    }

    private var sidebar: some View {
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 0) {
          ForEach(model.conversations, id: \.conversationID) { route in
            Button {
              model.select(route)
            } label: {
              Text(title(for: route))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .selectedRowHighlight(isSelected: model.selectedRoute == route)
            .accessibilityAddTraits(model.selectedRoute == route ? .isSelected : [])
            .accessibilityIdentifier("gateA.row.\(route.conversationID.uuidString)")
          }
        }
      }
      .navigationTitle("Gate A")
      .background {
        GateAIdentifierView(identifier: "gateA.sidebar")
      }
    }

    @ViewBuilder
    private var detailRoot: some View {
      if model.selectedRoute != nil {
        conversation
          .navigationTitle(title(for: model.selectedRoute))
          .toolbar {
            ToolbarItem(placement: .primaryAction) {
              Button("Info") {
                model.nestedPath.append(.info)
              }
              .accessibilityIdentifier("gateA.openNested")
            }
          }
      } else {
        ContentUnavailableView("Select a conversation", systemImage: "message")
      }
    }

    private var conversation: some View {
      ChatTiledView(
        items: model.timelineItems,
        cellContent: { item in
          cellFactory.makeContent(for: item)
            .overlay {
              GateAIdentifierView(identifier: "gateA.message.\(item.id.uuidString)")
                .allowsHitTesting(false)
            }
        },
        contentBackground: Theme.default.surfaces?.canvas,
        isAtBottom: $model.isAtBottom,
        unreadCount: $model.unreadCount,
        scrollToBottomRequest: model.scrollToBottomRequest,
        countsTowardUnread: { !$0.envelope.isOutgoing }
      )
      .safeAreaInset(edge: .bottom, spacing: 0) {
        ChatConversationInputBar(
          conversationType: .dm(model.contact),
          composingText: $model.composingText,
          focusRequest: $model.inputFocusRequest,
          nodeNameByteCount: 0,
          onSend: { text in
            model.send(text)
          },
          onWillSend: { model.scrollToBottomRequest += 1 },
          onFocus: { model.scrollToBottomRequest += 1 }
        )
        .chatKeyboardLiftPadding()
        .background {
          GateAIdentifierView(identifier: "gateA.composer")
        }
      }
      .chatKeyboardOwnedLift()
    }

    private var cellFactory: ChatCellContentFactory {
      ChatCellContentFactory(
        contactName: model.contact.displayName,
        deviceName: "Device",
        configuration: .directMessage,
        theme: .default,
        openURL: OpenURLAction { _ in .discarded },
        resolver: BubbleResolver(viewModel: model.chatViewModel),
        actions: BubbleActions(
          onRetryMessage: { _ in },
          onLongPress: { _ in },
          onImageTap: { _ in },
          onRetryInlineImage: { _ in },
          onRequestPreviewFetch: { _ in },
          onManualPreviewFetch: { _ in },
          onMapPreviewTap: { _ in },
          snapshotResolver: { _ in nil },
          requestSnapshot: { _ in },
          retrySnapshot: { _ in },
          onTranslationAction: { _ in }
        )
      )
    }

    private func title(for route: ChatRoute?) -> String {
      switch route {
      case let .direct(contact):
        contact.displayName
      case let .channel(channel):
        channel.displayName
      case let .room(session):
        session.name
      case .none:
        L10n.Localizable.Tabs.chats
      }
    }

    private func syncTraits() {
      let old = model.horizontalSizeClass
      let new = sizeClass ?? .compact
      model.horizontalSizeClass = new
      let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
        from: old,
        to: new,
        hasSelection: model.selectedRoute != nil
      )
      if let visibility = presentation.columnVisibility {
        model.columnVisibility = visibility
      }
      if let column = presentation.preferredColumn {
        model.preferredCompactColumn = column
      }
    }
  }

  private struct GateAIdentifierView: UIViewRepresentable {
    let identifier: String

    func makeUIView(context: Context) -> UIView {
      let view = UIView()
      view.accessibilityIdentifier = identifier
      view.isAccessibilityElement = false
      view.backgroundColor = .clear
      return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
      uiView.accessibilityIdentifier = identifier
    }
  }
#endif
