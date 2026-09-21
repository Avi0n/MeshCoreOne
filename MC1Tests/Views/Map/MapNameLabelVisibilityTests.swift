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

  @Test
  func `managed style images are not evicted`() {
    let coordinator = MC1MapView.Coordinator()
    let mapView = coordinator.mapView
    #expect(coordinator.mapView(mapView, shouldRemoveStyleImage: "label-Alice") == false)
    #expect(coordinator.mapView(mapView, shouldRemoveStyleImage: "pin-repeater") == false)
    #expect(coordinator.mapView(mapView, shouldRemoveStyleImage: "pill-bg") == false)
    #expect(coordinator.mapView(mapView, shouldRemoveStyleImage: "osm-sprite") == true)
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
