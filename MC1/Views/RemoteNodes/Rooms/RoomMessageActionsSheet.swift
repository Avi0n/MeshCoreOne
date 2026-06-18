import MC1Services
import SwiftUI

/// Long-press info and actions sheet for a room server message. Mirrors the
/// chat `MessageActionsSheet` layout but carries no reactions and only the
/// metadata a server-pushed room message actually has.
struct RoomMessageActionsSheet: View {
    let message: RoomMessageDTO
    let availability: RoomMessageActionAvailability
    let onAction: (RoomMessageAction) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @AppStorage(AppStorageKey.replyWithQuote.rawValue) private var replyWithQuote = AppStorageKey.defaultReplyWithQuote

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            ScrollView {
                VStack(spacing: 0) {
                    buttons
                    details
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
    }

    private func performAction(_ action: RoomMessageAction) {
        onAction(action)
        dismiss()
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(message.authorDisplayName)
                    .font(.subheadline)
                    .bold()
                Spacer()
                Text(message.date, format: .dateTime.hour().minute())
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Text(message.text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var buttons: some View {
        if availability.canReply {
            ActionButton(
                title: replyWithQuote
                    ? L10n.Chats.Chats.Message.Action.reply
                    : L10n.Chats.Chats.Message.Action.mention,
                icon: "arrowshape.turn.up.left",
                action: { performAction(.reply) }
            )
        }

        if availability.canSendDM {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.sendDM,
                icon: "bubble.left.and.bubble.right",
                action: { performAction(.sendDM) }
            )
        }

        ActionButton(
            title: L10n.Chats.Chats.Message.Action.copy,
            icon: "doc.on.doc",
            action: { performAction(.copy) }
        )

        if availability.canSendAgain {
            ActionButton(
                title: L10n.Chats.Chats.Message.Action.sendAgain,
                icon: "arrow.uturn.forward",
                action: { performAction(.sendAgain) }
            )
        }
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L10n.Chats.Chats.Message.Action.details)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
                .padding(.top, 12)
                .padding(.bottom, 4)

            if message.isFromSelf {
                outgoingRows
            } else {
                incomingRows
            }
        }
    }

    @ViewBuilder
    private var incomingRows: some View {
        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.sent(
            message.date.formatted(date: .abbreviated, time: .standard)))
        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.received(
            message.createdAt.formatted(date: .abbreviated, time: .standard)))
        ActionInfoRow(text: message.localizedStatusText)
    }

    @ViewBuilder
    private var outgoingRows: some View {
        ActionInfoRow(text: L10n.Chats.Chats.Message.Info.sent(
            message.date.formatted(date: .abbreviated, time: .standard)))
        ActionInfoRow(text: message.localizedStatusText)

        if let roundTripTime = message.roundTripTime {
            ActionInfoRow(text: L10n.Chats.Chats.Message.Info.roundTrip(Int(roundTripTime)))
        }
    }
}

#Preview("Incoming") {
    let message = RoomMessageDTO(
        sessionID: UUID(),
        authorKeyPrefix: Data(repeating: 0x55, count: 4),
        authorName: "Alice",
        text: "Anyone near the north trailhead? Roads are washed out past the bridge.",
        timestamp: UInt32(Date().timeIntervalSince1970)
    )
    let session = RemoteNodeSessionDTO(
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Test Room",
        role: .roomServer,
        isConnected: true,
        permissionLevel: .readWrite
    )
    return Color.clear.sheet(isPresented: .constant(true)) {
        RoomMessageActionsSheet(
            message: message,
            availability: RoomMessageActionAvailability(message: message, session: session),
            onAction: { _ in }
        )
    }
}

#Preview("Outgoing") {
    let message = RoomMessageDTO(
        sessionID: UUID(),
        authorKeyPrefix: Data(repeating: 0x42, count: 4),
        authorName: "Me",
        text: "Copy that, heading your way.",
        timestamp: UInt32(Date().timeIntervalSince1970),
        isFromSelf: true,
        status: .delivered,
        roundTripTime: 842
    )
    let session = RemoteNodeSessionDTO(
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Test Room",
        role: .roomServer,
        isConnected: true,
        permissionLevel: .readWrite
    )
    return Color.clear.sheet(isPresented: .constant(true)) {
        RoomMessageActionsSheet(
            message: message,
            availability: RoomMessageActionAvailability(message: message, session: session),
            onAction: { _ in }
        )
    }
}
