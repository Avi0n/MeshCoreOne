extension MCOImageV4Parser {
  func validate(_ point: MCOImageV4Point) throws {
    guard point.x >= -margin(width), point.x < width + margin(width),
          point.y >= -margin(height), point.y < height + margin(height) else { throw MCOImageCodecError.damaged }
  }

  func validate(_ figure: MCOImageFigure) throws {
    let style = figure.style
    guard style.strokeWidth >= Field.defaultStrokeWidth, style.strokeWidth <= max(width, height) else {
      throw MCOImageCodecError.damaged
    }
    switch figure {
    case let .dot(point, style):
      try validate(point)
      guard style.strokeColor != nil else { throw MCOImageCodecError.damaged }
    case let .line(start, end, _):
      try validate(start)
      try validate(end)
    case let .rectangle(first, second, third, _), let .ellipse(first, second, third, _):
      try validate(first)
      try validate(second)
      try validate(third)
      guard first != second, first != third, second != third else { throw MCOImageCodecError.damaged }
    case let .path(points, closed, _):
      let minimumPoints = closed ? 3 : 2
      guard points.count >= minimumPoints else { throw MCOImageCodecError.damaged }
      try points.forEach(validate)
    case let .wave(start, end, depth, _, _):
      try validate(start)
      try validate(end)
      guard depth != 0, abs(depth) <= max(width, height) else { throw MCOImageCodecError.damaged }
    case let .text(origin, areaWidth, _, fontSize, _, _):
      try validate(origin)
      if let areaWidth, areaWidth < 1 || areaWidth > width { throw MCOImageCodecError.damaged }
      guard fontSize >= 1, fontSize <= max(width, height) else { throw MCOImageCodecError.damaged }
    case let .group(figures, _): try figures.forEach(validate)
    case let .raster(origin, _): try validate(origin)
    }
  }
}
