import Foundation
import MeshCore

/// Limits duplicate suppression because image packet nonces have only one byte.
struct MCOImageDatagramDuplicates {
  static let capacity = 128
  static let lifetime: TimeInterval = 60

  private struct Entry {
    let radioID: UUID
    let channelIndex: UInt8
    let payload: Data
    let receivedAt: Date
  }

  private var entries: [Entry] = []

  mutating func admit(_ datagram: ChannelDatagram, radioID: UUID, receivedAt: Date) -> Bool {
    entries.removeAll { receivedAt.timeIntervalSince($0.receivedAt) >= Self.lifetime }
    guard !entries.contains(where: {
      $0.radioID == radioID && $0.channelIndex == datagram.channelIndex && $0.payload == datagram.data
    }) else { return false }
    if entries.count == Self.capacity { entries.removeFirst() }
    entries.append(Entry(
      radioID: radioID,
      channelIndex: datagram.channelIndex,
      payload: datagram.data,
      receivedAt: receivedAt
    ))
    return true
  }
}
