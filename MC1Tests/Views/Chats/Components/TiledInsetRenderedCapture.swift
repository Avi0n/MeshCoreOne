import Testing
import UIKit

@MainActor
final class TiledInsetRenderedCapture {
  private enum Metrics {
    static let scale: CGFloat = 1
    static let stripWidth: CGFloat = 1
    static let sampleColumn: CGFloat = 0.8
    static let bytesPerPixel = 4
    static let bitsPerComponent = 8
    static let minimumAlpha: UInt8 = 240
    /// Fixture colors must retain chroma when antialiased row edges blend.
    static let minimumChroma: UInt8 = 64
  }

  let directory: URL
  private var frames: [String: UIImage] = [:]

  init() throws {
    directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("tiled-animated-inset-\(UUID())", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  }

  func captureFrame(of window: UIWindow, name: String) {
    frames[name] = render(window, region: window.bounds)
  }

  func writeFrames() throws {
    for (name, image) in frames {
      let data = try #require(image.pngData(), "could not encode rendered timeline frame")
      try data.write(to: directory.appendingPathComponent("\(name).png"), options: .atomic)
    }
  }

  func unpaintedRowHeight(in window: UIWindow, readableRect: CGRect) throws -> CGFloat {
    let region = CGRect(
      x: readableRect.minX + readableRect.width * Metrics.sampleColumn,
      y: readableRect.minY,
      width: Metrics.stripWidth,
      height: readableRect.height
    )
    let image = try #require(render(window, region: region).cgImage)
    let bytesPerRow = image.width * Metrics.bytesPerPixel
    var pixels = [UInt8](repeating: 0, count: bytesPerRow * image.height)
    let decoded = pixels.withUnsafeMutableBytes { buffer in
      guard let context = CGContext(
        data: buffer.baseAddress,
        width: image.width,
        height: image.height,
        bitsPerComponent: Metrics.bitsPerComponent,
        bytesPerRow: bytesPerRow,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
      ) else { return false }
      context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
      return true
    }
    try #require(decoded, "could not read rendered timeline pixels")
    var unpainted = 0
    for offset in stride(from: 0, to: pixels.count, by: bytesPerRow) {
      let red = pixels[offset]
      let green = pixels[offset + 1]
      let blue = pixels[offset + 2]
      let alpha = pixels[offset + 3]
      let chroma = max(red, green, blue) - min(red, green, blue)
      if alpha < Metrics.minimumAlpha || chroma < Metrics.minimumChroma {
        unpainted += 1
      }
    }
    return CGFloat(unpainted) / Metrics.scale
  }

  private func render(_ window: UIWindow, region: CGRect) -> UIImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = Metrics.scale
    let renderer = UIGraphicsImageRenderer(size: region.size, format: format)
    return renderer.image { context in
      context.cgContext.translateBy(x: -region.minX, y: -region.minY)
      (window.layer.presentation() ?? window.layer).render(in: context.cgContext)
    }
  }
}
