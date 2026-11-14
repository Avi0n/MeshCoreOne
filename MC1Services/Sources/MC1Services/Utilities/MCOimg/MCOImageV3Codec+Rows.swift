extension MCOImageV3Codec.Decoder {
  mutating func rowValue(_ bits: Int, maximum: Int) throws -> Int {
    let value = try reader.read(bits)
    guard value <= maximum else { throw MCOImageCodecError.damaged }
    return value
  }

  mutating func predictor(row: Int, virtual: Bool, enabled: Bool = true) throws -> Int {
    let value = try !enabled || reader.read(1) == 0 ? 0 : reader.read(1) + 1
    guard row != 0 || !virtual || value == 0 else { throw MCOImageCodecError.damaged }
    return value
  }

  func predicted(_ pixels: [Int], row: Int, x: Int, rowLength: Int, predictor: Int, virtual: Bool) -> Int {
    if row == 0 && virtual { return 0 }
    let previousX = predictor == 1 ? (x + rowLength - 1) % rowLength : predictor == 2 ? (x + 1) % rowLength : x
    return pixels[(row - 1) * rowLength + previousX]
  }

  func copyRow(_ pixels: inout [Int], row: Int, rowLength: Int, predictor: Int = 0, virtual: Bool) {
    for x in 0..<rowLength { pixels[row * rowLength + x] = predicted(pixels, row: row, x: x, rowLength: rowLength, predictor: predictor, virtual: virtual) }
  }

  mutating func repeatRows(count: Int, rowLength: Int, bits: Int) throws -> [Int] {
    var pixels = Array(repeating: 0, count: count)
    for x in 0..<rowLength { pixels[x] = try reader.read(bits) }
    for row in 1..<(count / rowLength) {
      if try reader.read(1) != 0 { copyRow(&pixels, row: row, rowLength: rowLength, virtual: false) } else {
        for x in 0..<rowLength { pixels[row * rowLength + x] = try reader.read(bits) }
      }
    }
    return pixels
  }

  mutating func legacyRows(count: Int, rowLength: Int, valueBits: Int, maximum: Int) throws -> [Int] {
    let virtual = try reader.read(1) != 0
    let shifted = try reader.read(1) != 0
    var pixels = Array(repeating: 0, count: count)
    if !virtual { for x in 0..<rowLength { pixels[x] = try rowValue(valueBits, maximum: maximum) } }
    let rowCount = count / rowLength
    var row = virtual ? 0 : 1
    while row < rowCount {
      let start = row * rowLength
      let op = try reader.read(2)
      if op == 0 {
        for x in 0..<rowLength { pixels[start + x] = try rowValue(valueBits, maximum: maximum) }
      } else if op == 1 {
        copyRow(&pixels, row: row, rowLength: rowLength, virtual: virtual)
      } else if op == 2 {
        let prediction = try predictor(row: row, virtual: virtual, enabled: shifted)
        copyRow(&pixels, row: row, rowLength: rowLength, predictor: prediction, virtual: virtual)
        let changes = try reader.read(MCOImageBitReader.width(rowLength))
        guard changes <= rowLength else { throw MCOImageCodecError.damaged }
        var previous = -1
        for _ in 0..<changes {
          let x = try reader.read(MCOImageBitReader.width(rowLength - 1))
          guard x < rowLength, x > previous else { throw MCOImageCodecError.damaged }
          pixels[start + x] = try rowValue(valueBits, maximum: maximum)
          previous = x
        }
      } else {
        let extended = try reader.read(2)
        if extended == 3 {
          let repeats = try reader.range(rowCount - row - 2) + 2
          for index in row..<(row + repeats) { copyRow(&pixels, row: index, rowLength: rowLength, virtual: virtual) }
          row += repeats
          continue
        }
        let prediction = try predictor(row: row, virtual: virtual, enabled: shifted)
        copyRow(&pixels, row: row, rowLength: rowLength, predictor: prediction, virtual: virtual)
        if extended == 1 {
          try legacySegments(&pixels, start: start, rowLength: rowLength, bits: valueBits, maximum: maximum)
          row += 1
          continue
        }
        let positions = try legacyPositions(rowLength: rowLength)
        if extended == 2 {
          let value = try rowValue(valueBits, maximum: maximum)
          for x in positions { pixels[start + x] = value }
        } else {
          for x in positions { pixels[start + x] = try rowValue(valueBits, maximum: maximum) }
        }
      }
      row += 1
    }
    return pixels
  }

  mutating func legacyPositions(rowLength: Int) throws -> [Int] {
    var positions: [Int] = []
    for x in 0..<rowLength { if try reader.read(1) != 0 { positions.append(x) } }
    guard !positions.isEmpty else { throw MCOImageCodecError.damaged }
    return positions
  }

  mutating func legacySegments(_ pixels: inout [Int], start: Int, rowLength: Int, bits: Int, maximum: Int) throws {
    let positionBits = MCOImageBitReader.width(rowLength - 1)
    let segments = try reader.read(positionBits) + 1
    var previousEnd = -1
    for _ in 0..<segments {
      let x = try reader.read(positionBits)
      let length = try reader.read(positionBits) + 1
      guard x > previousEnd, x + length <= rowLength else { throw MCOImageCodecError.damaged }
      for offset in x..<(x + length) { pixels[start + offset] = try rowValue(bits, maximum: maximum) }
      previousEnd = x + length - 1
    }
  }
}
