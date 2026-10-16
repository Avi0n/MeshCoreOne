import MC1Services
import SwiftUI

struct ActionsPreviewHeader: View {
  let message: MessageDTO
  let senderResolution: NodeNameResolution

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
          SenderNameLabel(resolution: senderResolution, font: .subheadline)
          Spacer()
          ActionsTimestampLabel(message: message)
        }

        VStack(alignment: .leading, spacing: 2) {
          HStack(spacing: 6) {
            senderNodeIDLabel
            SenderNameLabel(resolution: senderResolution, font: .subheadline)
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
    // Only collapse to a single rotor stop when there is no interactive
    // descendant. The fallback-match and unverified-nickname indicators
    // (inside SenderNameLabel) are Buttons with their own label/hint/popover;
    // .combine would destroy that affordance. .contain preserves the
    // indicator and adds a parent container that VoiceOver users can land on.
    .accessibilityElement(
      children: (senderResolution.unverifiedNickname != nil || senderResolution.isFallback)
        ? .contain : .combine
    )
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
}

private struct ActionsTimestampLabel: View {
  let message: MessageDTO

  var body: some View {
    Text(message.date, format: .dateTime.hour().minute())
      .font(.subheadline)
      .foregroundStyle(.secondary)
  }
}
