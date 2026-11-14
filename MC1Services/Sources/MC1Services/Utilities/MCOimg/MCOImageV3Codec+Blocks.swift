extension MCOImageV3Codec.Decoder {
  mutating func block(
    width: Int, height: Int, algorithm: MCOImageV3Codec.Algorithm, scan: Int,
    background inheritedBackground: Int? = nil, sharedPalette: [Int]? = nil
  ) throws -> [Int] {
    let count = width * height
    let rowLength = scan == 1 || scan == 3 ? height : width
    if sharedPalette != nil && (algorithm == .rawGlobal || algorithm == .directBitplanes || algorithm == .directRowDelta) {
      throw MCOImageCodecError.damaged
    }
    if algorithm == .rawGlobal { return try (0..<count).map { _ in try color() } }
    if algorithm == .biColor {
      let background = try inheritedBackground ?? self.background()
      let foreground: Int
      if let palette = sharedPalette {
        let index = try reader.read(MCOImageBitReader.width(palette.count - 1))
        guard palette.indices.contains(index), palette[index] != background else { throw MCOImageCodecError.damaged }
        foreground = palette[index]
      } else { foreground = try color() }
      return try (0..<count).map { _ in try reader.read(1) != 0 ? foreground : background }
    }
    if algorithm == .directBitplanes || algorithm == .directRowDelta {
      let grayscale = [6, 7, 8].contains(profile)
      guard grayscale || profile >= MCOImagePalette.firstDynamicProfile else { throw MCOImageCodecError.damaged }
      let palette = MCOImagePalette.profileReferences(profile)
      if algorithm == .directBitplanes { return try bitplanes(count: count, palette: palette, adaptive: true) }
      return try mapLocal(compactRows(count: count, rowLength: rowLength, valueBits: MCOImagePalette.bits(profile),
                                      maximum: palette.count - 1, residuals: grayscale), palette: palette)
    }
    let background = try algorithm == .sparse || algorithm == .varSparse ? (inheritedBackground ?? self.background()) : nil
    let palette = try sharedPalette ?? localPalette()
    let bits = MCOImageBitReader.width(palette.count - 1)
    switch algorithm {
    case .rawLocal:
      return try mapLocal((0..<count).map { _ in try reader.read(bits) }, palette: palette)
    case .rle, .varRle:
      return try rle(count: count, palette: palette, variable: algorithm == .varRle)
    case .sparse, .varSparse:
      guard let background else { throw MCOImageCodecError.damaged }
      guard sharedPalette != nil || !palette.contains(background) else { throw MCOImageCodecError.damaged }
      return try sparse(count: count, palette: palette, background: background, variable: algorithm == .varSparse)
    case .lz:
      return try lz(count: count, palette: palette)
    case .quadtree:
      guard scan == 0 else { throw MCOImageCodecError.damaged }
      var pixels = Array(repeating: palette[0], count: count)
      try quadtree(&pixels, stride: width, bounds: Bounds(x: 0, y: 0, width: width, height: height), palette: palette)
      return pixels
    case .bitplanes, .adaptiveBitplanes:
      return try bitplanes(count: count, palette: palette, adaptive: algorithm == .adaptiveBitplanes)
    case .rowRepeat:
      return try mapLocal(repeatRows(count: count, rowLength: rowLength, bits: bits), palette: palette)
    case .rowDelta:
      return try mapLocal(legacyRows(count: count, rowLength: rowLength, valueBits: bits, maximum: palette.count - 1), palette: palette)
    case .compactRowDelta:
      return try mapLocal(compactRows(count: count, rowLength: rowLength, valueBits: bits, maximum: palette.count - 1, residuals: false), palette: palette)
    default:
      throw MCOImageCodecError.damaged
    }
  }

  func mapLocal(_ indexes: [Int], palette: [Int]) throws -> [Int] {
    try indexes.map { index in
      guard palette.indices.contains(index) else { throw MCOImageCodecError.damaged }
      return palette[index]
    }
  }

  mutating func rle(count: Int, palette: [Int], variable: Bool) throws -> [Int] {
    let bits = MCOImageBitReader.width(palette.count - 1)
    var result: [Int] = []
    while result.count < count {
      let index = try reader.read(bits)
      let length = try variable ? reader.varUint() : reader.bounded(count - result.count - 1) + 1
      guard palette.indices.contains(index), length > 0, length <= count - result.count else { throw MCOImageCodecError.damaged }
      result.append(contentsOf: repeatElement(palette[index], count: length))
    }
    return result
  }

  mutating func sparse(count: Int, palette: [Int], background: Int, variable: Bool) throws -> [Int] {
    let bits = MCOImageBitReader.width(palette.count - 1)
    let segments = try variable ? reader.varUint() : reader.bounded(count - 1) + 1
    guard segments > 0, segments <= count else { throw MCOImageCodecError.damaged }
    var result = Array(repeating: background, count: count)
    var position = 0
    for _ in 0..<segments {
      guard position < count else { throw MCOImageCodecError.damaged }
      let skip = try variable ? reader.varUint() : reader.bounded(count - position - 1)
      position += skip
      guard position < count else { throw MCOImageCodecError.damaged }
      let index = try reader.read(bits)
      let length = try variable ? reader.varUint() : reader.bounded(count - position - 1) + 1
      guard palette.indices.contains(index), palette[index] != background, length > 0, length <= count - position else {
        throw MCOImageCodecError.damaged
      }
      for offset in 0..<length { result[position + offset] = palette[index] }
      position += length
    }
    return result
  }

  mutating func lz(count: Int, palette: [Int]) throws -> [Int] {
    let minimumMatch = 3
    let bits = MCOImageBitReader.width(palette.count - 1)
    var result: [Int] = []
    while result.count < count {
      if try reader.read(1) != 0 {
        let remaining = count - result.count
        guard !result.isEmpty, remaining >= minimumMatch else { throw MCOImageCodecError.damaged }
        let distance = try reader.range(result.count - 1) + 1
        let length = try reader.range(remaining - minimumMatch) + minimumMatch
        for _ in 0..<length { result.append(result[result.count - distance]) }
      } else {
        let length = try reader.range(count - result.count - 1) + 1
        for _ in 0..<length {
          let index = try reader.read(bits)
          guard palette.indices.contains(index) else { throw MCOImageCodecError.damaged }
          result.append(palette[index])
        }
      }
    }
    return result
  }

  mutating func quadtree(_ pixels: inout [Int], stride: Int, bounds: Bounds, palette: [Int]) throws {
    if try reader.read(1) != 0 {
      let index = try reader.read(MCOImageBitReader.width(palette.count - 1))
      guard palette.indices.contains(index) else { throw MCOImageCodecError.damaged }
      for row in 0..<bounds.height {
        for column in 0..<bounds.width { pixels[(bounds.y + row) * stride + bounds.x + column] = palette[index] }
      }
      return
    }
    guard bounds.width > 1 || bounds.height > 1 else { throw MCOImageCodecError.damaged }
    let widths = bounds.width == 1 ? [1] : [bounds.width / 2, bounds.width - bounds.width / 2]
    let heights = bounds.height == 1 ? [1] : [bounds.height / 2, bounds.height - bounds.height / 2]
    var y = bounds.y
    for height in heights {
      var x = bounds.x
      for width in widths {
        try quadtree(&pixels, stride: stride, bounds: Bounds(x: x, y: y, width: width, height: height), palette: palette)
        x += width
      }
      y += height
    }
  }
}
