import CoreLocation

struct MapPoint: Identifiable, Equatable {
  let id: UUID
  let coordinate: CLLocationCoordinate2D
  let pinStyle: PinStyle
  let label: String?
  let isClusterable: Bool

  enum PinStyle: String, Hashable {
    case contactChat
    case contactRepeater
    case contactRoom
    case repeater
    case repeaterRingBlue
    case repeaterRingGreen
    case repeaterRingWhite
    case repeaterHop
    case pointA
    case pointB
    case crosshair
    case obstruction
    case badge
    case droppedPin
    /// A sampled node location report: a small neutral dot threaded onto the
    /// history trail. Center-anchored, unlike the bottom-anchored teardrops.
    case locationFix
    /// The node's most recent location report: the emphasized hero teardrop that
    /// caps the trail.
    case locationFixLatest
  }

  let hopIndex: Int?
  let badgeText: String?

  static func == (lhs: MapPoint, rhs: MapPoint) -> Bool {
    lhs.id == rhs.id
      && lhs.coordinate.latitude == rhs.coordinate.latitude
      && lhs.coordinate.longitude == rhs.coordinate.longitude
      && lhs.pinStyle == rhs.pinStyle
      && lhs.label == rhs.label
      && lhs.isClusterable == rhs.isClusterable
      && lhs.hopIndex == rhs.hopIndex
      && lhs.badgeText == rhs.badgeText
  }
}
