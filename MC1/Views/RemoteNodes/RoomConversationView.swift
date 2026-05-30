import SwiftUI
import MC1Services

/// Full room chat interface
struct RoomConversationView: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @State private var session: RemoteNodeSessionDTO
    @State private var viewModel = RoomConversationViewModel()
    @State private var chatViewModel = ChatViewModel()
    @State private var showingRoomInfo = false
    @State private var roomToAuthenticate: RemoteNodeSessionDTO?
    @State private var isAtBottom = true
    @State private var unreadCount = 0
    @State private var scrollToBottomRequest = 0
    @FocusState private var isInputFocused: Bool

    init(session: RemoteNodeSessionDTO) {
        self._session = State(initialValue: session)
    }

    var body: some View {
        makeMessagesView()
            .mentionTapHandling(
                contacts: chatViewModel.allContacts,
                radioID: session.radioID
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
                viewModel.configure(appState: appState)
                chatViewModel.configure(appState: appState)
                await chatViewModel.loadAllContacts(radioID: session.radioID)
                await viewModel.loadMessages(for: session)
            }
            .task(id: appState.servicesVersion) {
                // Track the active room so foreground banners for it are suppressed.
                // Keyed on servicesVersion so a reconnect, which mints a fresh
                // NotificationService, re-asserts this on the new instance.
                appState.services?.notificationService.activeRoomSessionID = session.id
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
            .refreshable {
                await viewModel.refreshMessages()
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
            return session.permissionLevel.displayName
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
            onRetry: { id in
                Task { await viewModel.retryMessage(id: id) }
            }
        )
    }

    private func makeInputBar() -> some View {
        ChatInputBar(
            text: $viewModel.composingText,
            isFocused: $isInputFocused,
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
    let onRetry: (UUID) -> Void

    var body: some View {
        Group {
            if !hasLoadedOnce {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if messages.isEmpty {
                EmptyMessagesView(session: session)
            } else {
                ChatTableView(
                    items: messages,
                    cellContent: { message in
                        messageBubble(for: message)
                    },
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
    }

    private func messageBubble(for message: RoomMessageDTO) -> some View {
        let index = messages.firstIndex(where: { $0.id == message.id }) ?? 0
        return RoomMessageBubble(
            message: message,
            showTimestamp: RoomConversationViewModel.shouldShowTimestamp(at: index, in: messages),
            onRetry: message.status == .failed ? {
                onRetry(message.id)
            } : nil
        )
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
