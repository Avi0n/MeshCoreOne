import Foundation

extension MCOImageV4Parser {
  mutating func figure(_ opcode: Opcode, style: MCOImageV4Style) throws -> MCOImageFigure {
    switch opcode {
    case .dot: return try .dot(point(), style)
    case .line: return try .line(point(), point(), style)
    case .rectangle, .ellipse:
      let first = try point()
      let second = try point()
      let third = try point()
      return opcode == .rectangle ? .rectangle(first, second, third, style) : .ellipse(first, second, third, style)
    case .rectangleAxis, .ellipseAxis:
      let swapped = try reader.bit()
      let first = try point()
      let second = try point()
      let third = swapped ? MCOImageV4Point(x: second.x, y: first.y) : MCOImageV4Point(x: first.x, y: second.y)
      return opcode == .rectangleAxis ? .rectangle(first, second, third, style) : .ellipse(first, second, third, style)
    case .path, .pathDelta: return try path(delta: opcode == .pathDelta, style: style)
    case .wave:
      let closed = try reader.bit()
      let first = try point()
      let second = try point()
      return try .wave(first, second, depth: depth(), closed: closed, style)
    default: throw MCOImageCodecError.damaged
    }
  }

  mutating func depth() throws -> Int {
    let negative = try reader.bit()
    let magnitude = try reader.read(scalarBits) + 1
    return negative ? -magnitude : magnitude
  }

  mutating func pointSequence(count: Int) throws -> [MCOImageV4Point] {
    let bits = try Field.shortWidths[reader.read(Field.shortSelectorBits)]
    var points = [try point()]
    for _ in 1..<count {
      let previous = points[points.count - 1]
      let next = try previous.translated(x: reader.signed(bits), y: reader.signed(bits))
      try validate(next)
      points.append(next)
    }
    return points
  }

  mutating func text(style: MCOImageV4Style, alignment: inout Int, fontSize: inout Int) throws -> MCOImageFigure {
    let origin = try point()
    let maskBits = 3
    let widthMask = 1
    let alignmentMask = 2
    let fontMask = 4
    let alignmentBits = 2
    let alignmentCount = 3
    let mask = try reader.read(maskBits)
    let areaWidth = try mask & widthMask != 0 ? reader.read(xBits) + 1 : nil
    if mask & alignmentMask != 0 { alignment = try reader.read(alignmentBits) }
    guard alignment < alignmentCount else { throw MCOImageCodecError.damaged }
    if mask & fontMask != 0 { fontSize = try reader.read(scalarBits) + 1 }
    let bitLength = try reader.compact()
    guard bitLength <= reader.remaining else { throw MCOImageCodecError.damaged }
    var bytes = [UInt8](repeating: 0, count: (bitLength + Field.byteBits - 1) / Field.byteBits)
    for index in 0..<bitLength where try reader.bit() {
      bytes[index / Field.byteBits] |= 1 << (Field.byteBits - 1 - index % Field.byteBits)
    }
    let value = try MCOTextCodec.decode(Data(bytes), bitLength: bitLength)
    return .text(origin: origin, width: areaWidth, alignment: alignment, fontSize: fontSize, text: value, style)
  }

  mutating func raster() throws -> MCOImageFigure {
    let origin = try point()
    let headerBits = 4
    let header = try reader.read(headerBits) << headerBits | profile
    let count = try reader.compact()
    let minimumPayloadCount = 2
    guard count >= minimumPayloadCount else { throw MCOImageCodecError.damaged }
    let body = try Data([UInt8(header)]) + reader.data(count)
    let image = try MCOImageV3Codec.decodeBodyWithoutPacketNonce(body)
    guard image.profile == profile else { throw MCOImageCodecError.damaged }
    return .raster(origin: origin, image: image)
  }
}
