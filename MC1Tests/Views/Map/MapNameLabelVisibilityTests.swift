import MapLibre
@testable import MC1
import Testing

@Suite("MapNameLabelVisibility")
@MainActor
struct MapNameLabelVisibilityTests {
  @Test
  func `hidden preference makes the name-label layer not visible`() {
    let layer = Self.configuredLayer(showLabels: false)
    #expect(layer.isVisible == false)
  }

  @Test
  func `shown preference makes the name-label layer visible`() {
    let layer = Self.configuredLayer(showLabels: true)
    #expect(layer.isVisible == true)
  }

  private static func configuredLayer(showLabels: Bool) -> MLNSymbolStyleLayer {
    let coordinator = MC1MapView.Coordinator()
    coordinator.currentShowLabels = showLabels
    let source = MLNShapeSource(identifier: "probe-points", features: [], options: nil)
    let layer = MLNSymbolStyleLayer(identifier: "probe-name-labels", source: source)
    coordinator.configureNameLabelLayer(layer)
    return layer
  }
}
