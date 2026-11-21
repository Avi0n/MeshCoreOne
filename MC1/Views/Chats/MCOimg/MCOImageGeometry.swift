import MC1Services
import SwiftUI

/// Anchors are cell centers in image space. `MCOImageRenderer` applies viewport scale later.
enum MCOImageGeometry {
  static let cellCenter: CGFloat = 0.5

  static func point(_ point: MCOImageV4Point) -> CGPoint {
    CGPoint(x: CGFloat(point.x) + cellCenter, y: CGFloat(point.y) + cellCenter)
  }

  static func rectangle(_ first: MCOImageV4Point, _ second: MCOImageV4Point, _ third: MCOImageV4Point) -> Path {
    let first = point(first), second = point(second), third = point(third)
    var path = Path()
    path.move(to: first)
    path.addLine(to: third)
    path.addLine(to: second)
    path.addLine(to: CGPoint(x: first.x + second.x - third.x, y: first.y + second.y - third.y))
    return path
  }

  static func ellipse(_ first: MCOImageV4Point, _ second: MCOImageV4Point,
                      _ third: MCOImageV4Point) -> (path: Path, closed: Bool)? {
    let first = point(first), second = point(second), third = point(third)
    let dx = second.x - first.x, dy = second.y - first.y
    let length = hypot(dx, dy)
    guard length > 0 else { return nil }
    let center = CGPoint(x: (first.x + second.x) / 2, y: (first.y + second.y) / 2)
    let minor = abs((third.x - center.x) * -dy / length + (third.y - center.y) * dx / length)
    if minor < cellCenter {
      var path = Path()
      path.move(to: first)
      path.addLine(to: second)
      return (path, false)
    }
    let rect = CGRect(x: -length / 2, y: -minor, width: length, height: minor * 2)
    let transform = CGAffineTransform(translationX: center.x, y: center.y).rotated(by: atan2(dy, dx))
    return (Path(ellipseIn: rect).applying(transform), true)
  }

  static func wave(_ start: MCOImageV4Point, _ end: MCOImageV4Point, depth: Int) -> Path? {
    let start = point(start), end = point(end)
    let dx = end.x - start.x, dy = end.y - start.y
    let length = hypot(dx, dy)
    guard length > 0 else { return nil }
    let control = CGPoint(x: (start.x + end.x) / 2 - dy / length * CGFloat(depth) * 2,
                          y: (start.y + end.y) / 2 + dx / length * CGFloat(depth) * 2)
    var path = Path()
    path.move(to: start)
    path.addQuadCurve(to: end, control: control)
    return path
  }
}
