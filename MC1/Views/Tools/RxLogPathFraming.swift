import Foundation
import MC1Services

/// What an RX Log entry's path bytes mean, which decides how the detail sheet shows them.
enum RxLogPathFraming: Equatable {
  /// Hops already traversed, sender first: flood packets and adverts.
  case traversed
  /// Hops still ahead: firmware strips each forwarder from a direct packet's path.
  case remaining
  /// Path bytes are per-hop SNR values; the route is the trace's target hashes.
  case trace

  private static let clipboardSeparator = ","

  init(entry: RxLogEntryDTO) {
    if entry.payloadType == .trace {
      self = .trace
    } else if entry.isFlood || entry.payloadType == .advert {
      // Adverts go out flood or zero-hop direct; both are a real sender-to-you path.
      self = .traversed
    } else {
      self = .remaining
    }
  }

  /// Path text for the toolbar copy action. Nil for traversed paths, which the hop
  /// list copies itself, and when there is nothing to copy.
  func clipboardPath(for entry: RxLogEntryDTO) -> String? {
    let hops: [Data] = switch self {
    case .traversed: []
    case .remaining: entry.pathHopHashes
    case .trace: entry.traceTargetHashes ?? []
    }
    guard !hops.isEmpty else { return nil }
    return hops.map { $0.uppercaseHexString() }.joined(separator: Self.clipboardSeparator)
  }
}
