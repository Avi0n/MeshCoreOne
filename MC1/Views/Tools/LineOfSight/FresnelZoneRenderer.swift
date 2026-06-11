import SwiftUI

enum FresnelZoneRenderer {

    /// Calculate LOS height at a given distance along the path
    /// - Parameters:
    ///   - atDistance: Distance from point A in meters
    ///   - totalDistance: Total path distance in meters
    ///   - heightA: Antenna height at A (ground + antenna) in meters
    ///   - heightB: Antenna height at B (ground + antenna) in meters
    /// - Returns: LOS height in meters above sea level
    static func losHeight(
        atDistance: Double,
        totalDistance: Double,
        heightA: Double,
        heightB: Double
    ) -> Double {
        guard totalDistance > 0 else { return heightA }
        let fraction = atDistance / totalDistance
        return heightA + fraction * (heightB - heightA)
    }

    /// Build profile samples from elevation data
    /// - Parameters:
    ///   - elevationProfile: Array of elevation samples from terrain API (can be a segment slice)
    ///   - pointAHeight: Antenna height at segment start in meters above ground
    ///   - pointBHeight: Antenna height at segment end in meters above ground
    ///   - frequencyMHz: Operating frequency for Fresnel zone calculation
    ///   - refractionK: Effective earth radius factor for earth bulge calculation
    /// - Returns: Array of ProfileSample with computed LOS heights and Fresnel radii
    ///
    /// Note: When passing a segment slice (e.g., R→B), the samples may have non-zero
    /// distanceFromAMeters values. This method calculates Fresnel zones relative to
    /// the segment boundaries while preserving original x-coordinates for rendering.
    static func buildProfileSamples(
        from elevationProfile: [ElevationSample],
        pointAHeight: Double,
        pointBHeight: Double,
        frequencyMHz: Double,
        refractionK: Double
    ) -> [ProfileSample] {
        guard let first = elevationProfile.first,
              let last = elevationProfile.last else { return [] }

        // Calculate segment-relative distances for Fresnel/LOS calculations
        // This handles both full paths (offset=0) and segment slices (offset>0)
        let segmentOffset = first.distanceFromAMeters
        let segmentLength = last.distanceFromAMeters - segmentOffset

        let heightA = first.elevation + pointAHeight
        let heightB = last.elevation + pointBHeight

        return elevationProfile.map { sample in
            // Use segment-relative distances for physics calculations
            let distanceFromSegmentStart = sample.distanceFromAMeters - segmentOffset
            let distanceToSegmentEnd = segmentLength - distanceFromSegmentStart

            let yLOS = losHeight(
                atDistance: distanceFromSegmentStart,
                totalDistance: segmentLength,
                heightA: heightA,
                heightB: heightB
            )

            let radius = RFCalculator.fresnelRadius(
                frequencyMHz: frequencyMHz,
                distanceToAMeters: distanceFromSegmentStart,
                distanceToBMeters: distanceToSegmentEnd
            )

            let earthBulge = RFCalculator.earthBulge(
                distanceToAMeters: distanceFromSegmentStart,
                distanceToBMeters: distanceToSegmentEnd,
                refractionK: refractionK
            )

            // Preserve original x-coordinate for correct rendering position
            return ProfileSample(
                x: sample.distanceFromAMeters,
                yTerrain: sample.elevation + earthBulge,
                yLOS: yLOS,
                fresnelRadius: radius
            )
        }
    }

}

/// Sample point with all computed values for rendering
struct ProfileSample {
    let x: Double           // distance from A in meters
    let yTerrain: Double    // terrain elevation in meters
    let yLOS: Double        // line of sight height in meters
    let fresnelRadius: Double

    var yTop: Double { yLOS + fresnelRadius }
    var yBottom: Double { yLOS - fresnelRadius }

    // Inner 60% zone boundaries (ideal clearance threshold)
    var yTop60: Double { yLOS + fresnelRadius * 0.6 }
    var yBottom60: Double { yLOS - fresnelRadius * 0.6 }

    /// Visible bottom of inner 60% zone (clamped)
    var yVisibleBottom60: Double {
        min(max(yTerrain, yBottom60), yTop60)
    }

    /// Whether terrain intrudes past the 60% Fresnel clearance threshold at this point
    var isObstructed: Bool { yTerrain > yBottom60 }

    /// Visible bottom of Fresnel zone (clamped to avoid path inversion)
    var yVisibleBottom: Double {
        min(max(yTerrain, yBottom), yTop)
    }
}
