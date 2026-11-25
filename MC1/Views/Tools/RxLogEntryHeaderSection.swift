import MC1Services
import SwiftUI

/// Packet type, decoded message, and when and how the packet arrived.
struct RxLogEntryHeaderSection: View {
  private static let rowSpacing: CGFloat = 6
  private static let separator = " · "

  let entry: RxLogEntryDTO
  let framing: RxLogPathFraming
  let labels: RxLogHopLabels
  /// Reports the row's content width, which the path preview row shares.
  let onWidthChange: (CGFloat) -> Void

  var body: some View {
    Section {
      VStack(alignment: .leading, spacing: Self.rowSpacing) {
        Label {
          Text(title)
        } icon: {
          Image(systemName: entry.payloadType.systemImage)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.secondary)

        if let messageBody = entry.messageBody {
          Text(messageBody)
            .font(.body)
            .textSelection(.enabled)
        }

        Text(metadata)
          .font(.caption)
          .foregroundStyle(.secondary)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
      .onGeometryChange(for: CGFloat.self) { proxy in
        proxy.size.width
      } action: { width in
        onWidthChange(width)
      }
    }
  }

  private var title: String {
    var parts = [entry.payloadType.displayName]
    if let channelName = entry.channelName {
      parts.append(channelName)
    }
    // Flood DMs name their sender in the path section; repeating it here would
    // resolve the same 1-byte hash a second way.
    if isDirectTextMessage, let sender = entry.senderPrefix, let recipient = entry.recipientPrefix {
      parts.append("\(labels.resolvedLabel(for: sender)) → \(labels.resolvedLabel(for: recipient))")
    }
    return parts.joined(separator: Self.separator)
  }

  private var metadata: String {
    var parts = [
      entry.receivedAt.formatted(date: .abbreviated, time: .standard),
      entry.isFlood ? L10n.Tools.Tools.RxLog.flood : L10n.Tools.Tools.RxLog.direct,
    ]
    if framing == .traversed, entry.hopCount > 0 {
      parts.append(
        entry.hopCount == 1
          ? L10n.Chats.Chats.Repeats.Hop.singular
          : L10n.Chats.Chats.Repeats.Hop.plural(entry.hopCount)
      )
    }
    return parts.joined(separator: Self.separator)
  }

  private var isDirectTextMessage: Bool {
    !entry.isFlood && entry.payloadType == .textMessage
  }
}
