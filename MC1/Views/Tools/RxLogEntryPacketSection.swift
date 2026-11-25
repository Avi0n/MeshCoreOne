import MC1Services
import SwiftUI

/// Wire-level facts about one packet, with the raw bytes collapsed by default.
struct RxLogEntryPacketSection: View {
  private static let hexBytesPerLine = 16

  let entry: RxLogEntryDTO

  var body: some View {
    Section(L10n.Tools.Tools.RxLog.packetSection) {
      LabeledContent(
        L10n.Tools.Tools.RxLog.routeLabel,
        value: entry.isFlood ? L10n.Tools.Tools.RxLog.flood : L10n.Tools.Tools.RxLog.direct
      )
      LabeledContent(
        L10n.Tools.Tools.RxLog.sizeLabel,
        value: "\(entry.rawPayload.count) \(L10n.Tools.Tools.RxLog.bytes)"
      )
      LabeledContent(L10n.Tools.Tools.RxLog.hashLabel) {
        hexText(entry.packetHash)
      }
      LabeledContent(L10n.Tools.Tools.RxLog.versionLabel, value: "\(entry.payloadVersion)")
      if let region = regionValue {
        LabeledContent(L10n.Tools.Tools.RxLog.regionLabel, value: region)
      }
      if let channelHash {
        LabeledContent(L10n.Tools.Tools.RxLog.channelHashLabel) {
          hexText(channelHash)
        }
      }
      DisclosureGroup(L10n.Tools.Tools.RxLog.rawPayload) {
        Text(hexDump)
          .font(.caption.monospaced())
          .textSelection(.enabled)
      }
    }
  }

  private func hexText(_ value: String) -> some View {
    Text(value)
      .monospaced()
      .lineLimit(1)
      .truncationMode(.middle)
      .textSelection(.enabled)
  }

  private var channelHash: String? {
    guard entry.decryptStatus == .success, entry.channelIndex != nil,
          let channelHashByte = entry.packetPayload.first else { return nil }
    return String(format: "%02x", channelHashByte)
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

  private var hexDump: String {
    guard !entry.rawPayload.isEmpty else { return "" }
    return stride(from: 0, to: entry.rawPayload.count, by: Self.hexBytesPerLine).map { offset in
      let end = min(offset + Self.hexBytesPerLine, entry.rawPayload.count)
      let bytes = entry.rawPayload[offset..<end].map { String(format: "%02X", $0) }.joined(separator: " ")
      return String(format: "%04X", offset) + "  " + bytes
    }
    .joined(separator: "\n")
  }
}
