import Foundation
import MeshCore

/// Reads the sender name and stored MCOimg text without decoding the pixels.
public enum MCOImageTransport {
  public static let applicationDataType: UInt16 = 0x0120
  public static let imageSubtype: UInt8 = 0x01
  private static let subtypeShift: UInt8 = 4
  private static let varintPayloadMask: UInt8 = 0x7F
  private static let varintContinuation: UInt8 = 0x80
  private static let varintPayloadBits = 7
  private static let maxVarintBytes = 5

  /// A datagram has no clock, so two copies with the same data field are one picture only
  /// within this many seconds of each other. Flood repeats land well inside it; a later
  /// identical drawing is a new picture.
  public static let datagramMatchWindowSeconds: UInt32 = 300

  /// Unix seconds within `datagramMatchWindowSeconds` either side of `anchor`, clamped to `UInt32`.
  public static func datagramMatchWindow(around anchor: UInt32) -> ClosedRange<UInt32> {
    let lower = anchor > datagramMatchWindowSeconds ? anchor - datagramMatchWindowSeconds : 0
    let upper = anchor > UInt32.max - datagramMatchWindowSeconds ? UInt32.max : anchor + datagramMatchWindowSeconds
    return lower...upper
  }

  public static func datagramMatchWindow(around date: Date) -> ClosedRange<UInt32> {
    datagramMatchWindow(around: UInt32(clamping: Int64(date.timeIntervalSince1970)))
  }

  /// RX receive times within `datagramMatchWindowSeconds` either side of `anchor`.
  public static func datagramReceiveWindow(around anchor: Date) -> ClosedRange<Date> {
    let span = TimeInterval(datagramMatchWindowSeconds)
    return anchor.addingTimeInterval(-span)...anchor.addingTimeInterval(span)
  }

  /// RX receive times within the window of a row's `timestamp` (Unix seconds).
  public static func datagramReceiveWindow(around timestamp: UInt32) -> ClosedRange<Date> {
    datagramReceiveWindow(around: Date(timeIntervalSince1970: TimeInterval(timestamp)))
  }

  public static func encode(text: String, senderName: String) throws -> Data {
    try framed(MCOImageCodec.channelAppPayload(fromText: text), senderName: senderName)
  }

  /// The group-datagram `data` field for a stored row: varint name framing plus the stored app payload.
  /// Transmit still uses `encode`, which upgrades a v3 payload before it goes on the air.
  public static func dataField(text: String, senderName: String) throws -> Data {
    try framed(MCOImageCodec.appPayload(fromText: text), senderName: senderName)
  }

  private static func framed(_ payload: Data, senderName: String) -> Data {
    let name = Data(senderName.utf8)
    var writer = MCOImageBitWriter()
    writer.varUint(name.count)
    return writer.data + name + payload
  }

  public static func decode(_ datagram: ChannelDatagram) -> (senderName: String, text: String)? {
    guard datagram.dataType == applicationDataType,
          datagram.data.count <= MCOImageWireBudget.maximumChannelDataBytes else { return nil }
    let bytes = Array(datagram.data)
    var nameLength = 0
    var cursor = 0
    for index in 0..<maxVarintBytes {
      guard cursor < bytes.count else { return nil }
      let byte = bytes[cursor]
      cursor += 1
      let payload = byte & varintPayloadMask
      nameLength |= Int(payload) << (index * varintPayloadBits)
      guard byte & varintContinuation != 0 else {
        guard index == 0 || payload != 0,
              nameLength < bytes.count - cursor,
              let name = String(bytes: bytes[cursor..<(cursor + nameLength)], encoding: .utf8) else {
          return nil
        }
        cursor += nameLength
        guard bytes[cursor] >> subtypeShift == imageSubtype,
              let text = try? MCOImageCodec.text(fromAppPayload: Data(bytes[cursor...])) else {
          return nil
        }
        return (senderName: name, text: text)
      }
    }
    return nil
  }
}
