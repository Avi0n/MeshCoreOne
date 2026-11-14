import Foundation

struct MCOTextBitReader {
  let bytes: [UInt8]
  let limit: Int
  private(set) var offset = 0
  private static let bitsPerByte = 8

  init(_ data: Data, bitLength: Int) throws {
    guard bitLength >= 0, bitLength <= data.count * Self.bitsPerByte else { throw MCOImageCodecError.damaged }
    bytes = Array(data)
    limit = bitLength
  }

  var remaining: Int { limit - offset }

  mutating func read(_ count: Int) throws -> Int {
    guard count >= 0, count < Int.bitWidth, count <= remaining else { throw MCOImageCodecError.damaged }
    var value = 0
    for _ in 0..<count {
      value = value << 1 | Int((bytes[offset / Self.bitsPerByte] >> (Self.bitsPerByte - 1 - offset % Self.bitsPerByte)) & 1)
      offset += 1
    }
    return value
  }

  mutating func bit() throws -> Bool { try read(1) != 0 }

  mutating func headerField() throws -> Int {
    let shortBits = 3
    let escape = 7
    let value = try read(shortBits)
    return try value == escape ? escape + read(Self.bitsPerByte) : value
  }
}
