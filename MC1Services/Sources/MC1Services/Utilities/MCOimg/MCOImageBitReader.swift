import Foundation

/// MCOimg fields are least-significant-bit first, including unaligned integers.
struct MCOImageBitReader {
  let bytes: [UInt8]
  var position: Int

  init(_ data: Data, byteOffset: Int = 0) {
    bytes = Array(data)
    position = byteOffset * 8
  }

  static func width(_ value: Int) -> Int { value <= 0 ? 0 : Int.bitWidth - value.leadingZeroBitCount }

  mutating func read(_ bits: Int) throws -> Int {
    guard (0...32).contains(bits), position >= 0, position + bits <= bytes.count * 8 else { throw MCOImageCodecError.damaged }
    var value = 0
    for shift in 0..<bits {
      value |= Int((bytes[position / 8] >> (position % 8)) & 1) << shift
      position += 1
    }
    return value
  }

  mutating func varUint() throws -> Int {
    var result = 0
    for index in 0..<5 {
      let byte = try read(8)
      result |= (byte & 127) << (index * 7)
      if byte & 128 == 0 { return result }
    }
    throw MCOImageCodecError.damaged
  }

  mutating func prefix(limit: Int) throws -> Int {
    for length in 0..<limit { if try read(1) == 0 { return length } }
    return limit
  }

  mutating func compact() throws -> Int {
    switch try prefix(limit: 3) {
    case 0: return try read(2)
    case 1: return try read(4) + 4
    case 2: return try read(8) + 20
    default: return try varUint()
    }
  }

  mutating func range(_ maximum: Int) throws -> Int {
    guard maximum >= 0 else { throw MCOImageCodecError.damaged }
    let value = try maximum <= 7 ? read(Self.width(maximum)) : compact()
    guard value <= maximum else { throw MCOImageCodecError.damaged }
    return value
  }

  mutating func bounded(_ maximum: Int) throws -> Int {
    guard maximum >= 0 else { throw MCOImageCodecError.damaged }
    if maximum <= 7 { return try range(maximum) }
    let value: Int
    switch try prefix(limit: 3) {
    case 0: value = try read(2)
    case 1: value = try read(4) + 4
    case 2: value = try read(8) + 20
    default:
      guard maximum >= 276 else { throw MCOImageCodecError.damaged }
      value = try read(Self.width(maximum - 276)) + 276
    }
    guard value <= maximum else { throw MCOImageCodecError.damaged }
    return value
  }

  mutating func signed() throws -> Int {
    let encoded = try compact()
    return encoded & 1 == 0 ? encoded / 2 : -(encoded + 1) / 2
  }

  mutating func finish() throws {
    let padding = (8 - position % 8) % 8
    guard try read(padding) == 0, position == bytes.count * 8 else { throw MCOImageCodecError.damaged }
  }
}
