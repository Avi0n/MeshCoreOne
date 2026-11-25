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
    return Color(.sRGB, red: Double((rgb >> 16) & 0xFF) / maximumComponent,
                 green: Double((rgb >> 8) & 0xFF) / maximumComponent,
                 blue: Double(rgb & 0xFF) / maximumComponent, opacity: 1)
  }

  private static let bitsPerComponent = 8
  private static let bytesPerPixel = 4
  private static let opaqueAlpha: UInt8 = 0xFF

  /// Draws the raster as one bitmap without interpolation, so pixels stay sharp at any scale.
  static func raster(_ image: MCOImageRaster, origin: CGPoint, context: inout GraphicsContext) {
    guard let bitmap = bitmap(image) else { return }
    context.draw(
      Image(decorative: bitmap, scale: 1).interpolation(.none),
      in: CGRect(x: origin.x, y: origin.y, width: CGFloat(image.width), height: CGFloat(image.height))
    )
  }

  /// Premultiplied RGBA; a transparent reference stays all zero.
  static func bitmap(_ image: MCOImageRaster) -> CGImage? {
    guard image.width > 0, image.height > 0, image.pixels.count == image.width * image.height else { return nil }
    var bytes = [UInt8](repeating: 0, count: image.pixels.count * bytesPerPixel)
    for (index, reference) in image.pixels.enumerated() where reference != image.transparentColor {
      let rgb = MCOImagePalette.color(reference, profile: image.profile)
      let offset = index * bytesPerPixel
      bytes[offset] = UInt8((rgb >> 16) & 0xFF)
      bytes[offset + 1] = UInt8((rgb >> 8) & 0xFF)
      bytes[offset + 2] = UInt8(rgb & 0xFF)
      bytes[offset + 3] = opaqueAlpha
    }
    guard let provider = CGDataProvider(data: Data(bytes) as CFData),
          let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
    return CGImage(
      width: image.width,
      height: image.height,
      bitsPerComponent: bitsPerComponent,
      bitsPerPixel: bitsPerComponent * bytesPerPixel,
      bytesPerRow: image.width * bytesPerPixel,
      space: colorSpace,
      bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
      provider: provider,
      decode: nil,
      shouldInterpolate: false,
      intent: .defaultIntent
    )
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
      for point in points.dropFirst() {
        path.addLine(to: MCOImageGeometry.point(point))
      }
      paint(path, closed: closed, style: style, profile: profile, context: &context)
    case let .wave(start, end, depth, closed, style):
      guard let path = MCOImageGeometry.wave(start, end, depth: depth) else { return }
      paint(path, closed: closed, style: style, profile: profile, context: &context)
    case let .text(origin, width, alignment, fontSize, text, style):
      drawText(text, origin: origin, width: width, alignment: alignment, fontSize: fontSize,
               style: style, profile: profile, canvasWidth: canvasWidth, context: &context)
    case let .group(figures, _):
      for child in figures {
        self.figure(child, profile: profile, canvasWidth: canvasWidth, context: &context)
      }
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
