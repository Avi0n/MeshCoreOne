import MC1Services
import SwiftUI
import UIKit

private let rxLogCopyConfirmationDuration: Duration = .seconds(2)
private let minimumHitTarget: CGFloat = 44

struct RxLogEntryDetailView: View {
  let entry: RxLogEntryDTO
  let viewModel: RxLogViewModel
  let labels: RxLogHopLabels

  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      List {
        if entry.rssi != nil || entry.snr != nil {
          signalSection
        }
        packetSection
        pathSection
        if showsMessage {
          messageSection
        }
        if !arrivals.isEmpty {
          arrivalsSection
        }
        Section {
          RawPayloadSection(payload: entry.rawPayload)
        }
      }
      .navigationTitle(L10n.Tools.Tools.RxLog.packetDetails)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) {
            dismiss()
          }
        }
      }
    }
  }

  private var arrivals: [RxLogEntryDTO] {
    viewModel.arrivals(of: entry)
  }

  private var signalSection: some View {
    Section(L10n.Tools.Tools.RxLog.signalSection) {
      if let rssi = entry.rssi {
        DetailRow(label: L10n.Tools.Tools.RxLog.rssiLabel, value: "\(rssi) dBm")
      }
      if let snr = entry.snrDisplayString {
        DetailRow(label: L10n.Tools.Tools.RxLog.snrLabel, value: snr)
      }
    }
  }

  private var packetSection: some View {
    Section(L10n.Tools.Tools.RxLog.packetSection) {
      DetailRow(label: L10n.Tools.Tools.RxLog.typeLabel, value: entry.payloadType.displayName)
      DetailRow(label: L10n.Tools.Tools.RxLog.versionLabel, value: "\(entry.payloadVersion)")
      DetailRow(label: L10n.Tools.Tools.RxLog.routeLabel, value: entry.routeTypeSimple)
      DetailRow(
        label: L10n.Tools.Tools.RxLog.sizeLabel,
        value: "\(entry.rawPayload.count) \(L10n.Tools.Tools.RxLog.bytes)"
      )
      DetailRow(
        label: L10n.Tools.Tools.RxLog.receivedLabel,
        value: entry.receivedAt.formatted(date: .abbreviated, time: .standard)
      )
      HashRow(hash: entry.packetHash)
    }
  }

  private var pathSection: some View {
    Section(L10n.Tools.Tools.RxLog.pathSection) {
      if entry.payloadType == .trace {
        let route = labels.traceRouteIDs(for: entry)
        if route.isEmpty {
          DetailRow(
            label: L10n.Tools.Tools.RxLog.pathLabel,
            value: labels.hopCountLabel(for: entry),
            wrapping: true
          )
        } else {
          DetailRow(
            label: L10n.Tools.Tools.RxLog.traceRouteLabel,
            value: route.joined(separator: " → "),
            wrapping: true
          )
        }
      } else if entry.pathHopHashes.isEmpty {
        DetailRow(label: L10n.Tools.Tools.RxLog.pathLabel, value: L10n.Tools.Tools.RxLog.direct)
      } else {
        ForEach(Array(entry.pathHopHashes.enumerated()), id: \.offset) { _, hash in
          DetailRow(label: L10n.Tools.Tools.RxLog.pathLabel, value: hopValue(hash), wrapping: true)
        }
      }
    }
  }

  private var messageSection: some View {
    Section(L10n.Tools.Tools.RxLog.messageSection) {
      if entry.decryptStatus == .success {
        if entry.channelIndex != nil, let channelHashByte = entry.packetPayload.first {
          DetailRow(
            label: L10n.Tools.Tools.RxLog.channelHashLabel,
            value: String(format: "%02x", channelHashByte)
          )
        }
        if let channelName = entry.channelName {
          DetailRow(label: L10n.Tools.Tools.RxLog.channelNameLabel, value: channelName)
        }
        if let region = regionValue {
          DetailRow(label: L10n.Tools.Tools.RxLog.regionLabel, value: region, wrapping: true)
        }
      }
      if isDirectTextMessage, let sender = entry.senderPrefix, let recipient = entry.recipientPrefix {
        DetailRow(label: L10n.Tools.Tools.RxLog.fromLabel, value: labels.resolvedLabel(for: sender))
        DetailRow(label: L10n.Tools.Tools.RxLog.toLabel, value: labels.resolvedLabel(for: recipient))
      }
      if let text = entry.decodedText {
        DetailRow(label: L10n.Tools.Tools.RxLog.textLabel, value: text, wrapping: true)
      }
    }
  }

  private var arrivalsSection: some View {
    Section(L10n.Tools.Tools.RxLog.arrivalsSection) {
      ForEach(arrivals) { arrival in
        VStack(alignment: .leading, spacing: 2) {
          Text(arrival.receivedAt.formatted(date: .abbreviated, time: .standard))
            .font(.caption.monospaced())
          if let snr = arrival.snrDisplayString {
            Text(snr)
              .font(.caption.monospaced())
              .foregroundStyle(.secondary)
          }
          Text(labels.pathSummary(for: arrival))
            .font(.caption)
            .foregroundStyle(.secondary)
        }
      }
    }
  }

  private var showsMessage: Bool {
    if entry.decodedText != nil { return true }
    if isDirectTextMessage, entry.senderPrefix != nil, entry.recipientPrefix != nil { return true }
    guard entry.decryptStatus == .success else { return false }
    return entry.channelName != nil || entry.channelIndex != nil || regionValue != nil
  }

  private var isDirectTextMessage: Bool {
    (entry.routeType == .direct || entry.routeType == .tcDirect) && entry.payloadType == .textMessage
  }

  private var regionValue: String? {
    guard entry.decryptStatus == .success,
          let transportCode = entry.transportCode,
          !transportCode.isEmpty else { return nil }
    if TransportCodeRegionResolver.isUnscoped(transportCode) {
      return L10n.Tools.Tools.RxLog.regionUnscoped
    }
    switch RegionScopeSemantics.coalesce(scope: entry.regionScope, matches: entry.regionScopeMatches) {
    case .none:
      return L10n.Tools.Tools.RxLog.regionUnresolved
    case let .unique(name):
      return name
    case let .ambiguous(names):
      return ListFormatter.localizedString(byJoining: names)
    }
  }

  private func hopValue(_ hash: Data) -> String {
    let hex = labels.hexLabel(for: hash)
    let name = labels.resolvedLabel(for: hash)
    return name == hex ? hex : "\(hex) · \(name)"
  }
}

private struct DetailRow: View {
  let label: String
  let value: String
  var truncate: Bool = false
  var wrapping: Bool = false

  var body: some View {
    HStack(alignment: .top) {
      Text(label)
        .font(.caption)
        .foregroundStyle(.secondary)
      Text(value)
        .font(.caption.monospaced())
        .lineLimit(wrapping ? nil : 1)
        .truncationMode(truncate ? .middle : .tail)
        .textSelection(.enabled)
    }
  }
}

private struct HashRow: View {
  let hash: String

  @State private var copied = false

  var body: some View {
    HStack(alignment: .top) {
      DetailRow(label: L10n.Tools.Tools.RxLog.hashLabel, value: hash, truncate: true)
      Spacer(minLength: 8)
      Button(action: copy) {
        Label(L10n.Tools.Tools.RxLog.copy, systemImage: copied ? "checkmark" : "doc.on.doc")
          .font(.caption)
          .foregroundStyle(copied ? Color.green : Color.secondary)
          .labelStyle(.iconOnly)
          .frame(width: minimumHitTarget, height: minimumHitTarget)
          .contentShape(.rect)
      }
      .buttonStyle(.plain)
      .sensoryFeedback(.success, trigger: copied) { _, newValue in newValue }
    }
  }

  private func copy() {
    UIPasteboard.general.string = hash
    copied = true
    Task {
      try? await Task.sleep(for: rxLogCopyConfirmationDuration)
      copied = false
    }
  }
}

private struct RawPayloadSection: View {
  let payload: Data

  @State private var copied = false

  private static let hexBytesPerLine = 16

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack {
        Text(L10n.Tools.Tools.RxLog.rawPayload)
          .font(.caption.bold())
          .foregroundStyle(.secondary)
        Spacer()
        Button(action: copy) {
          Label(L10n.Tools.Tools.RxLog.copy, systemImage: copied ? "checkmark" : "doc.on.doc")
            .font(.caption)
            .foregroundStyle(copied ? Color.green : Color.secondary)
            .labelStyle(.iconOnly)
            .frame(width: minimumHitTarget, height: minimumHitTarget)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.success, trigger: copied) { _, newValue in newValue }
      }

      Text(hexDump)
        .font(.caption2.monospaced())
        .textSelection(.enabled)
    }
  }

  private var hexDump: String {
    guard !payload.isEmpty else { return "" }
    return stride(from: 0, to: payload.count, by: Self.hexBytesPerLine).map { offset in
      let end = min(offset + Self.hexBytesPerLine, payload.count)
      let bytes = payload[offset..<end].map { String(format: "%02X", $0) }.joined(separator: " ")
      return String(format: "%04X", offset) + "  " + bytes
    }
    .joined(separator: "\n")
  }

  private var continuousHex: String {
    payload.map { String(format: "%02X", $0) }.joined(separator: " ")
  }

  private func copy() {
    UIPasteboard.general.string = continuousHex
    copied = true
    Task {
      try? await Task.sleep(for: rxLogCopyConfirmationDuration)
      copied = false
    }
  }
}
