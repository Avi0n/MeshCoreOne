extension MCOImageV3Codec.Decoder {
  mutating func compactRows(count: Int, rowLength: Int, valueBits: Int, maximum: Int, residuals: Bool) throws -> [Int] {
    let virtual = try reader.read(1) != 0
    var pixels = Array(repeating: 0, count: count)
    if !virtual { for x in 0..<rowLength { pixels[x] = try rowValue(valueBits, maximum: maximum) } }
    let rows = count / rowLength
    var row = virtual ? 0 : 1
    while row < rows {
      let start = row * rowLength
      let op = try reader.read(3)
      if op == 0 || op == 6 {
        let repeats = try op == 0 ? 1 : reader.range(rows - row - 2) + 2
        for index in row..<(row + repeats) { copyRow(&pixels, row: index, rowLength: rowLength, virtual: virtual) }
        row += repeats
        continue
      }
      if op == 1 {
        for x in 0..<rowLength { pixels[start + x] = try rowValue(valueBits, maximum: maximum) }
        row += 1
        continue
      }
      let prediction = try predictor(row: row, virtual: virtual)
      copyRow(&pixels, row: row, rowLength: rowLength, predictor: prediction, virtual: virtual)
      if op == 7 { row += 1; continue }
      let residual = try residuals && reader.read(1) != 0
      let positions = try compactPositions(op: op, rowLength: rowLength)
      if op == 3 {
        guard let first = positions.first else { throw MCOImageCodecError.damaged }
        let base = predicted(pixels, row: row, x: first, rowLength: rowLength, predictor: prediction, virtual: virtual)
        let value = try compactValue(valueBits, maximum: maximum, base: base, residual: residual)
        for x in positions { pixels[start + x] = value }
      } else {
        for x in positions {
          let base = predicted(pixels, row: row, x: x, rowLength: rowLength, predictor: prediction, virtual: virtual)
          pixels[start + x] = try compactValue(valueBits, maximum: maximum, base: base, residual: residual)
        }
      }
      row += 1
    }
    return pixels
  }

  mutating func compactPositions(op: Int, rowLength: Int) throws -> [Int] {
    var positions: [Int] = []
    if op == 2 || op == 3 {
      let count = try reader.range(rowLength - 1) + 1
      var previous = -1
      for index in 0..<count {
        let maximumGap = rowLength - previous - (count - index - 1) - 2
        let x = try previous + reader.range(maximumGap) + 1
        positions.append(x)
        previous = x
      }
    } else if op == 4 {
      let count = try reader.range(rowLength - 1) + 1
      var previousEnd = 0
      for index in 0..<count {
        let remaining = count - index - 1
        let start = try previousEnd + reader.range(rowLength - previousEnd - remaining - 1)
        let length = try reader.range(rowLength - start - remaining - 1) + 1
        positions.append(contentsOf: start..<(start + length))
        previousEnd = start + length
      }
    } else if op == 5 {
      let start = try reader.range(rowLength - 1)
      let span = try reader.range(rowLength - start - 1) + 1
      for offset in 0..<span { if try reader.read(1) != 0 { positions.append(start + offset) } }
      guard !positions.isEmpty else { throw MCOImageCodecError.damaged }
    } else { throw MCOImageCodecError.damaged }
    return positions
  }

  mutating func compactValue(_ bits: Int, maximum: Int, base: Int, residual: Bool) throws -> Int {
    if !residual { return try rowValue(bits, maximum: maximum) }
    let code = try reader.compact() + 1
    let delta = code & 1 == 1 ? (code + 1) / 2 : -(code / 2)
    let value = base + delta
    guard value >= 0, value <= maximum else { throw MCOImageCodecError.damaged }
    return value
  }
}
