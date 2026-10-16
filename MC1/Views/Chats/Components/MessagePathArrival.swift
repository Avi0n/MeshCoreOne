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
  let isFirst: Bool

  /// Hop count decoded from `pathLength` (lower 6 bits).
  var hopCount: Int {
    decodePathLen(pathLength)?.hopCount ?? Int(pathLength & 63)
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
    let size = hashSize
    guard size > 0 else { return [] }
    return stride(from: 0, to: pathNodes.count, by: size).map { offset in
      let end = min(offset + size, pathNodes.count)
      let chunk = pathNodes.subdata(in: offset..<end)
      return (chunk, chunk.uppercaseHexString())
    }
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
        .map { arrival(from: $0, isFirst: false) }
    }

    let canonical = MessagePathArrival(
      id: message.id,
      pathNodes: message.pathNodes ?? Data(),
      pathLength: message.pathLength,
      snr: message.snr,
      rssi: nil,
      receivedAt: message.createdAt,
      isFirst: true
    )
    let extras = repeats
      .sorted { $0.receivedAt < $1.receivedAt }
      .map { arrival(from: $0, isFirst: false) }
    return [canonical] + extras
  }

  static func arrivalCount(for message: MessageDTO) -> Int {
    message.isOutgoing ? message.heardRepeats : 1 + message.heardRepeats
  }

  /// Uses `preferred` when it is still in `arrivals`; otherwise the first so a
  /// vanished extra still lands on a capsule.
  static func resolvedSelection(preferred: UUID?, arrivals: [MessagePathArrival]) -> UUID? {
    if let preferred, arrivals.contains(where: { $0.id == preferred }) {
      return preferred
    }
    return arrivals.first?.id
  }

  private static func arrival(from repeatDTO: MessageRepeatDTO, isFirst: Bool) -> MessagePathArrival {
    MessagePathArrival(
      id: repeatDTO.id,
      pathNodes: repeatDTO.pathNodes,
      pathLength: repeatDTO.pathLength,
      snr: repeatDTO.snr,
      rssi: repeatDTO.rssi,
      receivedAt: repeatDTO.receivedAt,
      isFirst: isFirst
    )
  }
}
