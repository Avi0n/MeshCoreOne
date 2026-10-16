import CoreGraphics
@testable import MC1
import Testing

@Suite("Map ornament layout")
struct MapOrnamentLayoutTests {
  @Test
  func `nil clearance keeps the default logo and attribution margins`() {
    let logo = MC1MapView.OrnamentLayout.logoMargins(bottomClearance: nil)
    let attribution = MC1MapView.OrnamentLayout.attributionMargins(
      bottomClearance: nil,
      logoWidth: 80
    )
    #expect(logo == MC1MapView.OrnamentLayout.defaultLogoMargins)
    #expect(attribution == MC1MapView.OrnamentLayout.defaultAttributionMargins)
  }

  @Test
  func `clearance lifts logo and attribution to the same baseline beside each other`() {
    let clearance: CGFloat = 64
    let logoWidth: CGFloat = 80
    let logo = MC1MapView.OrnamentLayout.logoMargins(bottomClearance: clearance)
    let attribution = MC1MapView.OrnamentLayout.attributionMargins(
      bottomClearance: clearance,
      logoWidth: logoWidth
    )
    #expect(logo.x == MC1MapView.OrnamentLayout.defaultLogoMargins.x)
    #expect(logo.y == clearance)
    #expect(attribution.y == clearance)
    #expect(
      attribution.x
        == MC1MapView.OrnamentLayout.defaultLogoMargins.x
        + logoWidth
        + MC1MapView.OrnamentLayout.clusterSpacing
    )
  }
}
