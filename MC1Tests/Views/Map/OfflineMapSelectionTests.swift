import MapLibre
@testable import MC1
import Testing
import UIKit

@Suite("Offline map selection")
@MainActor
struct OfflineMapSelectionTests {
  @Test
  func `high latitude pitched selection covers each visible corner`() throws {
    let map = makeMap()
    map.setCenter(CLLocationCoordinate2D(latitude: 75, longitude: 20), zoomLevel: 6, animated: false)
    let camera = map.camera
    camera.pitch = 45
    map.setCamera(camera, animated: false)
    let bounds = try #require(MC1MapView.selectionBounds(in: map, padding: 40))

    for point in [CGPoint(x: 40, y: 40), CGPoint(x: 360, y: 40),
                  CGPoint(x: 40, y: 560), CGPoint(x: 360, y: 560)] {
      let coordinate = map.convert(point, toCoordinateFrom: map)
      #expect(coordinate.latitude >= bounds.sw.latitude - 0.000001)
      #expect(coordinate.latitude <= bounds.ne.latitude + 0.000001)
      #expect(coordinate.longitude >= bounds.sw.longitude - 0.000001)
      #expect(coordinate.longitude <= bounds.ne.longitude + 0.000001)
    }
  }

  @Test
  func `selection reads the current camera and resized map without a deferred callback`() throws {
    let map = makeMap()
    let initial = try #require(MC1MapView.selectionBounds(in: map, padding: 40))
    for size in [CGSize(width: 600, height: 400), CGSize(width: 1024, height: 768),
                 CGSize(width: 390, height: 844), CGSize(width: 669, height: 951),
                 CGSize(width: 951, height: 669)] {
      map.frame.size = size
      map.setNeedsLayout()
      map.layoutIfNeeded()
      map.setCenter(CLLocationCoordinate2D(latitude: 20, longitude: 30), zoomLevel: 9, animated: false)

      let current = try #require(MC1MapView.selectionBounds(in: map, padding: 40))
      let southwest = map.convert(current.sw, toPointTo: map)
      let northeast = map.convert(current.ne, toPointTo: map)
      #expect(!MLNCoordinateBoundsEqualToCoordinateBounds(initial, current))
      #expect(abs(southwest.x - 40) < 0.01)
      #expect(abs(southwest.y - (size.height - 40)) < 0.01)
      #expect(abs(northeast.x - (size.width - 40)) < 0.01)
      #expect(abs(northeast.y - 40) < 0.01)
    }
  }

  private func makeMap() -> MLNMapView {
    let map = MLNMapView(
      frame: CGRect(x: 0, y: 0, width: 400, height: 600),
      styleJSON: #"{"version":8,"sources":{},"layers":[]}"#
    )
    map.setCenter(CLLocationCoordinate2D(latitude: 0, longitude: 0), zoomLevel: 10, animated: false)
    return map
  }
}
