import MC1Services
import SwiftUI
import Testing
@testable import MC1

@Suite("MCOimg rendering geometry")
@MainActor
struct MCOImageGeometryTests {
  private func point(_ x: Int, _ y: Int) -> MCOImageV4Point { .init(x: x, y: y) }

  @Test func vectorAnchorsUseCellCentersWithoutClampingOverscan() {
    #expect(MCOImageGeometry.point(point(-1, 4)) == CGPoint(x: -0.5, y: 4.5))
    let rect = MCOImageGeometry.rectangle(point(0, 0), point(4, 4), point(4, 0))
    #expect(rect.boundingRect == CGRect(x: 0.5, y: 0.5, width: 4, height: 4))
  }

  @Test func ellipseUsesDiameterAndPerpendicularThirdPoint() throws {
    let ellipse = try #require(MCOImageGeometry.ellipse(point(0, 2), point(8, 2), point(4, 4)))
    #expect(ellipse.closed)
    #expect(ellipse.path.boundingRect == CGRect(x: 0.5, y: 0.5, width: 8, height: 4))
    let degenerate = try #require(MCOImageGeometry.ellipse(point(0, 0), point(4, 0), point(1, 0)))
    #expect(!degenerate.closed)
    #expect(MCOImageGeometry.ellipse(point(0, 0), point(0, 0), point(1, 1))?.path == nil)
  }

  @Test func waveDepthIsTheVisibleMidpointDisplacement() throws {
    let path = try #require(MCOImageGeometry.wave(point(0, 0), point(8, 0), depth: 2))
    var control: CGPoint?
    path.forEach { if case let .quadCurve(_, value) = $0 { control = value } }
    #expect(control == CGPoint(x: 4.5, y: 4.5))
    #expect(path.boundingRect.maxY == 2.5)
  }

  @Test func rasterPaintsExactProfileColorsAndTransparency() throws {
    let image = MCOImageRaster(width: 2, height: 1, profile: 0, pixels: [0, 1], transparentColor: 0)
    let renderer = ImageRenderer(content: MCOImageRenderer(content: .raster(image)).frame(width: 2, height: 1))
    renderer.scale = 1
    let rendered = try #require(renderer.cgImage)
    var bytes = [UInt8](repeating: 0, count: 8)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmap = try #require(CGContext(data: &bytes, width: 2, height: 1, bitsPerComponent: 8,
                                      bytesPerRow: 8, space: colorSpace,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    bitmap.draw(rendered, in: CGRect(x: 0, y: 0, width: 2, height: 1))
    #expect(Array(bytes[0..<4]) == [0, 0, 0, 0])
    #expect(Array(bytes[4..<8]) == [0, 0, 0, 255])
  }
}
