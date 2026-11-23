import Foundation
import MC1Services

/// Turns RX Log hop hashes into display labels: "You" for the local radio, a
/// contact name when the prefix is unique, otherwise uppercase hex.
struct RxLogHopLabels {
  let localPublicKeyPrefix: Data?
  let nodeNames: [Data: String]

  func isLocal(_ hash: Data) -> Bool {
    guard let localPublicKeyPrefix else { return false }
    return localPublicKeyPrefix.prefix(hash.count) == hash
  }

  /// Hex ID, or "You" for the local radio.
  func hexLabel(for hash: Data) -> String {
    isLocal(hash) ? L10n.Tools.Tools.RxLog.pathYou : hash.uppercaseHexString()
  }

  func resolvedLabel(for hash: Data) -> String {
    if isLocal(hash) {
      return L10n.Tools.Tools.RxLog.pathYou
    }
    return nodeNames[hash] ?? hash.uppercaseHexString()
  }

  func hopIDs(for entry: RxLogEntryDTO) -> [String] {
    entry.pathHopHashes.map(hexLabel(for:))
  }

  func traceRouteIDs(for entry: RxLogEntryDTO) -> [String] {
    (entry.traceTargetHashes ?? []).map { $0.uppercaseHexString() }
  }

  /// "N hop(s)" for a path, or "Direct" when empty.
  func hopCountLabel(for entry: RxLogEntryDTO) -> String {
    guard !entry.pathNodes.isEmpty else { return L10n.Tools.Tools.RxLog.direct }
    let count = entry.hopCount
    let hopLabel = count == 1 ? L10n.Tools.Tools.RxLog.hopSingular : L10n.Tools.Tools.RxLog.hopPlural
    return "\(count) \(hopLabel)"
  }

  /// One-line path for a row or an arrival: the trace route, hop IDs, or "Direct".
  /// A path longer than `limit` keeps the first and last hops.
  func pathSummary(for entry: RxLogEntryDTO, truncatingAt limit: Int = 6) -> String {
    if entry.payloadType == .trace {
      let route = traceRouteIDs(for: entry)
      if !route.isEmpty {
        return join(route, separator: " → ", truncatingAt: limit)
      }
      return hopCountLabel(for: entry)
    }
    if entry.pathNodes.isEmpty {
      return L10n.Tools.Tools.RxLog.direct
    }
    return join(hopIDs(for: entry), separator: " → ", truncatingAt: limit)
  }

  private func join(_ parts: [String], separator: String, truncatingAt limit: Int) -> String {
    guard parts.count > limit, limit > 1 else { return parts.joined(separator: separator) }
    let edge = limit / 2
    let first = parts.prefix(edge).joined(separator: separator)
    let last = parts.suffix(edge).joined(separator: separator)
    return "\(first)\(separator)…\(separator)\(last)"
  }
}
