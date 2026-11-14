import Foundation
import MeshCore

/// Reads the sender name and stored MCOimg text without decoding the image.
public enum MCOImageTransport {
  public static let applicationDataType: UInt16 = 0x0120
  public static let imageSubtype: UInt8 = 0x01
  private static let subtypeShift: UInt8 = 4
  private static let varintPayloadMask: UInt8 = 0x7f
  private static let varintContinuation: UInt8 = 0x80
  private static let varintPayloadBits = 7
  private static let maxVarintBytes = 5

  public static func encode(text: String, senderName: String) throws -> Data {
    let payload = try MCOImageCodec.channelAppPayload(fromText: text)
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
