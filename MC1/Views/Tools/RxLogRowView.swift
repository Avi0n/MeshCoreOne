import MC1Services
import SwiftUI
import UIKit

struct RxLogRowView: View {
  let entry: RxLogEntryDTO
  let groupCount: Int
  let labels: RxLogHopLabels
  let onSelect: () -> Void
  let onHideType: () -> Void

  var body: some View {
    Button(action: onSelect) {
      VStack(alignment: .leading, spacing: 4) {
        headerLine
        pathLine
        previewLine
      }
    }
    .buttonStyle(.plain)
    .contextMenu {
      Button(L10n.Tools.Tools.RxLog.copyRawPayload) {
        copy(continuousHex)
      }
      Button(L10n.Tools.Tools.RxLog.copyHash) {
        copy(entry.packetHash)
      }
      Button(L10n.Tools.Tools.RxLog.hideType(entry.payloadType.displayName)) {
        onHideType()
      }
    }
  }

  private var headerLine: some View {
    HStack(spacing: 6) {
      Image(systemName: entry.payloadType.systemImage)
        .foregroundStyle(.secondary)
        .accessibilityHidden(true)

      Text(entry.payloadType.displayName)
        .font(.caption.bold())

      Text(entry.routeTypeSimple)
        .font(.caption.bold())
        .foregroundStyle(entry.isFlood ? Color.green : Color.blue)

      Spacer(minLength: 4)

      if entry.snr != nil {
        Image(systemName: "cellularbars", variableValue: entry.snrLevel)
          .foregroundStyle(entry.snrQuality.color)
          .accessibilityLabel(L10n.Tools.Tools.RxLog.signalStrength(entry.snrQuality.localizedLabel))
      }

      receivedTime
        .font(.caption.monospaced())
        .foregroundStyle(.secondary)
    }
  }

  private var receivedTime: Text {
    if Calendar.current.isDateInToday(entry.receivedAt) {
      Text(entry.receivedAt, format: .dateTime.hour().minute().second())
    } else {
      Text(entry.receivedAt, format: .dateTime.month(.twoDigits).day(.twoDigits).hour().minute().second())
    }
  }

  private var pathLine: some View {
    HStack(spacing: 4) {
      Text(labels.pathSummary(for: entry))
        .font(.caption)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .truncationMode(.middle)

      if isDirectTextMessage, let sender = entry.senderPrefix, let recipient = entry.recipientPrefix {
        Text("·")
          .foregroundStyle(.tertiary)
        Text("\(labels.resolvedLabel(for: sender)) → \(labels.resolvedLabel(for: recipient))")
          .font(.caption)
          .foregroundStyle(.secondary)
      }
    }
  }

  private var previewLine: some View {
    HStack {
      if let text = entry.decodedText {
        Text("\"\(text)\"")
          .font(.caption)
          .foregroundStyle(.primary)
          .lineLimit(1)
          .truncationMode(.tail)
      } else {
        let versionSuffix = entry.payloadVersion > 0 ? " v\(entry.payloadVersion)" : ""
        Text("\(entry.payloadType.displayName)\(versionSuffix) · \(entry.rawPayload.count) \(L10n.Tools.Tools.RxLog.bytes)")
          .font(.caption)
          .foregroundStyle(.secondary)
      }

      Spacer()

      if let snrString = entry.snrDisplayString {
        Text(snrString)
          .font(.caption.monospaced())
          .foregroundStyle(.secondary)
      }

      if groupCount > 1 {
        Text("×\(groupCount)")
          .font(.caption.bold())
          .foregroundStyle(.orange)
          .accessibilityLabel(L10n.Tools.Tools.RxLog.receivedTimes(groupCount))
      }
    }
  }

  private var isDirectTextMessage: Bool {
    (entry.routeType == .direct || entry.routeType == .tcDirect) && entry.payloadType == .textMessage
  }

  private var continuousHex: String {
    entry.rawPayload.map { String(format: "%02X", $0) }.joined(separator: " ")
  }

  private func copy(_ text: String) {
    UIPasteboard.general.string = text
  }
}
