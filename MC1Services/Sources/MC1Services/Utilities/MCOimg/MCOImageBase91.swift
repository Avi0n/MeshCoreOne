import Foundation

public enum MCOImageBase91 {
  private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&()*+,./:;<=>?@[]^_`{|}~\"".utf8)
  private static let decodeTable = Dictionary(uniqueKeysWithValues: alphabet.enumerated().map { ($1, $0) })
  private static let radix = 91
  private static let shortMask = 8191
  private static let longMask = 16383
  private static let shortThreshold = 88

  public static func encode(_ data: Data) -> String {
    var output: [UInt8] = []
    var queue = 0
    var bits = 0
    for byte in data {
      queue |= Int(byte) << bits
      bits += 8
      if bits > 13 {
        var value = queue & shortMask
        let consumed = value > shortThreshold ? 13 : 14
        if consumed == 14 { value = queue & longMask }
        queue >>= consumed
        bits -= consumed
        output.append(alphabet[value % radix])
        output.append(alphabet[value / radix])
      }
    }
    if bits > 0 {
      output.append(alphabet[queue % radix])
      if bits > 7 || queue > radix - 1 { output.append(alphabet[queue / radix]) }
    }
    return String(decoding: output, as: UTF8.self)
  }

  public static func decode(_ text: String) throws -> Data {
    var output = Data()
    var value = -1
    var queue = 0
    var bits = 0
    for character in text.utf8 {
      guard let decoded = decodeTable[character] else { throw MCOImageCodecError.damaged }
      if value < 0 {
        value = decoded
      } else {
        value += decoded * radix
        queue |= value << bits
        bits += (value & shortMask) > shortThreshold ? 13 : 14
        while bits > 7 {
          output.append(UInt8(queue & 255))
          queue >>= 8
          bits -= 8
        }
        value = -1
      }
    }
    if value >= 0 { output.append(UInt8((queue | (value << bits)) & 255)) }
    return output
  }
}
