extension MCOImageV3Codec.Decoder {
  mutating func bitplanes(count: Int, palette: [Int], adaptive: Bool) throws -> [Int] {
    var pixels = Array(repeating: 0, count: count)
    for bit in 0..<MCOImageBitReader.width(palette.count - 1) {
      if try reader.read(1) == 0 {
        for index in 0..<count { pixels[index] |= try reader.read(1) << bit }
      } else if !adaptive {
        try bitplaneRuns(&pixels, bit: bit, short: false)
      } else {
        let prefix = try reader.prefix(limit: 2)
        if prefix < 2 {
          try bitplaneRuns(&pixels, bit: bit, short: prefix == 1)
          continue
        }
        let mode = try reader.read(2)
        if mode == 1 || mode == 3 { for index in 0..<count { pixels[index] |= 1 << bit } }
        if mode == 2 || mode == 3 { try sparseBitplane(&pixels, bit: bit, value: mode == 2) }
      }
    }
    return try mapLocal(pixels, palette: palette)
  }

  mutating func bitplaneRuns(_ pixels: inout [Int], bit: Int, short: Bool) throws {
    var value = try reader.read(1)
    var position = 0
    while position < pixels.count {
      let remaining = pixels.count - position
      let length = try short ? shortRun(remaining: remaining) : reader.range(remaining - 1) + 1
      if value != 0 { for offset in 0..<length { pixels[position + offset] |= 1 << bit } }
      position += length
      value ^= 1
    }
  }

  mutating func shortRun(remaining: Int) throws -> Int {
    for length in 1...3 {
      guard remaining >= length else { throw MCOImageCodecError.damaged }
      if try reader.read(1) == 0 { return length }
    }
    guard remaining >= 4 else { throw MCOImageCodecError.damaged }
    return try reader.range(remaining - 4) + 4
  }

  mutating func sparseBitplane(_ pixels: inout [Int], bit: Int, value: Bool) throws {
    let count = try reader.range(pixels.count - 1) + 1
    var previous = -1
    for index in 0..<count {
      let maximumGap = pixels.count - previous - (count - index - 1) - 2
      let position = try previous + reader.range(maximumGap) + 1
      if value { pixels[position] |= 1 << bit } else { pixels[position] &= ~(1 << bit) }
      previous = position
    }
  }
}
