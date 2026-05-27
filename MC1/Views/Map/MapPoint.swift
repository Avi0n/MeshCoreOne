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
        case pointA
        case pointB
        case crosshair
        case obstruction
        case badge
        case droppedPin
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
