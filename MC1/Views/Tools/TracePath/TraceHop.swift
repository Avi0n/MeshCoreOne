import Foundation
import MC1Services

/// Represents a single hop in a trace result
struct TraceHop: Identifiable {
    let id = UUID()
    let hashBytes: Data?          // nil for start/end node (local device)
    let resolvedName: String?     // From contacts lookup
    let snr: Double
    let isStartNode: Bool
    let isEndNode: Bool
    let latitude: Double?
    let longitude: Double?

    /// Display string for hash (shows all bytes)
    var hashDisplayString: String? {
        hashBytes?.map { $0.hexString }.joined()
    }

    /// Whether this hop has a valid (non-zero) location.
    /// Uses OR logic to match ContactDTO.hasLocation - if either coordinate is non-zero,
    /// we have some location data. (0,0) is "Null Island" and extremely unlikely.
    var hasLocation: Bool {
        guard let lat = latitude, let lon = longitude else { return false }
        return lat != 0 || lon != 0
    }

    var snrQuality: SNRQuality { SNRQuality(snr: snr) }
}
