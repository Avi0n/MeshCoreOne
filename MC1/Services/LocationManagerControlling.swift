import CoreLocation

/// Omits `authorizationStatus` so `LocationService` receives status via
/// `locationManagerDidChangeAuthorization(_:)` instead of synchronous `locationd` XPC.
@MainActor
protocol LocationManagerControlling: AnyObject {
  var delegate: (any CLLocationManagerDelegate)? { get set }
  var desiredAccuracy: CLLocationAccuracy { get set }
  func requestWhenInUseAuthorization()
  func requestLocation()
}

extension CLLocationManager: LocationManagerControlling {}
