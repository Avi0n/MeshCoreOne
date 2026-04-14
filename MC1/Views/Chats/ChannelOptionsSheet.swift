import SwiftUI
import MC1Services

/// Sheet presenting channel creation and joining options
struct ChannelOptionsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState

    let onChannelCreated: ((ChannelDTO) -> Void)?

    @State private var selectedOption: ChannelOption?
    @State private var availableSlots: [UInt8] = []
    @State private var hasPublicChannel = false
    @State private var isLoading = true

    init(onChannelCreated: ((ChannelDTO) -> Void)? = nil) {
        self.onChannelCreated = onChannelCreated
    }

    enum ChannelOption: Identifiable {
        case createPrivate
        case joinPrivate
        case joinPublic
        case joinHashtag
        case scanQR

        var id: Self { self }
    }

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView(L10n.Chats.Chats.ChannelOptions.loading)
                } else {
                    optionsList
                }
            }
            .navigationTitle(L10n.Chats.Chats.ChannelOptions.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Chats.Chats.Common.cancel) {
                        dismiss()
                    }
                }
            }
            .task {
                await loadChannelState()
            }
            .navigationDestination(item: $selectedOption) { option in
                switch option {
                case .createPrivate:
                    CreatePrivateChannelView(availableSlots: availableSlots) { channel in
                        if let channel { onChannelCreated?(channel) }
                        dismiss()
                    }
                case .joinPrivate:
                    JoinPrivateChannelView(availableSlots: availableSlots) { channel in
                        if let channel { onChannelCreated?(channel) }
                        dismiss()
                    }
                case .joinPublic:
                    JoinPublicChannelView { channel in
                        if let channel { onChannelCreated?(channel) }
                        dismiss()
                    }
                case .joinHashtag:
                    JoinHashtagChannelView(availableSlots: availableSlots) { channel in
                        if let channel { onChannelCreated?(channel) }
                        dismiss()
                    }
                case .scanQR:
                    ScanChannelQRView(availableSlots: availableSlots) { channel in
                        if let channel { onChannelCreated?(channel) }
                        dismiss()
                    }
                }
            }
        }
    }

    private var optionsList: some View {
        List {
            Section {
                // Create Private Channel
                Button {
                    selectedOption = .createPrivate
                } label: {
                    ChannelOptionRow(
                        title: L10n.Chats.Chats.ChannelOptions.CreatePrivate.title,
                        description: L10n.Chats.Chats.ChannelOptions.CreatePrivate.description,
                        icon: "lock.fill",
                        iconColor: .blue
                    )
                }
                .buttonStyle(.plain)
                .disabled(availableSlots.isEmpty)

                // Join Private Channel
                Button {
                    selectedOption = .joinPrivate
                } label: {
                    ChannelOptionRow(
                        title: L10n.Chats.Chats.ChannelOptions.JoinPrivate.title,
                        description: L10n.Chats.Chats.ChannelOptions.JoinPrivate.description,
                        icon: "key.fill",
                        iconColor: .orange
                    )
                }
                .buttonStyle(.plain)
                .disabled(availableSlots.isEmpty)

                // Scan QR Code
                Button {
                    selectedOption = .scanQR
                } label: {
                    ChannelOptionRow(
                        title: L10n.Chats.Chats.ChannelOptions.ScanQR.title,
                        description: L10n.Chats.Chats.ChannelOptions.ScanQR.description,
                        icon: "qrcode.viewfinder",
                        iconColor: .purple
                    )
                }
                .buttonStyle(.plain)
                .disabled(availableSlots.isEmpty)
            } header: {
                Text(L10n.Chats.Chats.ChannelOptions.Section.`private`)
            }

            Section {
                // Join Public Channel
                Button {
                    selectedOption = .joinPublic
                } label: {
                    ChannelOptionRow(
                        title: L10n.Chats.Chats.ChannelOptions.JoinPublic.title,
                        description: L10n.Chats.Chats.ChannelOptions.JoinPublic.description,
                        icon: "globe",
                        iconColor: .green
                    )
                }
                .buttonStyle(.plain)
                .disabled(hasPublicChannel)

                // Join Hashtag Channel
                Button {
                    selectedOption = .joinHashtag
                } label: {
                    ChannelOptionRow(
                        title: L10n.Chats.Chats.ChannelOptions.JoinHashtag.title,
                        description: L10n.Chats.Chats.ChannelOptions.JoinHashtag.description,
                        icon: "number",
                        iconColor: .cyan
                    )
                }
                .buttonStyle(.plain)
                .disabled(availableSlots.isEmpty)
            } header: {
                Text(L10n.Chats.Chats.ChannelOptions.Section.public)
            } footer: {
                if availableSlots.isEmpty {
                    Text(L10n.Chats.Chats.ChannelOptions.Footer.noSlots)
                } else if hasPublicChannel {
                    Text(L10n.Chats.Chats.ChannelOptions.Footer.hasPublic)
                }
            }
        }
    }

    private func loadChannelState() async {
        guard let radioID = appState.connectedDevice?.radioID else {
            isLoading = false
            return
        }

        do {
            let existingChannels = try await appState.services?.dataStore.fetchChannels(radioID: radioID) ?? []
            let usedSlots = Set(existingChannels.map(\.index))

            // Check if public channel exists
            hasPublicChannel = usedSlots.contains(0)

            // Slots 1 through (maxChannels-1) are available for user channels
            // Slot 0 is reserved for public channel
            let maxChannels = appState.connectedDevice?.maxChannels ?? 0
            if maxChannels > 1 {
                availableSlots = (1..<maxChannels).filter { !usedSlots.contains($0) }
            } else {
                availableSlots = []
            }
        } catch {
            // Handle error silently, show empty state
        }

        isLoading = false
    }
}

/// Reusable row for channel option buttons with proper disabled state styling
struct ChannelOptionRow: View {
    @Environment(\.isEnabled) private var isEnabled

    let title: String
    let description: String
    let icon: String
    let iconColor: Color

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .foregroundStyle(.primary)
                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(isEnabled ? iconColor : .secondary)
        }
    }
}

#Preview {
    ChannelOptionsSheet()
        .environment(\.appState, AppState())
}
