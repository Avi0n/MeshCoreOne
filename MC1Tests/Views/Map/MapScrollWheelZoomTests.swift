import MapKit
import MapLibre
@testable import MC1
import Testing
import UIKit

@Suite("Map scroll wheel zoom")
@MainActor
struct MapScrollWheelZoomTests {
  @Test
  func `map accepts discrete wheel scrolling without consuming touches or trackpad scrolling`() throws {
    let coordinator = MC1MapView.Coordinator()
    let wheel = try #require(coordinator.mapView.gestureRecognizers?
      .compactMap { $0 as? UIPanGestureRecognizer }
      .first { $0.allowedScrollTypesMask.contains(.discrete) })

    #expect(wheel.allowedScrollTypesMask == .discrete)
    #expect(wheel.allowedTouchTypes.isEmpty)
  }

  @Test(arguments: [
    (translation: 100.0, initialZoom: 5.0, expectedZoom: 6.0),
    (translation: -100.0, initialZoom: 5.0, expectedZoom: 4.0),
    (translation: 100.0, initialZoom: 9.5, expectedZoom: 10.0),
    (translation: -100.0, initialZoom: 2.5, expectedZoom: 2.0)
  ])
  func `wheel zoom changes scale within map limits`(
    values: (translation: Double, initialZoom: Double, expectedZoom: Double)
  ) {
    let coordinator = MC1MapView.Coordinator()
    let map = coordinator.mapView
    map.minimumZoomLevel = 2
    map.maximumZoomLevel = 10
    map.zoomLevel = values.initialZoom
    let wheel = WheelPan()
    map.addGestureRecognizer(wheel)
    wheel.setTranslation(CGPoint(x: 100, y: values.translation), in: map)

    coordinator.handleScrollWheelZoom(wheel)

    #expect(abs(map.zoomLevel - values.expectedZoom) < 0.0001)
    coordinator.pendingRegionTask?.cancel()
  }

  @Test
  func `disabled maps ignore wheel zoom`() {
    let coordinator = MC1MapView.Coordinator()
    let map = coordinator.mapView
    map.zoomLevel = 5
    map.isZoomEnabled = false
    let wheel = WheelPan()
    map.addGestureRecognizer(wheel)
    wheel.setTranslation(CGPoint(x: 0, y: 100), in: map)

    coordinator.handleScrollWheelZoom(wheel)

    #expect(map.zoomLevel == 5)
  }

  @Test
  func `wheel changes are incremental and persist the final user camera`() async throws {
    let coordinator = MC1MapView.Coordinator()
    let map = coordinator.mapView
    map.zoomLevel = 5
    coordinator.isStyleLoaded = true
    coordinator.viewportSnapshotProvider = { nil }
    var isCenteredOnUser = true
    var viewport: MapViewportSnapshot?
    var reportedRegions: [MKCoordinateRegion] = []
    coordinator.setIsCenteredOnUser = { isCenteredOnUser = $0 }
    coordinator.onViewportCapture = { viewport = $0 }
    coordinator.onCameraRegionChange = { reportedRegions.append($0) }
    let wheel = WheelPan()
    map.addGestureRecognizer(wheel)

    for _ in 0..<2 {
      wheel.setTranslation(CGPoint(x: 0, y: 100), in: map)
      coordinator.handleScrollWheelZoom(wheel)
    }
    let pending = try #require(coordinator.pendingRegionTask)
    await pending.value

    #expect(abs(map.zoomLevel - 7) < 0.0001)
    #expect(isCenteredOnUser == false)
    let captured = try #require(viewport)
    #expect(abs(captured.zoom - 7) < 0.0001)
    #expect(reportedRegions.count == 1)
    #expect(reportedRegions.first?.span.latitudeDelta == map.mlnRegion.span.latitudeDelta)
  }

  private final class WheelPan: UIPanGestureRecognizer {
    private var simulatedTranslation = CGPoint.zero

    override var state: UIGestureRecognizer.State {
      get { .changed }
      set {}
    }

    override func translation(in view: UIView?) -> CGPoint {
      simulatedTranslation
    }

    override func setTranslation(_ translation: CGPoint, in view: UIView?) {
      simulatedTranslation = translation
    }
  }
}
