import Foundation

struct MCOImageV4Parser {
  enum Opcode: Int {
    case end = 0, fill = 1, stroke = 2, strokeWidth = 3, dot = 4, line = 5
    case rectangle = 6, ellipse = 7, rectangleAxis = 8, path = 9, pathDelta = 10
    case wave = 11, repeatLast = 12, ellipseAxis = 13, repeatShort = 14, extended = 15
  }

  enum Field {
    static let opcodeBits = 4
    static let modeBits = 2
    static let profileBits = 4
    static let byteBits = 8
    static let shortSelectorBits = 2
    static let shortWidths = [3, 4, 5, 6]
    static let smallDimensionBits = 5
    static let mediumDimensionBits = 6
    static let smallDimensionMaximum = 32
    static let mediumDimensionMaximum = 64
    static let maximumOverscan = 16
    static let overscanDivisor = 8
    static let defaultStrokeWidth = 1
    static let initialTextAlignment = 0
    static let textSizePercent = 12
    static let percentDivisor = 100
    static let targetFlag = 1
    static let replyFlag = 2
    static let knownTailFlags = targetFlag | replyFlag
    static let maximumExpandedFigures = 4096
    static let maximumGroupDepth = 64
  }

  var reader: MCOImageV4BitReader
  var width = 0
  var height = 0
  var profile = 0
  var mode = 0
  var xBits = 0
  var yBits = 0
  var scalarBits = 0
  var colorBits = 0
  var remainingFigures = Field.maximumExpandedFigures
  var groupDepth = 0

  init(_ data: Data) { reader = MCOImageV4BitReader(data) }

  mutating func decode() throws -> MCOImageV4Document {
    mode = try reader.read(Field.modeBits)
    guard mode <= 1 else { throw MCOImageCodecError.unsupported }
    profile = try reader.read(Field.profileBits)
    (width, height) = try dimensions()
    xBits = Self.bits(width + 2 * margin(width) - 1)
    yBits = Self.bits(height + 2 * margin(height) - 1)
    scalarBits = Self.bits(max(width, height) - 1)
    colorBits = Self.bits(MCOImagePalette.count(profile: profile) - 1)
    let background = try reader.bit() ? optionalColor() : MCOImagePalette.white(profile: profile)
    var style = MCOImageV4Style(
      fillColor: nil, strokeColor: MCOImagePalette.black(profile: profile), strokeWidth: Field.defaultStrokeWidth
    )
    var alignment = Field.initialTextAlignment
    var fontSize = max(1, width * Field.textSizePercent / Field.percentDivisor)
    let figures = try stream(count: nil, style: &style, alignment: &alignment, fontSize: &fontSize)
    if mode == 1, !figures.contains(where: { if case .raster = $0 { true } else { false } }) {
      throw MCOImageCodecError.damaged
    }
    try reader.finishByte()
    var targetName: String?
    var replyTimestamp: UInt32?
    if reader.remaining > 0 {
      let flags = try reader.read(Field.byteBits)
      guard flags & ~Field.knownTailFlags == 0 else { throw MCOImageCodecError.damaged }
      if flags & Field.targetFlag != 0 {
        let count = try reader.varUInt()
        guard count > 0, let name = String(data: try reader.data(count), encoding: .utf8) else {
          throw MCOImageCodecError.damaged
        }
        targetName = name
      }
      if flags & Field.replyFlag != 0 {
        replyTimestamp = UInt32(try reader.read(Field.byteBits))
          | UInt32(try reader.read(Field.byteBits)) << Field.byteBits
          | UInt32(try reader.read(Field.byteBits)) << (2 * Field.byteBits)
          | UInt32(try reader.read(Field.byteBits)) << (3 * Field.byteBits)
      }
      guard reader.remaining == 0 else { throw MCOImageCodecError.damaged }
    }
    return MCOImageV4Document(width: width, height: height, profile: profile, backgroundColor: background,
                              figures: figures, targetName: targetName, replyTimestamp: replyTimestamp)
  }

  mutating func stream(
    count: Int?, style: inout MCOImageV4Style, alignment: inout Int, fontSize: inout Int
  ) throws -> [MCOImageFigure] {
    var figures: [MCOImageFigure] = []
    var history: [MCOImageFigure] = []
    while count == nil || figures.count < count! {
      let opcode = Opcode(rawValue: try reader.read(Field.opcodeBits))!
      var next: [MCOImageFigure] = []
      switch opcode {
      case .end:
        guard count == nil else { throw MCOImageCodecError.damaged }
        return figures
      case .fill: style.fillColor = try optionalColor()
      case .stroke: style.strokeColor = try optionalColor()
      case .strokeWidth: style.strokeWidth = try reader.read(scalarBits) + 1
      case .repeatLast, .repeatShort:
        guard let previous = history.last else { throw MCOImageCodecError.damaged }
        let short = opcode == .repeatShort
        let delta = try translation(short: short)
        try consume(previous.expandedCount(upTo: remainingFigures))
        next = [previous.translated(x: delta.x, y: delta.y, style: style)]
      case .extended:
        next = try extended(history: history, nested: count != nil, style: &style, alignment: &alignment, fontSize: &fontSize)
      default:
        try consume(1)
        next = [try figure(opcode, style: style)]
      }
      if let count, next.count > count - figures.count { throw MCOImageCodecError.damaged }
      for figure in next {
        try validate(figure)
        figures.append(figure)
        if case .raster = figure { continue }
        history.append(figure)
      }
    }
    return figures
  }

  mutating func dimensions() throws -> (Int, Int) {
    switch try reader.read(Field.modeBits) {
    case 0:
      let side = try reader.read(Field.mediumDimensionBits) + 1
      return (side, side)
    case 1:
      let width = try reader.read(Field.smallDimensionBits) + 1
      let height = try reader.read(Field.smallDimensionBits) + 1
      guard width != height else { throw MCOImageCodecError.damaged }
      return (width, height)
    case 2:
      let width = try reader.read(Field.mediumDimensionBits) + 1
      let height = try reader.read(Field.mediumDimensionBits) + 1
      guard width != height, width > Field.smallDimensionMaximum || height > Field.smallDimensionMaximum else {
        throw MCOImageCodecError.damaged
      }
      return (width, height)
    default:
      let rectangle = try reader.bit()
      let width = try reader.read(Field.byteBits) + 1
      let height = try rectangle ? reader.read(Field.byteBits) + 1 : width
      guard max(width, height) > Field.mediumDimensionMaximum, !rectangle || width != height else {
        throw MCOImageCodecError.damaged
      }
      return (width, height)
    }
  }

  mutating func optionalColor() throws -> Int? { try reader.bit() ? color() : nil }

  mutating func color() throws -> Int {
    try MCOImagePalette.ref(fromStored: reader.read(colorBits), profile: profile)
  }

  mutating func point() throws -> MCOImageV4Point {
    try MCOImageV4Point(x: coordinate(size: width, bits: xBits), y: coordinate(size: height, bits: yBits))
  }

  mutating func coordinate(size: Int, bits: Int) throws -> Int {
    let code = try reader.read(bits)
    guard code < size + 2 * margin(size) else { throw MCOImageCodecError.damaged }
    return code - margin(size)
  }

  mutating func translation(short: Bool) throws -> MCOImageV4Point {
    let shortBits = try short ? Field.shortWidths[reader.read(Field.shortSelectorBits)] : nil
    return try MCOImageV4Point(x: reader.signed(shortBits ?? (xBits + 1)), y: reader.signed(shortBits ?? (yBits + 1)))
  }

  func margin(_ size: Int) -> Int { min(Field.maximumOverscan, max(1, (size + Field.overscanDivisor - 1) / Field.overscanDivisor)) }

  static func bits(_ maximum: Int) -> Int { max(1, Int.bitWidth - maximum.leadingZeroBitCount) }

  mutating func consume(_ count: Int) throws {
    guard count <= remainingFigures else { throw MCOImageCodecError.unsupported }
    remainingFigures -= count
  }
}
