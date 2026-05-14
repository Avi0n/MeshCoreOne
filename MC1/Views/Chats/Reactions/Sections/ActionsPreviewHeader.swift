import MC1Services
import SwiftUI

struct ActionsPreviewHeader: View {
    let message: MessageDTO
    let senderName: String
    let senderMatchKind: NodeNameMatchKind

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
                    senderNodeIDLabel
                    senderLabel
                    Spacer()
                    ActionsTimestampLabel(message: message)
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        senderNodeIDLabel
                        senderLabel
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

    @ViewBuilder
    private var senderNodeIDLabel: some View {
        if let senderNodeID {
            Text(senderNodeID)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .monospaced()
                .accessibilityHidden(true)
        }
    }

    private var senderLabel: some View {
        HStack(spacing: 4) {
            Text(senderName)
                .font(.subheadline)
                .bold()

            if senderMatchKind == .fallback {
                FallbackMatchIndicatorView()
            }
        }
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
