import MapLibre
import UIKit

extension MC1MapView.Coordinator {
  private static let scrollPointsPerZoomLevel = 100.0

  func configureScrollWheelZoom() {
    scrollWheelZoomGesture.addTarget(self, action: #selector(handleScrollWheelZoom(_:)))
    scrollWheelZoomGesture.allowedScrollTypesMask = .discrete
    scrollWheelZoomGesture.allowedTouchTypes = []
    scrollWheelZoomGesture.delegate = self
    mapView.addGestureRecognizer(scrollWheelZoomGesture)
  }

  @objc func handleScrollWheelZoom(_ sender: UIPanGestureRecognizer) {
    guard sender.state == .began || sender.state == .changed || sender.state == .ended else { return }
    let translation = sender.translation(in: mapView)
    sender.setTranslation(.zero, in: mapView)
    guard mapView.isZoomEnabled, translation.y != 0 else { return }

    let zoom = min(mapView.maximumZoomLevel, max(
      mapView.minimumZoomLevel,
      mapView.zoomLevel + Double(translation.y) / Self.scrollPointsPerZoomLevel
    ))
    guard zoom != mapView.zoomLevel else { return }

    setIsCenteredOnUser?(false)
    mapView.setZoomLevel(zoom, animated: false)
    // MLNMapView reports camera setters as programmatic, so wheel zoom reports the user camera separately.
    userCameraDidChange()
  }
}
