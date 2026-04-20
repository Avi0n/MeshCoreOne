import MC1Services
import SwiftUI

/// Actions available from the message actions sheet
enum MessageAction: Equatable {
    case react(String)
    case reply
    case copy
    case sendAgain
    case sendDM
    case blockSender
    case delete
}

/// Sheet-based message actions UI (ElementX style)
/// Replaces native context menus for unified experience across channel and direct messages
struct MessageActionsSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let message: MessageDTO
    let senderName: String
    let recentEmojis: [String]
    let onAction: (MessageAction) -> Void

    private var availability: MessageActionAvailability {
        MessageActionAvailability(message: message)
    }

    private func performAction(_ action: MessageAction) {
        onAction(action)
        dismiss()
    }

    private var emojiSection: some View {
        ActionsEmojiSection(
            recentEmojis: recentEmojis,
            showEmojiPicker: $showEmojiPicker,
            onSelectEmoji: { emoji in
                performAction(.react(emoji))
            }
        )
    }

    @State private var longPressHapticTrigger = 0
    @State private var showEmojiPicker = false
    @State private var isDetailExpanded = false
    @State private var repeats: [MessageRepeatDTO]?
    @State private var contacts: [ContactDTO] = []
    @State private var discoveredNodes: [DiscoveredNodeDTO] = []
    @State private var pathViewModel = MessagePathViewModel()

    var body: some View {
        VStack(spacing: 0) {
            ActionsPreviewHeader(
                message: message,
                senderName: senderName
            )

            Divider()

            if !dynamicTypeSize.isAccessibilitySize {
                emojiSection
                Divider()
            }

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        if dynamicTypeSize.isAccessibilitySize {
                            emojiSection
                            Divider()
                        }
                        ActionsButtonsSection(
                            availability: availability,
                            onSelectAction: performAction
                        )
                        ActionsDetailsSection(
                            message: message,
                            availability: availability,
                            isDetailExpanded: $isDetailExpanded,
                            repeats: repeats,
                            contacts: contacts,
                            discoveredNodes: discoveredNodes,
                            pathViewModel: pathViewModel
                        )
                        ActionsBlockSection(
                            availability: availability,
                            onSelectAction: performAction
                        )
                        ActionsDeleteSection(
                            availability: availability,
                            onSelectAction: performAction
                        )
                    }
                }
                .onChange(of: isDetailExpanded) { _, expanded in
                    if expanded {
                        withAnimation {
                            proxy.scrollTo("expandedContent", anchor: .top)
                        }
                    }
                }
            }
        }
        .presentationDetents(
            (horizontalSizeClass == .regular || dynamicTypeSize.isAccessibilitySize)
                ? [.large] : [.medium, .large]
        )
        .presentationContentInteraction(.scrolls)
        .presentationDragIndicator(.visible)
        .presentationBackground(Color(.systemBackground))
        .onAppear {
            longPressHapticTrigger += 1
        }
        .sensoryFeedback(.impact(flexibility: .solid), trigger: longPressHapticTrigger)
        .task {
            guard let services = appState.services else { return }
            if availability.canShowRepeatDetails {
                do {
                    contacts = try await services.dataStore.fetchContacts(radioID: message.radioID)
                    discoveredNodes = try await services.dataStore.fetchDiscoveredNodes(radioID: message.radioID)
                } catch {
                    contacts = []
                    discoveredNodes = []
                }
                repeats = await services.heardRepeatsService.refreshRepeats(for: message.id)
            } else if availability.canViewPath {
                await pathViewModel.loadContacts(services: services, radioID: message.radioID)
            }
        }
    }
}

// MARK: - Extracted Views

private struct ActionsPreviewHeader: View {
    let message: MessageDTO
    let senderName: String

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var senderNodeID: String? {
        guard !message.isOutgoing,
              let keyPrefix = message.senderKeyPrefix,
              let firstByte = keyPrefix.first else { return nil }
        return String(format: "%02X", firstByte)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ViewThatFits(in: .horizontal) {
                HStack {
                    if let senderNodeID {
                        Text(senderNodeID)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .monospaced()
                    }
                    Text(senderName)
                        .font(.subheadline)
                        .bold()
                    Spacer()
                    ActionsTimestampLabel(message: message)
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        if let senderNodeID {
                            Text(senderNodeID)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .monospaced()
                        }
                        Text(senderName)
                            .font(.subheadline)
                            .bold()
                    }
                    ActionsTimestampLabel(message: message)
                }
            }

            Text(message.text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
        }
        .padding()
    }
}

private struct ActionsTimestampLabel: View {
    let message: MessageDTO

    var body: some View {
        Text(message.date, format: .dateTime.hour().minute())
            .font(.subheadline)
            .foregroundStyle(.secondary)
    }
}

private struct ActionsEmojiSection: View {
    let recentEmojis: [String]
    @Binding var showEmojiPicker: Bool
    let onSelectEmoji: (String) -> Void

    var body: some View {
        EmojiPickerRow(
            emojis: recentEmojis,
            onSelect: onSelectEmoji,
            onOpenKeyboard: { showEmojiPicker = true }
        )
        .padding(.vertical, 4)
        .sheet(isPresented: $showEmojiPicker) {
            EmojiPickerSheet(onSelect: onSelectEmoji)
        }
    }
}

private struct ActionsButtonsSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void
    @AppStorage("replyWithQuote") private var replyWithQuote = false

    var body: some View {
        if availability.canReply {
            ActionButton(
                title: replyWithQuote ? L10n.Chats.Chats.Message.Action.reply : L10n.Chats.Chats.Message.Action.mention,
                icon: "arrowshape.turn.up.left",
                action: { onSelectAction(.reply) }
            )
        }

        if availability.canSendDM {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.sendDM,
                icon: "bubble.left.and.bubble.right",
                action: { onSelectAction(.sendDM) }
            )
        }

        ActionButton(
            title: L10n.Chats.Chats.Message.Action.copy,
            icon: "doc.on.doc",
            action: { onSelectAction(.copy) }
        )

        if availability.canSendAgain {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.sendAgain,
                icon: "arrow.uturn.forward",
                action: { onSelectAction(.sendAgain) }
            )
        }
    }
}

private struct ActionsBlockSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    var body: some View {
        if availability.canBlockSender {
            Divider()
                .padding(.vertical, 8)
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.blockSender,
                icon: "hand.raised",
                isDestructive: true,
                action: { onSelectAction(.blockSender) }
            )
        }
    }
}

private struct ActionsDeleteSection: View {
    let availability: MessageActionAvailability
    let onSelectAction: (MessageAction) -> Void

    var body: some View {
        if availability.canDelete {
            Divider()
                .padding(.vertical, 8)
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.delete,
                icon: "trash",
                isDestructive: true,
                action: { onSelectAction(.delete) }
            )
        }
    }
}

private struct ActionsDetailsSection: View {
    let message: MessageDTO
    let availability: MessageActionAvailability
    @Binding var isDetailExpanded: Bool
    let repeats: [MessageRepeatDTO]?
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let pathViewModel: MessagePathViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if availability.canShowRepeatDetails || availability.canViewPath {
                ActionsExpandableDetailRow(
                    message: message,
                    availability: availability,
                    isDetailExpanded: $isDetailExpanded,
                    repeats: repeats,
                    contacts: contacts,
                    discoveredNodes: discoveredNodes,
                    pathViewModel: pathViewModel
                )
            }

            Text(L10n.Chats.Chats.Message.Action.details)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
                .padding(.top, 12)
                .padding(.bottom, 4)

            if message.isOutgoing {
                ActionsOutgoingDetailsRows(message: message)
            } else {
                ActionsIncomingDetailsRows(message: message)
            }
        }
    }
}

private struct ActionsExpandableDetailRow: View {
    @Environment(\.appState) private var appState

    let message: MessageDTO
    let availability: MessageActionAvailability
    @Binding var isDetailExpanded: Bool
    let repeats: [MessageRepeatDTO]?
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let pathViewModel: MessagePathViewModel

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation {
                    isDetailExpanded.toggle()
                }
            } label: {
                HStack {
                    Label(
                        availability.canShowRepeatDetails
                            ? L10n.Chats.Chats.Message.Action.repeatDetails
                            : L10n.Chats.Chats.Message.Action.viewPath,
                        systemImage: availability.canShowRepeatDetails
                            ? "arrow.triangle.branch"
                            : "point.topleft.down.to.point.bottomright.curvepath"
                    )
                    Spacer()
                    Image(systemName: "chevron.right")
                        .rotationEffect(.degrees(isDetailExpanded ? 90 : 0))
                        .foregroundStyle(.secondary)
                        .font(.caption)
                }
                .padding()
                .contentShape(.rect)
            }
            .foregroundStyle(.primary)
            .accessibilityValue(isDetailExpanded ? "expanded" : "collapsed")

            if isDetailExpanded {
                Divider()
                    .padding(.horizontal)
                ActionsExpandedContent(
                    message: message,
                    availability: availability,
                    repeats: repeats,
                    contacts: contacts,
                    discoveredNodes: discoveredNodes,
                    pathViewModel: pathViewModel
                )
                .padding(.horizontal)
                .padding(.bottom)
                .id("expandedContent")
            }
        }
    }
}

private struct ActionsExpandedContent: View {
    @Environment(\.appState) private var appState

    let message: MessageDTO
    let availability: MessageActionAvailability
    let repeats: [MessageRepeatDTO]?
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let pathViewModel: MessagePathViewModel

    var body: some View {
        if availability.canShowRepeatDetails {
            RepeatDetailsContent(
                repeats: repeats,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: appState.bestAvailableLocation
            )
        } else if availability.canViewPath {
            MessagePathContent(
                message: message,
                viewModel: pathViewModel,
                receiverName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
                userLocation: appState.bestAvailableLocation
            )
        }
    }
}

private struct ActionsOutgoingDetailsRows: View {
    let message: MessageDTO

    var body: some View {
        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.sent(
            message.senderDate.formatted(date: .abbreviated, time: .shortened)))

        if let rtt = message.roundTripTime {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.roundTrip(Int(rtt)))
        }

        if message.heardRepeats > 0 {
            let word = message.heardRepeats == 1
                ? L10n.Chats.Chats.Message.Repeat.singular
                : L10n.Chats.Chats.Message.Repeat.plural
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.heardRepeats(message.heardRepeats, word))
        }
    }
}

private struct ActionsIncomingDetailsRows: View {
    let message: MessageDTO

    var body: some View {
        ActionInfoRow(
            text: L10n.Chats.Chats.Message.Info.hops(hopCountFormatted(message)),
            icon: "arrowshape.bounce.right"
        )

        let sentText = L10n.Chats.Chats.Message.Info.sent(
            message.senderDate.formatted(date: .abbreviated, time: .shortened))
        let adjusted = message.timestampCorrected ? " " + L10n.Chats.Chats.Message.Info.adjusted : ""
        ActionInfoRow(text: sentText + adjusted)

        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.received(
            message.createdAt.formatted(date: .abbreviated, time: .shortened)))

        if let snr = message.snr {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.snr(snrFormatted(snr)))
        }
    }

    private func snrFormatted(_ snr: Double) -> String {
        let quality = SNRQuality(snr: snr).localizedLabel
        return "\(snr.formatted(.number.precision(.fractionLength(1)))) dB (\(quality))"
    }

    private func hopCountFormatted(_ message: MessageDTO) -> String {
        if message.isDirectRouted {
            return L10n.Chats.Chats.Message.Hops.direct
        }
        return "\(message.hopCount)"
    }
}

// MARK: - Shared Helper Views

private struct ActionButton: View {
    let title: String
    let icon: String
    var isDestructive: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Label(title, systemImage: icon)
                Spacer()
            }
            .padding()
            .contentShape(.rect)
        }
        .foregroundStyle(isDestructive ? .red : .primary)
    }
}

private struct ActionInfoRow: View {
    let text: String
    var icon: String?

    var body: some View {
        HStack {
            if let icon {
                Image(systemName: icon)
                    .foregroundStyle(.secondary)
            }
            Text(text)
            Spacer()
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal)
        .padding(.vertical, 6)
    }
}

#Preview("Outgoing Message") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "Hello world!",
        directionRawValue: MessageDirection.outgoing.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue
    )
    message.roundTripTime = 234
    message.heardRepeats = 2
    return MessageActionsSheet(
        message: MessageDTO(from: message),
        senderName: "My Device",
        recentEmojis: RecentEmojisStore.defaultEmojis,

        onAction: { print("Action: \($0)") }
    )
}

#Preview("Incoming Message") {
    let message = Message(
        radioID: UUID(),
        contactID: UUID(),
        text: "Hey, can you meet me at the coffee shop downtown later today? I have something important to discuss.",
        directionRawValue: MessageDirection.incoming.rawValue,
        statusRawValue: MessageStatus.delivered.rawValue,
        pathLength: 2
    )
    message.pathNodes = Data([0xA3, 0x7F])
    message.snr = 8.5
    return MessageActionsSheet(
        message: MessageDTO(from: message),
        senderName: "Alice",
        recentEmojis: RecentEmojisStore.defaultEmojis,

        onAction: { print("Action: \($0)") }
    )
}
