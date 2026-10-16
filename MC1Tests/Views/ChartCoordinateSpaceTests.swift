@testable import MC1
import SwiftUI
import Testing

@Suite("ChartCoordinateSpace Tests")
struct ChartCoordinateSpaceTests {
  @Test
  func `xPixel returns leading padding at xRange.lowerBound`() {
    let space = ChartCoordinateSpace(
      canvasSize: CGSize(width: 400, height: 200),
      padding: EdgeInsets(top: 20, leading: 40, bottom: 30, trailing: 50),
      xRange: 0...10000,
      yRange: 0...500
    )

    let pixel = space.xPixel(0)
    #expect(pixel == 40) // leading padding
  }

  @Test
  func `xPixel returns width minus trailing padding at xRange.upperBound`() {
    let space = ChartCoordinateSpace(
      canvasSize: CGSize(width: 400, height: 200),
      padding: EdgeInsets(top: 20, leading: 40, bottom: 30, trailing: 50),
      xRange: 0...10000,
      yRange: 0...500
    )

    let pixel = space.xPixel(10000)
    #expect(pixel == 350) // 400 - 50 trailing
  }

  @Test
  func `yPixel returns height minus bottom padding at yRange.lowerBound (inverted)`() {
    let space = ChartCoordinateSpace(
      canvasSize: CGSize(width: 400, height: 200),
      padding: EdgeInsets(top: 20, leading: 40, bottom: 30, trailing: 50),
      xRange: 0...10000,
      yRange: 0...500
    )

    // At y=0 (lowest elevation), should be at bottom of plot area
    let pixel = space.yPixel(0)
    #expect(pixel == 170) // 200 - 30 bottom padding
  }

  @Test
  func `yPixel returns top padding at yRange.upperBound (inverted)`() {
    let space = ChartCoordinateSpace(
      canvasSize: CGSize(width: 400, height: 200),
      padding: EdgeInsets(top: 20, leading: 40, bottom: 30, trailing: 50),
      xRange: 0...10000,
      yRange: 0...500
    )

    // At y=500 (highest elevation), should be at top of plot area
    let pixel = space.yPixel(500)
    #expect(pixel == 20) // top padding
  }

  @Test
  func `point combines xPixel and yPixel`() {
    let space = ChartCoordinateSpace(
      canvasSize: CGSize(width: 400, height: 200),
      padding: EdgeInsets(top: 20, leading: 40, bottom: 30, trailing: 50),
      xRange: 0...10000,
      yRange: 0...500
    )

    let pt = space.point(x: 5000, y: 250)
    #expect(pt.x == 195) // midpoint of plot area x
    #expect(pt.y == 95) // midpoint of plot area y
  }

  @Test
  func `xLabel converts meters to km string`() {
    let space = ChartCoordinateSpace(
      canvasSize: CGSize(width: 400, height: 200),
      padding: EdgeInsets(top: 20, leading: 40, bottom: 30, trailing: 50),
      xRange: 0...10000,
      yRange: 0...500
    )

    #expect(space.xLabel(0) == 0.0.formatted(.number.precision(.fractionLength(1))))
    #expect(space.xLabel(5000) == 5.0.formatted(.number.precision(.fractionLength(1))))
    #expect(space.xLabel(10000) == 10.0.formatted(.number.precision(.fractionLength(1))))
  }
}
