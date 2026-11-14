extension MCOImageV4Parser {
  mutating func path(delta: Bool, style: MCOImageV4Style) throws -> MCOImageFigure {
    let closed = try reader.bit()
    let shortBits = try delta ? Field.shortWidths[reader.read(Field.shortSelectorBits)] : nil
    let count = try reader.compact() + (closed ? 3 : 2)
    var points = [try point()]
    while points.count < count {
      let next: MCOImageV4Point
      if let shortBits {
        let previous = points[points.count - 1]
        let x = try reader.bit() ? coordinate(size: width, bits: xBits) : previous.x + reader.signed(shortBits)
        let y = try reader.bit() ? coordinate(size: height, bits: yBits) : previous.y + reader.signed(shortBits)
        next = MCOImageV4Point(x: x, y: y)
        try validate(next)
      } else { next = try point() }
      points.append(next)
    }
    return .path(points, closed: closed, style)
  }

  mutating func orthogonalPath(style: MCOImageV4Style) throws -> MCOImageFigure {
    let closed = try reader.bit()
    let bits = try Field.shortWidths[reader.read(Field.shortSelectorBits)]
    let count = try reader.compact() + (closed ? 3 : 2)
    var points = [try point()]
    while points.count < count {
      let previous = points[points.count - 1]
      let vertical = try reader.bit()
      let delta = try reader.signed(bits)
      let next = previous.translated(x: vertical ? 0 : delta, y: vertical ? delta : 0)
      try validate(next)
      points.append(next)
    }
    return .path(points, closed: closed, style)
  }

  mutating func boundsPath(delta: Bool, style: MCOImageV4Style) throws -> MCOImageFigure {
    let closed = try reader.bit()
    let shortBits = try delta ? Field.shortWidths[reader.read(Field.shortSelectorBits)] : nil
    let count = try reader.compact() + (closed ? 3 : 2)
    let origin = try point()
    let boundsWidth = try reader.read(xBits) + 1
    let boundsHeight = try reader.read(yBits) + 1
    try validate(origin.translated(x: boundsWidth - 1, y: boundsHeight - 1))
    let localXBits = Self.bits(boundsWidth - 1)
    let localYBits = Self.bits(boundsHeight - 1)
    var points: [MCOImageV4Point] = []
    while points.count < count {
      let next: MCOImageV4Point
      if let shortBits, let previous = points.last {
        next = try previous.translated(x: reader.signed(shortBits), y: reader.signed(shortBits))
      } else { next = try MCOImageV4Point(x: reader.read(localXBits), y: reader.read(localYBits)) }
      guard next.x >= 0, next.x < boundsWidth, next.y >= 0, next.y < boundsHeight else {
        throw MCOImageCodecError.damaged
      }
      points.append(next)
    }
    return .path(points.map { origin.translated(x: $0.x, y: $0.y) }, closed: closed, style)
  }

  mutating func dots(style: MCOImageV4Style) throws -> [MCOImageFigure] {
    let delta = try reader.bit()
    let shortBits = try delta ? Field.shortWidths[reader.read(Field.shortSelectorBits)] : nil
    let count = try reader.compact() + 2
    let minimumStepBits = shortBits.map { 2 * $0 } ?? (xBits + yBits)
    guard count - 1 <= reader.remaining / minimumStepBits else { throw MCOImageCodecError.damaged }
    try consume(count)
    var points = [try point()]
    while points.count < count {
      let next: MCOImageV4Point
      if let shortBits {
        next = try points[points.count - 1].translated(x: reader.signed(shortBits), y: reader.signed(shortBits))
        try validate(next)
      } else { next = try point() }
      points.append(next)
    }
    return points.map { .dot($0, style) }
  }
}
