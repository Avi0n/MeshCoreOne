import Foundation

struct MCOImageV4BitReader {
  private let bytes: [UInt8]
  private(set) var offset = 0
  private static let bitsPerByte = 8
  private static let varUIntBytes = 5
  private static let varUIntPayloadBits = 7
  private static let varUIntPayloadMask = 0x7f
  private static let varUIntContinuation = 0x80

  init(_ data: Data) { bytes = Array(data) }

  var remaining: Int { bytes.count * Self.bitsPerByte - offset }

  mutating func read(_ bits: Int) throws -> Int {
    guard bits >= 0, bits <= Int.bitWidth - 1, bits <= remaining else { throw MCOImageCodecError.damaged }
    var value = 0
    for shift in 0..<bits {
      value |= Int((bytes[offset / Self.bitsPerByte] >> (offset % Self.bitsPerByte)) & 1) << shift
      offset += 1
    }
    return value
  }

  mutating func bit() throws -> Bool { try read(1) != 0 }

  mutating func signed(_ bits: Int) throws -> Int {
    let value = try read(bits)
    return value & 1 == 0 ? value >> 1 : -(value >> 1) - 1
  }

  mutating func compact() throws -> Int {
    if try !bit() { return try read(2) }
    if try !bit() { return try read(4) + 4 }
    if try !bit() { return try read(8) + 20 }
    let value = try varUInt()
    guard value > 275 else { throw MCOImageCodecError.damaged }
    return value
  }

  mutating func varUInt() throws -> Int {
    var value = 0
    for index in 0..<Self.varUIntBytes {
      let byte = try read(Self.bitsPerByte)
      let payload = byte & Self.varUIntPayloadMask
      value |= payload << (index * Self.varUIntPayloadBits)
      if byte & Self.varUIntContinuation == 0 {
        guard index == 0 || payload != 0 else { throw MCOImageCodecError.damaged }
        return value
      }
    }
    throw MCOImageCodecError.damaged
  }

  mutating func data(_ count: Int) throws -> Data {
    guard count >= 0, count <= remaining / Self.bitsPerByte else { throw MCOImageCodecError.damaged }
    return Data(try (0..<count).map { _ in UInt8(try read(Self.bitsPerByte)) })
  }

  mutating func finishByte() throws {
    let remainder = offset % Self.bitsPerByte
    if remainder != 0, try read(Self.bitsPerByte - remainder) != 0 { throw MCOImageCodecError.damaged }
  }
}
