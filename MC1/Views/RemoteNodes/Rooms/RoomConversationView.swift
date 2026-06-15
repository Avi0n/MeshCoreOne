import SwiftUI
import MC1Services

/// Full room chat interface
struct RoomConversationView: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.appTheme) private var theme

    @State private var session: RemoteNodeSessionDTO
    @State private var viewModel = RoomConversationViewModel()
    @State private var chatViewModel = ChatViewModel()
    @State private var showingRoomInfo = false
    @State private var roomToAuthenticate: RemoteNodeSessionDTO?
    @State private var isAtBottom = true
    @State private var unreadCount = 0
    @State private var scrollToBottomRequest = 0

    init(session: RemoteNodeSessionDTO) {
        self._session = State(initialValue: session)
    }

    var body: some View {
        makeMessagesView()
            .mentionTapHandling(
                contacts: chatViewModel.allContacts,
                radioID: session.radioID,
                shouldSuppressOpen: { false }
            )
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !session.isConnected {
                    makeDisconnectedBanner()
                } else if session.canPost {
                    makeInputBar()
                } else {
                    makeReadOnlyBanner()
                }
            }
            .animation(.default, value: session.isConnected)
            .navigationHeader(title: session.name, subtitle: connectionStatus)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(L10n.RemoteNodes.RemoteNodes.Room.infoTitle, systemImage: "info.circle") {
                        showingRoomInfo = true
                    }
                }
            }
            .sheet(isPresented: $showingRoomInfo) {
                RoomInfoSheet(session: session)
                    .environment(\.chatViewModel, chatViewModel)
            }
            .sheet(item: $roomToAuthenticate) { sessionToAuth in
                RoomAuthenticationSheet(session: sessionToAuth) { authenticatedSession in
                    roomToAuthenticate = nil
                    session = authenticatedSession
                }
                .presentationSizing(.page)
            }
            .task {
                viewModel.configure(
                    roomServerService: { appState.services?.roomServerService },
                    dataStore: { appState.services?.dataStore },
                    syncCoordinator: { appState.syncCoordinator },
                    notificationService: { appState.services?.notificationService }
                )
                chatViewModel.configure(
                    dependencies: ChatViewModel.Dependencies(
                        dataStore: { appState.offlineDataStore },
                        messageService: { appState.services?.messageService },
                        notificationService: { appState.services?.notificationService },
                        channelService: { appState.services?.channelService },
                        roomServerService: { appState.services?.roomServerService },
                        contactService: { appState.services?.contactService },
                        syncCoordinator: { appState.syncCoordinator },
                        connectionState: { appState.connectionState },
                        connectedDevice: { appState.connectedDevice },
                        currentRadioID: { appState.currentRadioID },
                        session: { appState.services?.session },
                        reactionService: { appState.services?.reactionService },
                        chatSendQueueService: { appState.services?.chatSendQueueService },
                        inlineImageDimensionsStore: { nil },
                        prefetchDataStore: { nil }
                    ),
                    onNavigateToMap: { appState.navigation.navigateToMap(coordinate: $0) },
                    linkPreviewCache: nil,
                    chatCoordinatorRegistry: nil,
                    conversation: nil
                )
                await chatViewModel.loadAllContacts(radioID: session.radioID)
                await viewModel.loadMessages(for: session)
            }
            .task(id: appState.servicesVersion) {
                // Track the active room so foreground banners for it are suppressed.
                // Keyed on servicesVersion so a reconnect, which mints a fresh
                // NotificationService, re-asserts this on the new instance.
                appState.services?.notificationService.setActiveConversation(roomSessionID: session.id)
            }
            .task {
                for await event in appState.messageEventStream.events() {
                    await viewModel.handleEvent(event)
                }
            }
            .onChange(of: appState.sessionStateChangeCount) { _, _ in
                Task {
                    await viewModel.refreshSession()
                    if let updated = viewModel.session {
                        session = updated
                    }
                }
            }
            .task(id: session.isConnected) {
                guard session.isConnected else { return }
                await appState.services?.remoteNodeService.startSessionKeepAlive(
                    sessionID: session.id, publicKey: session.publicKey
                )
            }
            .onChange(of: session.isConnected) { _, isConnected in
                if isConnected {
                    AccessibilityNotification.Announcement(
                        L10n.RemoteNodes.RemoteNodes.Room.reconnected
                    ).post()
                }
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    // Re-clear the tray: notifications usually arrive while
                    // backgrounded with this room already on screen.
                    Task {
                        await appState.services?.notificationService
                            .removeDeliveredNotifications(forRoomSessionID: session.id)
                        await appState.services?.notificationService.updateBadgeCount()
                    }
                    if session.isConnected {
                        Task {
                            await appState.services?.remoteNodeService.startSessionKeepAlive(
                                sessionID: session.id, publicKey: session.publicKey
                            )
                        }
                    }
                }
            }
            .onDisappear {
                // Only clear if this room still owns the active slot; a newer room's
                // .task may have already claimed it before this view tears down.
                if appState.services?.notificationService.activeRoomSessionID == session.id {
                    appState.services?.notificationService.activeRoomSessionID = nil
                }
                Task {
                    await appState.services?.remoteNodeService.stopSessionKeepAlive(
                        sessionID: session.id
                    )
                }
            }
    }

    private var connectionStatus: String {
        if session.isConnected {
            return session.permissionLevel.localizedName
        }
        return L10n.RemoteNodes.RemoteNodes.Room.disconnected
    }

    // MARK: - Subviews

    private func makeMessagesView() -> some View {
        MessagesView(
            hasLoadedOnce: viewModel.hasLoadedOnce,
            messages: viewModel.messages,
            isAtBottom: $isAtBottom,
            unreadCount: $unreadCount,
            scrollToBottomRequest: $scrollToBottomRequest,
            session: session,
            theme: theme,
            onRetry: { id in
                Task { await viewModel.retryMessage(id: id) }
            }
        )
    }

    private func makeInputBar() -> some View {
        ChatInputBar(
            text: $viewModel.composingText,
            focusRequest: 0,
            placeholder: L10n.RemoteNodes.RemoteNodes.Room.publicMessage,
            maxBytes: ProtocolLimits.maxDirectMessageLength,
            isEncrypted: false
        ) { text in
            scrollToBottomRequest += 1
            Task { await viewModel.sendMessage(text: text) }
        }
    }

    private func makeReadOnlyBanner() -> some View {
        RoomStatusBanner(
            icon: "eye",
            title: L10n.RemoteNodes.RemoteNodes.Room.viewOnlyBanner,
            hint: L10n.RemoteNodes.RemoteNodes.Room.viewOnlyHint,
            style: AnyShapeStyle(.secondary),
            isBold: false,
            action: { roomToAuthenticate = session }
        )
    }

    private func makeDisconnectedBanner() -> some View {
        RoomStatusBanner(
            icon: "exclamationmark.triangle.fill",
            title: L10n.RemoteNodes.RemoteNodes.Room.disconnectedBanner,
            hint: L10n.RemoteNodes.RemoteNodes.Room.disconnectedHint,
            style: AnyShapeStyle(.orange),
            isBold: true,
            action: { roomToAuthenticate = session }
        )
    }
}

// MARK: - Messages View

private struct MessagesView: View {
    let hasLoadedOnce: Bool
    let messages: [RoomMessageDTO]
    @Binding var isAtBottom: Bool
    @Binding var unreadCount: Int
    @Binding var scrollToBottomRequest: Int
    let session: RemoteNodeSessionDTO
    let theme: Theme
    let onRetry: (UUID) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @Environment(\.openURL) private var openURL

    var body: some View {
        Group {
            if !hasLoadedOnce {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if messages.isEmpty {
                EmptyMessagesView(session: session)
            } else {
                let timestampVisibleIDs = Self.timestampVisibleIDs(in: messages)
                ChatTableView(
                    items: messages,
                    cellContent: { message in
                        messageBubble(for: message, showTimestamp: timestampVisibleIDs.contains(message.id))
                            .environment(\.appTheme, theme)
                            .environment(\.openURL, openURL)
                    },
                    contentBackground: theme.surfaces?.canvas,
                    themeID: theme.id,
                    appearanceToken: AppearanceToken.make(colorScheme: colorScheme, contrast: colorSchemeContrast),
                    isAtBottom: $isAtBottom,
                    unreadCount: $unreadCount,
                    scrollToBottomRequest: $scrollToBottomRequest,
                    scrollToMentionRequest: .constant(0),
                    scrollToDividerRequest: .constant(0),
                    isDividerVisible: .constant(false)
                )
                .overlay(alignment: .bottomTrailing) {
                    ScrollToBottomButton(
                        isVisible: !isAtBottom,
                        unreadCount: unreadCount,
                        onTap: { scrollToBottomRequest += 1 }
                    )
                    .padding(.trailing, 16)
                    .padding(.bottom, 8)
                }
            }
        }
        .themedCanvas(theme)
    }

    private func messageBubble(for message: RoomMessageDTO, showTimestamp: Bool) -> some View {
        RoomMessageBubble(
            message: message,
            showTimestamp: showTimestamp,
            onRetry: message.status == .failed ? {
                onRetry(message.id)
            } : nil
        )
    }

    /// Single pass over the array producing the set of message IDs whose timestamp is shown,
    /// so each cell does an O(1) lookup instead of an O(n) `firstIndex` per body evaluation.
    private static func timestampVisibleIDs(in messages: [RoomMessageDTO]) -> Set<UUID> {
        var visible = Set<UUID>()
        for index in messages.indices where RoomConversationViewModel.shouldShowTimestamp(at: index, in: messages) {
            visible.insert(messages[index].id)
        }
        return visible
    }
}

// MARK: - Empty Messages View

private struct EmptyMessagesView: View {
    let session: RemoteNodeSessionDTO

    var body: some View {
        VStack(spacing: 16) {
            NodeAvatar(publicKey: session.publicKey, role: .roomServer, size: 80)

            Text(session.name)
                .font(.title2)
                .bold()

            Text(L10n.RemoteNodes.RemoteNodes.Room.noMessagesYet)
                .foregroundStyle(.secondary)

            if session.canPost {
                Text(L10n.RemoteNodes.RemoteNodes.Room.beFirstToPost)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

// MARK: - Room Status Banner

private struct RoomStatusBanner: View {
    let icon: String
    let title: String
    let hint: String
    let style: AnyShapeStyle
    let isBold: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                HStack {
                    Image(systemName: icon)
                    Text(title)
                }
                .bold(isBold)
                Text(hint)
                    .font(.caption)
            }
            .font(.subheadline)
            .foregroundStyle(style)
            .frame(maxWidth: .infinity)
            .padding()
            .background(.bar)
        }
        .accessibilityLabel(title)
        .accessibilityHint(hint)
    }
}

#Preview {
    NavigationStack {
        RoomConversationView(
            session: RemoteNodeSessionDTO(
                radioID: UUID(),
                publicKey: Data(repeating: 0x42, count: 32),
                name: "Test Room",
                role: .roomServer,
                isConnected: true,
                permissionLevel: .readWrite
            )
        )
    }
    .environment(\.appState, AppState())
}
