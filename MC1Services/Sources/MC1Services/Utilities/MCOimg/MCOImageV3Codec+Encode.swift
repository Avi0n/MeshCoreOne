import Foundation

extension MCOImageV3Codec {
  public static func encode(_ image: MCOImageRaster, nonce: UInt8) throws -> Data {
    guard (1...maximumDimension).contains(image.width), (1...maximumDimension).contains(image.height),
          (0..<MCOImagePalette.profileCount).contains(image.profile), image.pixels.count == image.width * image.height else {
      throw MCOImageCodecError.damaged
    }
    for pixel in image.pixels { _ = try MCOImagePalette.stored(fromRef: pixel, profile: image.profile) }
    if let transparent = image.transparentColor { _ = try MCOImagePalette.stored(fromRef: transparent, profile: image.profile) }
    let palette = Array(Set(image.pixels)).sorted()
    var candidates: [Data] = []
    if palette.count == 1 {
      let color = try MCOImagePalette.stored(fromRef: palette[0], profile: image.profile)
      let implicit = palette[0] == MCOImagePalette.white(profile: image.profile)
      var writer = try preamble(image, nonce: nonce, scan: 0, implicit: implicit, container: .solid, context: implicit ? 0 : color & contextMask)
      if !implicit { writer.write(color >> containerShift, max(0, MCOImagePalette.bits(image.profile) - containerShift)) }
      return writer.data
    }
    let fullBounds = Decoder.Bounds(x: 0, y: 0, width: image.width, height: image.height)
    try addBlockCandidates(image, nonce: nonce, pixels: image.pixels, bounds: fullBounds, background: nil, candidates: &candidates)
    let counts = Dictionary(image.pixels.map { ($0, 1) }, uniquingKeysWith: +)
    let backgrounds = palette.sorted { counts[$0, default: 0] > counts[$1, default: 0] }.prefix(maximumBackgroundCandidates)
    for background in backgrounds {
      for scan in 0..<scanCount {
        let linear = scanIndexes(width: image.width, height: image.height, scan: scan).map { image.pixels[$0] }
        let implicit = background == MCOImagePalette.white(profile: image.profile)
        var writer = try preamble(image, nonce: nonce, scan: scan, implicit: implicit, container: .block, context: Algorithm.sparse.rawValue)
        if !implicit { try writeColor(background, profile: image.profile, writer: &writer) }
        try writeSparse(linear, background: background, profile: image.profile, writer: &writer)
        candidates.append(writer.data)
      }
      let foregroundIndexes = image.pixels.indices.filter { image.pixels[$0] != background }
      guard let minX = foregroundIndexes.map({ $0 % image.width }).min(), let maxX = foregroundIndexes.map({ $0 % image.width }).max(),
            let minY = foregroundIndexes.map({ $0 / image.width }).min(), let maxY = foregroundIndexes.map({ $0 / image.width }).max() else { continue }
      let bounds = Decoder.Bounds(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
      if bounds.width * bounds.height == image.pixels.count { continue }
      var cropped: [Int] = []
      for y in minY...maxY { cropped.append(contentsOf: image.pixels[(y * image.width + minX)...(y * image.width + maxX)]) }
      try addBlockCandidates(image, nonce: nonce, pixels: cropped, bounds: bounds, background: background, candidates: &candidates)
    }
    guard let best = candidates.min(by: {
      $0.count == $1.count ? MCOImageBase91.encode($0).utf8.count < MCOImageBase91.encode($1).utf8.count : $0.count < $1.count
    }) else { throw MCOImageCodecError.damaged }
    return best
  }

  private static func addBlockCandidates(
    _ image: MCOImageRaster, nonce: UInt8, pixels: [Int], bounds: Decoder.Bounds, background: Int?, candidates: inout [Data]
  ) throws {
    let algorithms: [Algorithm] = [.rawGlobal, .rawLocal, .rle, .rowRepeat]
    for algorithm in algorithms {
      for scan in 0..<(algorithm.compact ? 1 : scanCount) {
        let linear = scanIndexes(width: bounds.width, height: bounds.height, scan: scan).map { pixels[$0] }
        let implicit = background == MCOImagePalette.white(profile: image.profile)
        let container: Container = background == nil ? (algorithm.compact ? .compactBlock : .block) : .compactBoundsBlock
        var writer = try preamble(image, nonce: nonce, scan: scan, implicit: implicit, container: container, context: algorithm.rawValue)
        if let background {
          if !implicit { try writeColor(background, profile: image.profile, writer: &writer) }
          writer.write(bounds.x, MCOImageBitReader.width(image.width - 1))
          writer.write(bounds.y, MCOImageBitReader.width(image.height - 1))
          writer.write(bounds.width - 1, MCOImageBitReader.width(image.width - bounds.x - 1))
          writer.write(bounds.height - 1, MCOImageBitReader.width(image.height - bounds.y - 1))
        }
        try writeBlock(linear, profile: image.profile, algorithm: algorithm, rowLength: scan == 1 || scan == 3 ? bounds.height : bounds.width, writer: &writer)
        candidates.append(writer.data)
      }
    }
  }

  private static func preamble(
    _ image: MCOImageRaster, nonce: UInt8, scan: Int, implicit: Bool, container: Container, context: Int
  ) throws -> MCOImageBitWriter {
    var writer = MCOImageBitWriter()
    writer.write(Int(nonce), byteBits)
    writer.write(image.profile | (scan << scanShift) | (implicit ? implicitWhiteFlag : 0) | (image.transparentColor == nil ? 0 : transparentFlag), byteBits)
    writeDimensions(image.width, image.height, writer: &writer)
    writer.write((container.rawValue << containerShift) | context, byteBits)
    if let transparent = image.transparentColor { try writeColor(transparent, profile: image.profile, writer: &writer) }
    return writer
  }

  private static func writeDimensions(_ width: Int, _ height: Int, writer: inout MCOImageBitWriter) {
    if width == height && width <= mediumDimensionLimit { writer.write(0, dimensionModeBits); writer.write(width - 1, squareDimensionBits) } else if width <= smallDimensionLimit && height <= smallDimensionLimit {
      writer.write(1, dimensionModeBits); writer.write(width - 1, smallDimensionBits); writer.write(height - 1, smallDimensionBits)
    } else if width <= mediumDimensionLimit && height <= mediumDimensionLimit {
      writer.write(2, dimensionModeBits); writer.write(width - 1, squareDimensionBits); writer.write(height - 1, squareDimensionBits)
    } else {
      writer.write(3, dimensionModeBits); writer.write(width == height ? 0 : 1, 1); writer.write(width - 1, extendedDimensionBits)
      if width != height { writer.write(height - 1, extendedDimensionBits) }
    }
  }

  private static func writeColor(_ color: Int, profile: Int, writer: inout MCOImageBitWriter) throws {
    writer.write(try MCOImagePalette.stored(fromRef: color, profile: profile), MCOImagePalette.bits(profile))
  }

  private static func writePalette(_ palette: [Int], profile: Int, writer: inout MCOImageBitWriter) throws {
    if MCOImagePalette.count(profile: profile) <= 64 { writer.write(0, 1); writer.write(palette.count - 1, MCOImagePalette.bits(profile)) } else if palette.count <= 64 {
      writer.write(0, 1); writer.write(palette.count - 1, 6)
    } else if palette.count <= 128 {
      writer.write(1, 2); writer.write(palette.count - 65, 6)
    } else if palette.count <= 384 {
      writer.write(3, 3); writer.write(palette.count - 129, 8)
    } else { writer.write(7, 4); writer.write(palette.count - 385, 7) }
    for color in palette { try writeColor(color, profile: profile, writer: &writer) }
  }

  private static func writeBlock(_ pixels: [Int], profile: Int, algorithm: Algorithm, rowLength: Int, writer: inout MCOImageBitWriter) throws {
    if algorithm == .rawGlobal {
      for color in pixels { try writeColor(color, profile: profile, writer: &writer) }
      return
    }
    let palette = Array(Set(pixels)).sorted()
    try writePalette(palette, profile: profile, writer: &writer)
    let local = Dictionary(uniqueKeysWithValues: palette.enumerated().map { ($1, $0) })
    let indexes = pixels.map { local[$0]! }
    let bits = MCOImageBitReader.width(palette.count - 1)
    if algorithm == .rle {
      var position = 0
      while position < indexes.count {
        var end = position + 1
        while end < indexes.count && indexes[end] == indexes[position] { end += 1 }
        writer.write(indexes[position], bits)
        writer.bounded(end - position - 1, indexes.count - position - 1)
        position = end
      }
    } else if algorithm == .rowRepeat {
      for row in 0..<(indexes.count / rowLength) {
        let start = row * rowLength
        let same = row > 0 && indexes[start..<(start + rowLength)].elementsEqual(indexes[(start - rowLength)..<start])
        if row > 0 { writer.write(same ? 1 : 0, 1) }
        if !same { for index in indexes[start..<(start + rowLength)] { writer.write(index, bits) } }
      }
    } else { for index in indexes { writer.write(index, bits) } }
  }

  private static func writeSparse(_ pixels: [Int], background: Int, profile: Int, writer: inout MCOImageBitWriter) throws {
    let palette = Array(Set(pixels).subtracting([background])).sorted()
    try writePalette(palette, profile: profile, writer: &writer)
    let local = Dictionary(uniqueKeysWithValues: palette.enumerated().map { ($1, $0) })
    var runs: [(start: Int, length: Int, color: Int)] = []
    var position = 0
    while position < pixels.count {
      if pixels[position] == background { position += 1; continue }
      var end = position + 1
      while end < pixels.count && pixels[end] == pixels[position] { end += 1 }
      runs.append((position, end - position, pixels[position]))
      position = end
    }
    writer.bounded(runs.count - 1, pixels.count - 1)
    position = 0
    for run in runs {
      writer.bounded(run.start - position, pixels.count - position - 1)
      writer.write(local[run.color]!, MCOImageBitReader.width(palette.count - 1))
      writer.bounded(run.length - 1, pixels.count - run.start - 1)
      position = run.start + run.length
    }
  }
}
