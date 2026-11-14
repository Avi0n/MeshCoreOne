import Foundation

extension MCOImageV3Codec.Decoder {
  mutating func color() throws -> Int {
    try MCOImagePalette.ref(fromStored: reader.read(MCOImagePalette.bits(profile)), profile: profile)
  }

  mutating func background() throws -> Int { try implicitWhite ? MCOImagePalette.white(profile: profile) : color() }

  mutating func pixels(container: MCOImageV3Codec.Container, algorithm: MCOImageV3Codec.Algorithm, scan: Int, context: Int) throws -> [Int] {
    switch container {
    case .block, .compactBlock:
      return MCOImageV3Codec.fromScan(try block(width: width, height: height, algorithm: algorithm, scan: scan), width: width, height: height, scan: scan)
    case .boundsBlock, .compactBoundsBlock:
      let background = try background()
      let bounds = try geometry(compact: container == .compactBoundsBlock)
      let linear = try block(width: bounds.width, height: bounds.height, algorithm: algorithm, scan: scan, background: background)
      var result = Array(repeating: background, count: width * height)
      place(linear, in: bounds, scan: scan, result: &result)
      return result
    case .regions, .compactRegions:
      return try regions(count: context + 1, compact: container == .compactRegions)
    case .solid:
      if implicitWhite {
        guard context == 0 else { throw MCOImageCodecError.damaged }
        return Array(repeating: MCOImagePalette.white(profile: profile), count: width * height)
      }
      let bits = MCOImagePalette.bits(profile)
      let lowBits = min(bits, MCOImageV3Codec.containerShift)
      guard context < 1 << lowBits else { throw MCOImageCodecError.damaged }
      let ref = try context | reader.read(bits - lowBits) << lowBits
      return Array(repeating: try MCOImagePalette.ref(fromStored: ref, profile: profile), count: width * height)
    case .solidRects:
      let background = try background()
      let palette = try localPalette()
      let bits = MCOImageBitReader.width(palette.count - 1)
      let count = try context + (reader.read(1) << MCOImageV3Codec.containerShift) + 1
      var result = Array(repeating: background, count: width * height)
      for _ in 0..<count {
        let bounds = try geometry(compact: true)
        let index = try reader.read(bits)
        guard palette.indices.contains(index) else { throw MCOImageCodecError.damaged }
        place(Array(repeating: palette[index], count: bounds.width * bounds.height), in: bounds, scan: 0, result: &result)
      }
      return result
    }
  }

  mutating func regions(count: Int, compact: Bool) throws -> [Int] {
    let background = try background()
    let common = try compact && reader.read(1) != 0
    let delta = try compact && reader.read(1) != 0
    let shared = try compact && reader.read(1) != 0
    var commonAlgorithm: MCOImageV3Codec.Algorithm = .rawGlobal
    var commonScan = 0
    var hybrid = false
    if common {
      let algorithmID = try reader.read(5)
      hybrid = algorithmID == MCOImageV3Codec.contextMask
      commonAlgorithm = try readAlgorithm(hybrid ? reader.read(5) : algorithmID)
      commonScan = try commonAlgorithm.compact ? 0 : reader.read(2)
    }
    let palette = try shared ? localPalette() : nil
    var result = Array(repeating: background, count: width * height)
    var previous: Bounds?
    for _ in 0..<count {
      let bounds = try delta && previous != nil ? deltaGeometry(previous!) : geometry(compact: compact)
      previous = bounds
      let individual = try hybrid ? reader.read(1) != 0 : !common
      let algorithm = try individual ? readAlgorithm(reader.read(5)) : commonAlgorithm
      let scan = try individual ? (algorithm.compact ? 0 : reader.read(2)) : commonScan
      let linear = try block(width: bounds.width, height: bounds.height, algorithm: algorithm, scan: scan, background: background, sharedPalette: palette)
      place(linear, in: bounds, scan: scan, result: &result)
    }
    return result
  }

  func readAlgorithm(_ value: Int) throws -> MCOImageV3Codec.Algorithm {
    guard let algorithm = MCOImageV3Codec.Algorithm(rawValue: value) else { throw MCOImageCodecError.unsupported }
    return algorithm
  }

  mutating func geometry(compact: Bool) throws -> Bounds {
    let x = try reader.read(compact ? MCOImageBitReader.width(width - 1) : 8)
    let y = try reader.read(compact ? MCOImageBitReader.width(height - 1) : 8)
    guard x < width, y < height else { throw MCOImageCodecError.damaged }
    let rectWidth = try reader.read(compact ? MCOImageBitReader.width(width - x - 1) : 8) + 1
    let rectHeight = try reader.read(compact ? MCOImageBitReader.width(height - y - 1) : 8) + 1
    guard x + rectWidth <= width, y + rectHeight <= height else { throw MCOImageCodecError.damaged }
    return Bounds(x: x, y: y, width: rectWidth, height: rectHeight)
  }

  mutating func deltaGeometry(_ previous: Bounds) throws -> Bounds {
    let bounds = try Bounds(x: previous.x + reader.signed(), y: previous.y + reader.signed(),
                            width: previous.width + reader.signed(), height: previous.height + reader.signed())
    guard bounds.x >= 0, bounds.y >= 0, bounds.width > 0, bounds.height > 0,
          bounds.x + bounds.width <= width, bounds.y + bounds.height <= height else { throw MCOImageCodecError.damaged }
    return bounds
  }

  func place(_ linear: [Int], in bounds: Bounds, scan: Int, result: inout [Int]) {
    let pixels = MCOImageV3Codec.fromScan(linear, width: bounds.width, height: bounds.height, scan: scan)
    for row in 0..<bounds.height {
      for column in 0..<bounds.width {
        result[(bounds.y + row) * width + bounds.x + column] = pixels[row * bounds.width + column]
      }
    }
  }
}
