public indirect enum MCOImageFigure: Sendable, Hashable {
  case dot(MCOImageV4Point, MCOImageV4Style)
  case line(MCOImageV4Point, MCOImageV4Point, MCOImageV4Style)
  case rectangle(MCOImageV4Point, MCOImageV4Point, MCOImageV4Point, MCOImageV4Style)
  case ellipse(MCOImageV4Point, MCOImageV4Point, MCOImageV4Point, MCOImageV4Style)
  case path([MCOImageV4Point], closed: Bool, MCOImageV4Style)
  case wave(MCOImageV4Point, MCOImageV4Point, depth: Int, closed: Bool, MCOImageV4Style)
  case text(origin: MCOImageV4Point, width: Int?, alignment: Int, fontSize: Int, text: String, MCOImageV4Style)
  case group([MCOImageFigure], MCOImageV4Style)
  case raster(origin: MCOImageV4Point, image: MCOImageRaster)

  public var style: MCOImageV4Style {
    switch self {
    case let .dot(_, style), let .line(_, _, style), let .rectangle(_, _, _, style),
         let .ellipse(_, _, _, style), let .path(_, _, style), let .wave(_, _, _, _, style),
         let .text(_, _, _, _, _, style), let .group(_, style): style
    case .raster: MCOImageV4Style(fillColor: nil, strokeColor: nil, strokeWidth: 1)
    }
  }

  func translated(x: Int, y: Int, style nextStyle: MCOImageV4Style) -> Self {
    func moved(_ point: MCOImageV4Point) -> MCOImageV4Point { point.translated(x: x, y: y) }
    switch self {
    case let .dot(point, _): return .dot(moved(point), nextStyle)
    case let .line(start, end, _): return .line(moved(start), moved(end), nextStyle)
    case let .rectangle(first, second, third, _): return .rectangle(moved(first), moved(second), moved(third), nextStyle)
    case let .ellipse(first, second, third, _): return .ellipse(moved(first), moved(second), moved(third), nextStyle)
    case let .path(points, closed, _): return .path(points.map(moved), closed: closed, nextStyle)
    case let .wave(start, end, depth, closed, _):
      return .wave(moved(start), moved(end), depth: depth, closed: closed, nextStyle)
    case let .text(origin, width, alignment, fontSize, text, _):
      return .text(origin: moved(origin), width: width, alignment: alignment, fontSize: fontSize, text: text, nextStyle)
    case let .group(figures, oldStyle):
      return .group(figures.map { child in
        child.translated(x: x, y: y, style: oldStyle == nextStyle ? child.style : nextStyle)
      }, nextStyle)
    case let .raster(origin, image): return .raster(origin: moved(origin), image: image)
    }
  }

  func expandedCount(upTo limit: Int) throws -> Int {
    guard limit > 0 else { throw MCOImageCodecError.unsupported }
    if case let .group(figures, _) = self {
      var total = 0
      for figure in figures { total += try figure.expandedCount(upTo: limit - total) }
      return total
    }
    return 1
  }
}
