import Foundation
import MC1Services

/// One observation of a packet reaching this radio, with the path it took.
struct MessagePathArrival: Identifiable, Equatable, Hashable, Sendable {
  let id: UUID
  let pathNodes: Data
  let pathLength: UInt8
  let snr: Double?
  let rssi: Int?
  let receivedAt: Date

  /// Hop count from a path-length byte `decodePathLen` accepts. A rejected byte, including 0xFF, has none.
  var hopCount: Int? {
    decodePathLen(pathLength)?.hopCount
  }

  /// Empty path bytes with hop count 0 is a 0-hop arrival, not missing path data.
  var isZeroHop: Bool {
    pathNodes.isEmpty && hopCount == 0
  }

  /// True when there is no hop list to show and this is not a 0-hop arrival.
  var isPathUnavailable: Bool {
    pathNodes.isEmpty && !isZeroHop
  }

  var hashSize: Int {
    decodePathLen(pathLength)?.hashSize ?? 1
  }

  var pathHops: [(data: Data, hex: String)] {
    pathNodes.pathHops(hashSize: hashSize)
  }

  var pathString: String {
    pathHops.map(\.hex).joined(separator: " → ")
  }

  var pathStringForClipboard: String {
    pathHops.map(\.hex).joined(separator: ",")
  }
}

enum MessagePathArrivals {
  static func assemble(message: MessageDTO, repeats: [MessageRepeatDTO]) -> [MessagePathArrival] {
    if message.isOutgoing {
      return repeats
        .sorted { $0.receivedAt < $1.receivedAt }
        .map(arrival(from:))
    }

    let canonical = MessagePathArrival(
      id: message.id,
      pathNodes: message.pathNodes ?? Data(),
      pathLength: message.pathLength,
      snr: message.snr,
      rssi: nil,
      receivedAt: message.createdAt
    )
    let extras = repeats
      .sorted { $0.receivedAt < $1.receivedAt }
      .map(arrival(from:))
    return [canonical] + extras
  }

  /// RX Log entries sharing a packet hash, oldest first. The tapped entry is kept
  /// even after the log prunes it.
  static func assemble(entry: RxLogEntryDTO, arrivals entries: [RxLogEntryDTO]) -> [MessagePathArrival] {
    let all = entries.contains { $0.id == entry.id } ? entries : entries + [entry]
    return all
      .sorted { $0.receivedAt < $1.receivedAt }
      .map { rxEntry in
        MessagePathArrival(
          id: rxEntry.id,
          pathNodes: rxEntry.pathNodes,
          pathLength: rxEntry.pathLength,
          snr: rxEntry.snr,
          rssi: rxEntry.rssi,
          receivedAt: rxEntry.receivedAt
        )
      }
  }

  static func arrivalCount(for message: MessageDTO) -> Int {
    message.isOutgoing ? message.heardRepeats : 1 + message.heardRepeats
  }

  /// Uses `preferred` when it is still in `arrivals`; otherwise the first so a
  /// vanished extra still lands on an arrival.
  static func selectedArrival(preferred: UUID?, in arrivals: [MessagePathArrival]) -> MessagePathArrival? {
    if let preferred, let match = arrivals.first(where: { $0.id == preferred }) {
      return match
    }
    return arrivals.first
  }

  static func resolvedSelection(preferred: UUID?, arrivals: [MessagePathArrival]) -> UUID? {
    selectedArrival(preferred: preferred, in: arrivals)?.id
  }

  private static func arrival(from repeatDTO: MessageRepeatDTO) -> MessagePathArrival {
    MessagePathArrival(
      id: repeatDTO.id,
      pathNodes: repeatDTO.pathNodes,
      pathLength: repeatDTO.pathLength,
      snr: repeatDTO.snr,
      rssi: repeatDTO.rssi,
      receivedAt: repeatDTO.receivedAt
    )
  }
}
