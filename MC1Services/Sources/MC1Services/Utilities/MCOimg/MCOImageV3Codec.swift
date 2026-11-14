import Foundation

/// `decodeBody` keeps the leading packet nonce; `decodeBodyWithoutPacketNonce` starts at the header.
public enum MCOImageV3Codec {
  static let maximumDimension = 256
  static let scanCount = 4
  static let maximumBackgroundCandidates = 4
  static let smallDimensionLimit = 32
  static let mediumDimensionLimit = 64
  static let dimensionModeBits = 2
  static let squareDimensionBits = 6
  static let smallDimensionBits = 5
  static let extendedDimensionBits = 8
  static let byteBits = 8
  static let transparentFlag = 0x80
  static let implicitWhiteFlag = 0x40
  static let profileMask = 0x0F
  static let scanShift = 4
  static let containerShift = 5
  static let contextMask = 31

  enum Container: Int {
    case block = 0, compactBlock = 1, boundsBlock = 2, compactBoundsBlock = 3
    case regions = 4, compactRegions = 5, solid = 6, solidRects = 7
  }

  enum Algorithm: Int {
    case rawGlobal = 0, rawLocal = 1, rle = 2, sparse = 3, biColor = 4, rowRepeat = 5, lz = 6, quadtree = 7
    case bitplanes = 8, adaptiveBitplanes = 9, directBitplanes = 10, compactRowDelta = 11, directRowDelta = 12, rowDelta = 13
    case varRle = 14, varSparse = 15

    var compact: Bool { self == .rawGlobal || self == .rawLocal || self == .biColor }
    var usesBackground: Bool { self == .sparse || self == .varSparse || self == .biColor }
  }

  struct Decoder {
    var reader: MCOImageBitReader
    let width: Int
    let height: Int
    let profile: Int
    let implicitWhite: Bool

    struct Bounds {
      let x: Int
      let y: Int
      let width: Int
      let height: Int
    }
  }

  public static func decodeBody(_ body: Data) throws -> MCOImageRaster {
    try decode(body, hasNonce: true)
  }

  public static func decodeBodyWithoutPacketNonce(_ body: Data) throws -> MCOImageRaster {
    try decode(body, hasNonce: false)
  }

  private static func decode(_ body: Data, hasNonce: Bool) throws -> MCOImageRaster {
    let offset = hasNonce ? 1 : 0
    guard body.count >= offset + 3 else { throw MCOImageCodecError.damaged }
    let header = Int(body[body.startIndex + offset])
    let profile = header & profileMask
    let scan = (header >> scanShift) & 3
    let implicitWhite = header & implicitWhiteFlag != 0
    var reader = MCOImageBitReader(body, byteOffset: offset + 1)
    let (width, height) = try readDimensions(&reader)
    let containerByte = try reader.read(byteBits)
    guard let container = Container(rawValue: containerByte >> containerShift) else { throw MCOImageCodecError.unsupported }
    let context = containerByte & contextMask
    let isBlock = container.rawValue <= Container.compactBoundsBlock.rawValue
    guard let algorithm = Algorithm(rawValue: isBlock ? context : 0) else { throw MCOImageCodecError.unsupported }
    if container == .block && algorithm.compact || container == .compactBlock && !algorithm.compact {
      throw MCOImageCodecError.damaged
    }
    let requiresHorizontal = container == .compactBlock || container.rawValue >= Container.regions.rawValue
      || container == .compactBoundsBlock && algorithm.compact || algorithm == .quadtree
    guard !requiresHorizontal || scan == 0 else { throw MCOImageCodecError.damaged }
    guard !implicitWhite || container.rawValue >= Container.boundsBlock.rawValue || algorithm.usesBackground else {
      throw MCOImageCodecError.damaged
    }
    var decoder = Decoder(reader: reader, width: width, height: height, profile: profile, implicitWhite: implicitWhite)
    let transparent = try header & transparentFlag != 0 ? decoder.color() : nil
    let pixels = try decoder.pixels(container: container, algorithm: algorithm, scan: scan, context: context)
    try decoder.reader.finish()
    return MCOImageRaster(width: width, height: height, profile: profile, pixels: pixels, transparentColor: transparent)
  }

  static func readDimensions(_ reader: inout MCOImageBitReader) throws -> (Int, Int) {
    let mode = try reader.read(dimensionModeBits)
    let width: Int
    let height: Int
    switch mode {
    case 0:
      width = try reader.read(squareDimensionBits) + 1
      height = width
    case 1:
      width = try reader.read(smallDimensionBits) + 1
      height = try reader.read(smallDimensionBits) + 1
      guard width != height else { throw MCOImageCodecError.damaged }
    case 2:
      width = try reader.read(squareDimensionBits) + 1
      height = try reader.read(squareDimensionBits) + 1
      guard width != height, width > smallDimensionLimit || height > smallDimensionLimit else { throw MCOImageCodecError.damaged }
    default:
      let rectangle = try reader.read(1) != 0
      width = try reader.read(extendedDimensionBits) + 1
      height = try rectangle ? reader.read(extendedDimensionBits) + 1 : width
      guard rectangle ? width != height && (width > mediumDimensionLimit || height > mediumDimensionLimit) : width > mediumDimensionLimit else { throw MCOImageCodecError.damaged }
    }
    return (width, height)
  }

  static func scanIndexes(width: Int, height: Int, scan: Int) -> [Int] {
    if scan == 0 { return Array(0..<(width * height)) }
    var indexes: [Int] = []
    let vertical = scan == 1 || scan == 3
    let outer = vertical ? width : height
    let inner = vertical ? height : width
    for major in 0..<outer {
      for minor in 0..<inner {
        let coordinate = scan >= 2 && major % 2 != 0 ? inner - minor - 1 : minor
        indexes.append(vertical ? coordinate * width + major : major * width + coordinate)
      }
    }
    return indexes
  }

  static func fromScan(_ linear: [Int], width: Int, height: Int, scan: Int) -> [Int] {
    if scan == 0 { return linear }
    var result = Array(repeating: 0, count: linear.count)
    for (position, index) in scanIndexes(width: width, height: height, scan: scan).enumerated() { result[index] = linear[position] }
    return result
  }
}
