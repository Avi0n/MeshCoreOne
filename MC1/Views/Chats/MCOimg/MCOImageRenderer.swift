import MC1Services
import SwiftUI

/// Uses logical cells for both versions; raster origins are edges, vector points are centers.
struct MCOImageRenderer: View {
  let content: MCOImageContent
  private static let strokeMiterLimit: CGFloat = 4

  var body: some View {
    Canvas { context, size in
      switch content {
      case let .raster(image):
        prepare(&context, size: size, width: image.width, height: image.height)
        Self.raster(image, origin: .zero, context: &context)
      case let .document(document):
        prepare(&context, size: size, width: document.width, height: document.height)
        if let background = document.backgroundColor {
          context.fill(Path(CGRect(x: 0, y: 0, width: document.width, height: document.height)),
                       with: .color(Self.color(background, profile: document.profile)))
        }
        for figure in document.figures {
          Self.figure(figure, profile: document.profile, canvasWidth: document.width, context: &context)
        }
      case .unsupported, .damaged: break
      }
    }
  }

  private func prepare(_ context: inout GraphicsContext, size: CGSize, width: Int, height: Int) {
    let scale = min(size.width / CGFloat(width), size.height / CGFloat(height))
    context.translateBy(x: (size.width - CGFloat(width) * scale) / 2,
                        y: (size.height - CGFloat(height) * scale) / 2)
    context.scaleBy(x: scale, y: scale)
    context.clip(to: Path(CGRect(x: 0, y: 0, width: width, height: height)))
  }

  static func color(_ reference: Int, profile: Int) -> Color {
    let rgb = MCOImagePalette.color(reference, profile: profile)
    let maximumComponent = 255.0
    return Color(.sRGB, red: Double((rgb >> 16) & 0xff) / maximumComponent,
                 green: Double((rgb >> 8) & 0xff) / maximumComponent,
                 blue: Double(rgb & 0xff) / maximumComponent, opacity: 1)
  }

  static func raster(_ image: MCOImageRaster, origin: CGPoint, context: inout GraphicsContext) {
    for row in 0..<image.height {
      for column in 0..<image.width {
        let ref = image.pixels[row * image.width + column]
        guard ref != image.transparentColor else { continue }
        context.fill(Path(CGRect(x: origin.x + CGFloat(column), y: origin.y + CGFloat(row), width: 1, height: 1)),
                     with: .color(color(ref, profile: image.profile)), style: FillStyle(antialiased: false))
      }
    }
  }

  static func figure(_ figure: MCOImageFigure, profile: Int, canvasWidth: Int, context: inout GraphicsContext) {
    switch figure {
    case let .dot(point, style):
      guard let stroke = style.strokeColor else { return }
      let center = MCOImageGeometry.point(point)
      let radius = CGFloat(style.strokeWidth) / 2
      context.fill(Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius,
                                         width: radius * 2, height: radius * 2)), with: .color(color(stroke, profile: profile)))
    case let .line(start, end, style):
      var path = Path()
      path.move(to: MCOImageGeometry.point(start))
      path.addLine(to: MCOImageGeometry.point(end))
      // Lines and dots use stroke only, even when the current style has a fill.
      stroke(path, style: style, profile: profile, context: &context)
    case let .rectangle(first, second, third, style):
      paint(MCOImageGeometry.rectangle(first, second, third), closed: true, style: style, profile: profile, context: &context)
    case let .ellipse(first, second, third, style):
      let geometry = MCOImageGeometry.ellipse(first, second, third)
      guard let geometry else { return }
      paint(geometry.path, closed: geometry.closed, style: style, profile: profile, context: &context)
    case let .path(points, closed, style):
      guard let first = points.first else { return }
      var path = Path()
      path.move(to: MCOImageGeometry.point(first))
      for point in points.dropFirst() { path.addLine(to: MCOImageGeometry.point(point)) }
      paint(path, closed: closed, style: style, profile: profile, context: &context)
    case let .wave(start, end, depth, closed, style):
      guard let path = MCOImageGeometry.wave(start, end, depth: depth) else { return }
      paint(path, closed: closed, style: style, profile: profile, context: &context)
    case let .text(origin, width, alignment, fontSize, text, style):
      drawText(text, origin: origin, width: width, alignment: alignment, fontSize: fontSize,
               style: style, profile: profile, canvasWidth: canvasWidth, context: &context)
    case let .group(figures, _):
      for child in figures { self.figure(child, profile: profile, canvasWidth: canvasWidth, context: &context) }
    case let .raster(origin, image):
      raster(image, origin: CGPoint(x: origin.x, y: origin.y), context: &context)
    }
  }

  private static func paint(_ path: Path, closed: Bool, style: MCOImageV4Style,
                            profile: Int, context: inout GraphicsContext) {
    if let fill = style.fillColor {
      var fillPath = path
      fillPath.closeSubpath()
      context.fill(fillPath, with: .color(color(fill, profile: profile)), style: FillStyle(eoFill: false))
    }
    var strokePath = path
    if closed { strokePath.closeSubpath() }
    stroke(strokePath, style: style, profile: profile, context: &context)
  }

  private static func stroke(_ path: Path, style: MCOImageV4Style, profile: Int, context: inout GraphicsContext) {
    guard let stroke = style.strokeColor else { return }
    context.stroke(path, with: .color(color(stroke, profile: profile)),
                   style: StrokeStyle(lineWidth: CGFloat(style.strokeWidth), lineCap: .square, lineJoin: .miter, miterLimit: strokeMiterLimit))
  }
}
