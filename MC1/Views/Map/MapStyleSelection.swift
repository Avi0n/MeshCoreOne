import MapKit
import SwiftUI

enum MapStyleSelection: String, CaseIterable, Hashable {
    case standard
    case satellite

    var label: String {
        switch self {
        case .standard: L10n.Map.Map.Style.standard
        case .satellite: L10n.Map.Map.Style.satellite
        }
    }

    var mapType: MKMapType {
        switch self {
        case .standard: .standard
        case .satellite: .hybrid
        }
    }
}
