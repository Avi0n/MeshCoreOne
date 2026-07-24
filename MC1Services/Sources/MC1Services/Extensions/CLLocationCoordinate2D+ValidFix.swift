import CoreLocation

public extension CLLocationCoordinate2D {
  /// A fix worth plotting: geographically valid and not the (0,0) null-island
  /// sentinel that a node without a GPS lock frequently emits.
  var isValidFix: Bool {
    CLLocationCoordinate2DIsValid(self) && !(latitude == 0 && longitude == 0)
  }
}
