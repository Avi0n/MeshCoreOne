import Foundation
@testable import MC1
import Testing

@Suite("AvatarCropGeometry Tests")
struct AvatarCropGeometryTests {
  @Test
  func `fill size covers the crop square for a landscape image`() {
    let geometry = AvatarCropGeometry(cropSize: 100, imageSize: CGSize(width: 200, height: 100))
    #expect(geometry.baseDisplaySize == CGSize(width: 200, height: 100))
  }

  @Test
  func `fill size covers the crop square for a portrait image`() {
    let geometry = AvatarCropGeometry(cropSize: 100, imageSize: CGSize(width: 100, height: 200))
    #expect(geometry.baseDisplaySize == CGSize(width: 100, height: 200))
  }

  @Test
  func `zero image size falls back to the crop square`() {
    let geometry = AvatarCropGeometry(cropSize: 100, imageSize: .zero)
    #expect(geometry.baseDisplaySize == CGSize(width: 100, height: 100))
  }

  @Test
  func `scale clamps to 1...4`() {
    let geometry = AvatarCropGeometry(cropSize: 100, imageSize: CGSize(width: 100, height: 100))
    #expect(geometry.clampedScale(0.5) == 1)
    #expect(geometry.clampedScale(1) == 1)
    #expect(geometry.clampedScale(2) == 2)
    #expect(geometry.clampedScale(8) == 4)
  }

  @Test
  func `offset clamps so the crop square stays covered`() {
    let geometry = AvatarCropGeometry(
      cropSize: 100,
      imageSize: CGSize(width: 100, height: 100),
      scale: 2,
      offset: .zero
    )
    let clamped = geometry.clampedOffset(CGSize(width: 1000, height: -1000))
    #expect(clamped.width == 50)
    #expect(clamped.height == -50)
  }

  @Test
  func `live pinch reclamps offset to the live scale`() {
    let geometry = AvatarCropGeometry(
      cropSize: 100,
      imageSize: CGSize(width: 100, height: 100),
      scale: 4,
      offset: CGSize(width: 150, height: 0)
    )
    let live = geometry.liveTransform(pinchDelta: 0.5, dragTranslation: .zero)
    #expect(live.scale == 2)
    #expect(live.offset.width == 50)
    #expect(live.offset.height == 0)
  }

  @Test
  func `draw rect at scale 1 offset 0 matches the centered fill`() {
    let geometry = AvatarCropGeometry(cropSize: 100, imageSize: CGSize(width: 200, height: 100))
    let rect = geometry.imageDrawRect(outputSide: 512)
    #expect(abs(rect.origin.x - -256) < 0.001)
    #expect(abs(rect.origin.y - 0) < 0.001)
    #expect(abs(rect.width - 1024) < 0.001)
    #expect(abs(rect.height - 512) < 0.001)
  }

  @Test
  func `crop size shrinks on a short landscape canvas`() {
    let size = AvatarCropGeometry.cropSize(
      in: CGSize(width: 667, height: 280),
      isRegularWidth: false
    )
    #expect(size == 280 - (AvatarCropGeometry.cropMargin * 2))
  }

  @Test
  func `crop size uses the regular cap on iPad`() {
    let size = AvatarCropGeometry.cropSize(
      in: CGSize(width: 1024, height: 768),
      isRegularWidth: true
    )
    #expect(size == AvatarCropGeometry.regularMaxCropSize)
  }
}
