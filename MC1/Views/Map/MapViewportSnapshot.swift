import CoreLocation
import MapLibre

struct MapViewportSnapshot: Equatable, Sendable {
  let latitude: Double
  let longitude: Double
  let zoom: Double
  let bearing: Double
  let pitch: Double
  let appliedCameraCommandVersion: Int

  @MainActor
  init(mapView: MLNMapView, appliedCameraCommandVersion: Int = 0) {
    latitude = mapView.centerCoordinate.latitude
    longitude = mapView.centerCoordinate.longitude
    zoom = mapView.zoomLevel
    bearing = mapView.direction
    pitch = Double(mapView.camera.pitch)
    self.appliedCameraCommandVersion = appliedCameraCommandVersion
  }

  init(
    latitude: Double, longitude: Double, zoom: Double, bearing: Double, pitch: Double,
    appliedCameraCommandVersion: Int = 0
  ) {
    self.latitude = latitude
    self.longitude = longitude
    self.zoom = zoom
    self.bearing = bearing
    self.pitch = pitch
    self.appliedCameraCommandVersion = appliedCameraCommandVersion
  }

  @MainActor
  func apply(to mapView: MLNMapView, northLocked: Bool) {
    mapView.setCenter(
      CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
      zoomLevel: zoom, direction: northLocked ? 0 : bearing, animated: false
    )
    let camera = mapView.camera
    camera.pitch = CGFloat(pitch)
    mapView.setCamera(camera, animated: false)
    mapView.setZoomLevel(zoom, animated: false)
  }
}
