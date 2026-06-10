import CoreLocation

struct MapLine: Identifiable, Equatable {
    let id: String
    let coordinates: [CLLocationCoordinate2D]
    let style: LineStyle
    let opacity: Double
    var pathIndex: Int?

    enum LineStyle: String, Hashable {
        case los
        case traceUntraced
        case traceWeak
        case traceMedium
        case traceGood
        case messagePath
    }

    static func == (lhs: MapLine, rhs: MapLine) -> Bool {
        lhs.id == rhs.id
            && lhs.style == rhs.style
            && lhs.opacity == rhs.opacity
            && lhs.coordinates.count == rhs.coordinates.count
            && zip(lhs.coordinates, rhs.coordinates).allSatisfy {
                $0.latitude == $1.latitude && $0.longitude == $1.longitude
            }
    }
}
