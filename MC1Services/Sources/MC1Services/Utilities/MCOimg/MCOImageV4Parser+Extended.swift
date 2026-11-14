import Foundation

extension MCOImageV4Parser {
  private enum Extended: Int {
    case lineDelta = 0, lineAxisDelta = 1, areaDelta = 2, waveDelta = 3, pathOrthogonal = 4
    case pathBounds = 5, pathBoundsDelta = 6, lineAxis = 7, ellipseDepth = 8, repeatBack = 9
    case dots = 10, style = 11, repeatColors = 12, group = 13, raster = 14, escape = 15
  }

  mutating func extended(
    history: [MCOImageFigure], nested: Bool,
    style: inout MCOImageV4Style, alignment: inout Int, fontSize: inout Int
  ) throws -> [MCOImageFigure] {
    let opcode = Extended(rawValue: try reader.read(Field.opcodeBits))!
    switch opcode {
    case .style, .repeatBack, .repeatColors, .dots, .group: break
    default: try consume(1)
    }
    switch opcode {
    case .style:
      let fillMask = 1
      let strokeMask = 2
      let widthMask = 4
      let maskBits = 3
      let mask = try reader.read(maskBits)
      guard mask != 0 else { throw MCOImageCodecError.damaged }
      if mask & fillMask != 0 { style.fillColor = try optionalColor() }
      if mask & strokeMask != 0 { style.strokeColor = try optionalColor() }
      if mask & widthMask != 0 { style.strokeWidth = try reader.read(scalarBits) + 1 }
      return []
    case .lineDelta:
      let points = try pointSequence(count: 2)
      return [.line(points[0], points[1], style)]
    case .lineAxisDelta:
      let vertical = try reader.bit()
      let bits = try Field.shortWidths[reader.read(Field.shortSelectorBits)]
      let start = try point()
      let delta = try reader.signed(bits)
      return [.line(start, start.translated(x: vertical ? 0 : delta, y: vertical ? delta : 0), style)]
    case .lineAxis:
      let vertical = try reader.bit()
      let first = try coordinate(size: vertical ? width : height, bits: vertical ? xBits : yBits)
      let start = try coordinate(size: vertical ? height : width, bits: vertical ? yBits : xBits)
      let end = try coordinate(size: vertical ? height : width, bits: vertical ? yBits : xBits)
      return [.line(MCOImageV4Point(x: vertical ? first : start, y: vertical ? start : first),
                    MCOImageV4Point(x: vertical ? first : end, y: vertical ? end : first), style)]
    case .areaDelta:
      let ellipse = try reader.bit()
      let points = try pointSequence(count: 3)
      return [ellipse ? .ellipse(points[0], points[1], points[2], style) : .rectangle(points[0], points[1], points[2], style)]
    case .waveDelta:
      let closed = try reader.bit()
      let depth = try depth()
      let points = try pointSequence(count: 2)
      return [.wave(points[0], points[1], depth: depth, closed: closed, style)]
    case .ellipseDepth:
      let first = try point()
      let second = try point()
      let third = try ellipseThird(first: first, second: second, depth: depth())
      return [.ellipse(first, second, third, style)]
    case .repeatBack:
      let distanceBits = 3
      let minimumDistance = 2
      let distance = try reader.read(distanceBits) + 1
      guard distance >= minimumDistance, distance <= history.count else { throw MCOImageCodecError.damaged }
      let delta = try translation(short: reader.bit())
      let previous = history[history.count - distance]
      try consume(previous.expandedCount(upTo: remainingFigures))
      return [previous.translated(x: delta.x, y: delta.y, style: style)]
    case .repeatColors: return try repeatedColors(history: history, style: &style)
    case .dots: return try dots(style: style)
    case .pathOrthogonal: return [try orthogonalPath(style: style)]
    case .pathBounds: return [try boundsPath(delta: false, style: style)]
    case .pathBoundsDelta: return [try boundsPath(delta: true, style: style)]
    case .escape:
      let textCommand = 0
      guard try reader.read(Field.opcodeBits) == textCommand else { throw MCOImageCodecError.unsupported }
      return [try text(style: style, alignment: &alignment, fontSize: &fontSize)]
    case .raster:
      let mixedMode = 1
      guard mode == mixedMode, !nested else { throw MCOImageCodecError.damaged }
      return [try raster()]
    case .group:
      let count = try reader.compact() + 1
      guard groupDepth < Field.maximumGroupDepth else { throw MCOImageCodecError.unsupported }
      groupDepth += 1
      defer { groupDepth -= 1 }
      var nestedStyle = style
      var nestedAlignment = alignment
      var nestedFont = fontSize
      let figures = try stream(count: count, style: &nestedStyle, alignment: &nestedAlignment, fontSize: &nestedFont)
      return [.group(figures, style)]
    }
  }

  private mutating func repeatedColors(history: [MCOImageFigure], style: inout MCOImageV4Style) throws -> [MCOImageFigure] {
    guard var previous = history.last else { throw MCOImageCodecError.damaged }
    let stroke = try reader.bit()
    let delta = try translation(short: reader.bit())
    let count = try reader.compact() + 2
    let hasColors = try reader.bit()
    if hasColors, count - 1 > reader.remaining { throw MCOImageCodecError.damaged }
    let figureCost = try previous.expandedCount(upTo: remainingFigures)
    guard count <= remainingFigures / figureCost else { throw MCOImageCodecError.unsupported }
    try consume(count * figureCost)
    let baseStyle = style
    var currentStyle = style
    var figures: [MCOImageFigure] = []
    for index in 0..<count {
      if index > 0, hasColors {
        currentStyle = baseStyle
        if stroke { currentStyle.strokeColor = try optionalColor() } else { currentStyle.fillColor = try optionalColor() }
      }
      let figure = previous.translated(x: delta.x, y: delta.y, style: currentStyle)
      try validate(figure)
      figures.append(figure)
      previous = figure
    }
    style = currentStyle
    return figures
  }

  private func ellipseThird(first: MCOImageV4Point, second: MCOImageV4Point, depth: Int) throws -> MCOImageV4Point {
    let dx = second.x - first.x
    let dy = second.y - first.y
    let squared = dx * dx + dy * dy
    let length = Int(Double(squared).squareRoot())
    guard length > 0, length * length == squared else { throw MCOImageCodecError.damaged }
    let denominator = 2 * length
    let numeratorX = (first.x + second.x) * length - 2 * dy * depth
    let numeratorY = (first.y + second.y) * length + 2 * dx * depth
    guard numeratorX % denominator == 0, numeratorY % denominator == 0 else { throw MCOImageCodecError.damaged }
    return MCOImageV4Point(x: numeratorX / denominator, y: numeratorY / denominator)
  }
}
