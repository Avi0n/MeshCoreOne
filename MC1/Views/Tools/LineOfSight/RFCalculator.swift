import CoreLocation
import Foundation

// MARK: - Path Analysis Types

/// Clearance status at worst point along path
enum ClearanceStatus: String {
    case clear = "Clear"
    case marginal = "Marginal"
    case partialObstruction = "Partial obstruction"
    case blocked = "Blocked"
}

/// Point where obstruction affects the path
struct ObstructionPoint: Identifiable, Equatable {
    let id = UUID()
    let distanceFromAMeters: Double
    let obstructionHeightMeters: Double
    let fresnelClearancePercent: Double

    static func == (lhs: ObstructionPoint, rhs: ObstructionPoint) -> Bool {
        lhs.distanceFromAMeters == rhs.distanceFromAMeters
            && lhs.obstructionHeightMeters == rhs.obstructionHeightMeters
            && lhs.fresnelClearancePercent == rhs.fresnelClearancePercent
    }
}

/// Complete analysis result for a path
struct PathAnalysisResult: Equatable {
    let distanceMeters: Double
    let freeSpacePathLoss: Double
    /// Peak diffraction loss from the single worst knife-edge obstruction (not cumulative)
    let peakDiffractionLoss: Double
    let totalPathLoss: Double
    let clearanceStatus: ClearanceStatus
    let worstClearancePercent: Double
    let obstructionPoints: [ObstructionPoint]
    let frequencyMHz: Double
    let refractionK: Double

    var distanceKm: Double { distanceMeters / 1000 }

    var worstObstructionPoint: ObstructionPoint? {
        obstructionPoints.min(by: { $0.fresnelClearancePercent < $1.fresnelClearancePercent })
    }

    /// Returns the worst obstruction point per contiguous obstructed region.
    /// Groups adjacent obstruction points by sample spacing, then picks the
    /// lowest clearance point from each group — one per red bar in the terrain profile.
    var peakObstructionPerRegion: [ObstructionPoint] {
        guard obstructionPoints.count >= 2 else { return obstructionPoints }

        // Find the smallest gap between consecutive points (= one sample step)
        var minGap = Double.infinity
        for i in 1..<obstructionPoints.count {
            let gap = obstructionPoints[i].distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters
            if gap > 0 && gap < minGap { minGap = gap }
        }
        guard minGap.isFinite else { return [obstructionPoints[0]] }

        // A gap > 2x the sample step means a non-obstructed sample separates two regions
        let gapThreshold = minGap * 2.5

        var regions: [ObstructionPoint] = []
        var regionWorst = obstructionPoints[0]

        for i in 1..<obstructionPoints.count {
            let point = obstructionPoints[i]
            let gap = point.distanceFromAMeters - obstructionPoints[i - 1].distanceFromAMeters

            if gap > gapThreshold {
                regions.append(regionWorst)
                regionWorst = point
            } else if point.fresnelClearancePercent < regionWorst.fresnelClearancePercent {
                regionWorst = point
            }
        }
        regions.append(regionWorst)

        return regions
    }
}

/// Elevation sample along the path
struct ElevationSample: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let elevation: Double  // meters above sea level
    let distanceFromAMeters: Double
}

/// Result for a single path segment (A→R or R→B)
struct SegmentAnalysisResult: Equatable {
    let startLabel: String
    let endLabel: String
    let clearanceStatus: ClearanceStatus
    let distanceMeters: Double
    let worstClearancePercent: Double

    var distanceKm: Double { distanceMeters / 1000 }
}

/// Combined result when analyzing a path via repeater
struct RelayPathAnalysisResult: Equatable {
    let segmentAR: SegmentAnalysisResult
    let segmentRB: SegmentAnalysisResult

    var totalDistanceMeters: Double {
        segmentAR.distanceMeters + segmentRB.distanceMeters
    }

    var totalDistanceKm: Double { totalDistanceMeters / 1000 }

    /// Overall status is the worst of the two segments
    var overallStatus: ClearanceStatus {
        let statusOrder: [ClearanceStatus] = [.clear, .marginal, .partialObstruction, .blocked]
        let arIndex = statusOrder.firstIndex(of: segmentAR.clearanceStatus) ?? 0
        let rbIndex = statusOrder.firstIndex(of: segmentRB.clearanceStatus) ?? 0
        return statusOrder[max(arIndex, rbIndex)]
    }
}

/// RF propagation calculator for line-of-sight analysis.
///
/// Provides functions for calculating wavelength, Fresnel zones, earth bulge,
/// path loss, and diffraction loss for radio frequency propagation analysis.
enum RFCalculator {

    // MARK: - Constants

    /// Speed of light in meters per second
    static let speedOfLight: Double = 299_792_458

    /// Earth's radius in kilometers
    static let earthRadiusKm: Double = 6371

    /// Minimum Fresnel zone clearance percentage for a "clear" path
    static let clearClearanceThreshold: Double = 80

    /// Minimum Fresnel zone clearance percentage for a "marginal" path
    static let marginalClearanceThreshold: Double = 60

    // MARK: - Wavelength

    /// Calculates the wavelength in meters for a given frequency.
    /// - Parameter frequencyMHz: The frequency in megahertz.
    /// - Returns: The wavelength in meters.
    static func wavelength(frequencyMHz: Double) -> Double {
        guard frequencyMHz > 0 else { return 0 }
        let frequencyHz = frequencyMHz * 1_000_000
        return speedOfLight / frequencyHz
    }

    // MARK: - Fresnel Zone

    /// Calculates the first Fresnel zone radius at a point along the path.
    ///
    /// The Fresnel zone represents the ellipsoidal region around the direct
    /// line-of-sight path where radio waves propagate. For best reception,
    /// at least 60% of the first Fresnel zone should be clear of obstructions.
    ///
    /// - Parameters:
    ///   - frequencyMHz: The frequency in megahertz.
    ///   - distanceToAMeters: Distance from point A to the calculation point in meters.
    ///   - distanceToBMeters: Distance from the calculation point to point B in meters.
    /// - Returns: The first Fresnel zone radius in meters.
    static func fresnelRadius(
        frequencyMHz: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double
    ) -> Double {
        guard frequencyMHz > 0, distanceToAMeters > 0, distanceToBMeters > 0 else { return 0 }

        let lambda = wavelength(frequencyMHz: frequencyMHz)
        let totalDistance = distanceToAMeters + distanceToBMeters

        // First Fresnel zone radius: r = sqrt((lambda * d1 * d2) / (d1 + d2))
        return sqrt((lambda * distanceToAMeters * distanceToBMeters) / totalDistance)
    }

    // MARK: - Earth Bulge

    /// Calculates the earth bulge (curvature correction) at a point along the path.
    ///
    /// Earth bulge represents how much the curved surface of the Earth rises
    /// above a straight line between two points. This is critical for long-distance
    /// radio links where the curvature can obstruct the signal path.
    ///
    /// - Parameters:
    ///   - distanceToAMeters: Distance from point A to the calculation point in meters.
    ///   - distanceToBMeters: Distance from the calculation point to point B in meters.
    ///   - refractionK: The effective earth radius factor. Use 1.0 for no adjustment,
    ///              1.33 (4/3) for standard atmosphere, or 4.0 for ducting conditions.
    /// - Returns: The earth bulge in meters.
    static func earthBulge(
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        refractionK: Double
    ) -> Double {
        guard distanceToAMeters > 0, distanceToBMeters > 0, refractionK > 0 else { return 0 }

        let earthRadiusMeters = earthRadiusKm * 1000
        let effectiveEarthRadius = refractionK * earthRadiusMeters

        // Earth bulge: h = (d1 * d2) / (2 * Re_effective)
        return (distanceToAMeters * distanceToBMeters) / (2 * effectiveEarthRadius)
    }

    // MARK: - Path Loss

    /// Calculates the free-space path loss in decibels.
    ///
    /// Free-space path loss represents the attenuation of radio signal
    /// as it travels through free space (vacuum). Real-world losses are
    /// typically higher due to atmospheric absorption and other factors.
    ///
    /// - Parameters:
    ///   - distanceMeters: The distance in meters.
    ///   - frequencyMHz: The frequency in megahertz.
    /// - Returns: The free-space path loss in dB.
    static func pathLoss(distanceMeters: Double, frequencyMHz: Double) -> Double {
        guard distanceMeters > 0, frequencyMHz > 0 else { return 0 }

        // FSPL (dB) = 20*log10(d) + 20*log10(f) + 20*log10(4*pi/c)
        // Simplified: FSPL = 20*log10(d_m) + 20*log10(f_MHz) + 20*log10(4*pi*1e6/c)
        // The constant = 20*log10(4*pi*1e6/299792458) ≈ -27.55
        let distanceComponent = 20 * log10(distanceMeters)
        let frequencyComponent = 20 * log10(frequencyMHz)
        let constant = -27.55

        return distanceComponent + frequencyComponent + constant
    }

    // MARK: - Diffraction Loss

    /// Calculates the knife-edge diffraction loss for an obstruction.
    ///
    /// Uses the Fresnel-Kirchhoff diffraction parameter (v) to estimate
    /// the loss caused by a single knife-edge obstruction in the path.
    ///
    /// - Parameters:
    ///   - obstructionHeightMeters: The height of the obstruction above the line-of-sight
    ///                              (positive = blocked, negative = clearance).
    ///   - distanceToAMeters: Distance from point A to the obstruction in meters.
    ///   - distanceToBMeters: Distance from the obstruction to point B in meters.
    ///   - frequencyMHz: The frequency in megahertz.
    /// - Returns: The diffraction loss in dB (positive value represents loss).
    static func diffractionLoss(
        obstructionHeightMeters: Double,
        distanceToAMeters: Double,
        distanceToBMeters: Double,
        frequencyMHz: Double
    ) -> Double {
        guard distanceToAMeters > 0, distanceToBMeters > 0, frequencyMHz > 0 else { return 0 }

        let lambda = wavelength(frequencyMHz: frequencyMHz)
        let totalDistance = distanceToAMeters + distanceToBMeters

        // Fresnel-Kirchhoff diffraction parameter:
        // v = h * sqrt(2 * (d1 + d2) / (lambda * d1 * d2))
        let vParam = obstructionHeightMeters * sqrt(
            2 * totalDistance / (lambda * distanceToAMeters * distanceToBMeters)
        )

        // Approximate diffraction loss based on v parameter
        // Using ITU-R P.526 approximation
        return diffractionLossFromV(vParam)
    }

    /// Calculates diffraction loss from the Fresnel-Kirchhoff v parameter.
    ///
    /// Uses a polynomial approximation of the ITU-R P.526 knife-edge diffraction model.
    ///
    /// - Parameter vParam: The Fresnel-Kirchhoff diffraction parameter.
    /// - Returns: The diffraction loss in dB.
    private static func diffractionLossFromV(_ vParam: Double) -> Double {
        if vParam < -1 {
            // Clear line-of-sight with good clearance
            // Negligible loss (small gain possible)
            return 0
        } else if vParam <= 0 {
            // Grazing or slight clearance
            // Approximately: L = 6.02 + 9.11*v + 1.27*v^2
            return max(0, 6.02 + 9.11 * vParam + 1.27 * vParam * vParam)
        } else if vParam <= 2.4 {
            // Moderate obstruction
            // Approximately: L = 6.02 + 9.11*v + 1.27*v^2
            return 6.02 + 9.11 * vParam + 1.27 * vParam * vParam
        } else {
            // Severe obstruction
            // Approximately: L = 12.95 + 20*log10(v)
            return 12.95 + 20 * log10(vParam)
        }
    }

    // MARK: - Distance Calculation

    /// Calculates the great-circle distance between two coordinates using the Haversine formula.
    ///
    /// - Parameters:
    ///   - from: The starting coordinate.
    ///   - destination: The ending coordinate.
    /// - Returns: The distance in meters.
    static func distance(from: CLLocationCoordinate2D, to destination: CLLocationCoordinate2D) -> Double {
        let earthRadiusMeters = earthRadiusKm * 1000

        let lat1 = from.latitude * .pi / 180
        let lat2 = destination.latitude * .pi / 180
        let deltaLat = (destination.latitude - from.latitude) * .pi / 180
        let deltaLon = (destination.longitude - from.longitude) * .pi / 180

        // Haversine formula
        let haversineA = sin(deltaLat / 2) * sin(deltaLat / 2) +
                         cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        let angularDistance = 2 * atan2(sqrt(haversineA), sqrt(1 - haversineA))

        return earthRadiusMeters * angularDistance
    }

    // MARK: - Path Analysis

    /// Analyze full path for clearance and signal propagation.
    ///
    /// This function evaluates an elevation profile between two points to determine:
    /// - Free-space path loss (FSPL)
    /// - Additional loss from diffraction over obstructions
    /// - Fresnel zone clearance at each point
    /// - Overall clearance status of the path
    ///
    /// - Parameters:
    ///   - elevationProfile: Array of elevation samples along the path from A to B.
    ///   - pointAHeightMeters: Antenna height at point A in meters above ground.
    ///   - pointBHeightMeters: Antenna height at point B in meters above ground.
    ///   - frequencyMHz: The operating frequency in megahertz.
    ///   - refractionK: The effective earth radius factor. Use 1.0 for no adjustment,
    ///              1.33 (4/3) for standard atmosphere, or 4.0 for ducting conditions.
    /// - Returns: A PathAnalysisResult containing loss calculations and clearance status.
    static func analyzePath(
        elevationProfile: [ElevationSample],
        pointAHeightMeters: Double,
        pointBHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double
    ) -> PathAnalysisResult {
        guard elevationProfile.count >= 2 else {
            return PathAnalysisResult(
                distanceMeters: 0,
                freeSpacePathLoss: 0,
                peakDiffractionLoss: 0,
                totalPathLoss: 0,
                clearanceStatus: .blocked,
                worstClearancePercent: 0,
                obstructionPoints: [],
                frequencyMHz: frequencyMHz,
                refractionK: refractionK
            )
        }

        // Get first and last samples for total distance and endpoint elevations
        let firstSample = elevationProfile.first!
        let lastSample = elevationProfile.last!
        let totalDistanceMeters = lastSample.distanceFromAMeters

        guard totalDistanceMeters > 0 else {
            return PathAnalysisResult(
                distanceMeters: 0,
                freeSpacePathLoss: 0,
                peakDiffractionLoss: 0,
                totalPathLoss: 0,
                clearanceStatus: .blocked,
                worstClearancePercent: 0,
                obstructionPoints: [],
                frequencyMHz: frequencyMHz,
                refractionK: refractionK
            )
        }

        // Antenna heights above sea level
        let antennaAHeight = firstSample.elevation + pointAHeightMeters
        let antennaBHeight = lastSample.elevation + pointBHeightMeters

        // Calculate free-space path loss
        let fspl = pathLoss(distanceMeters: totalDistanceMeters, frequencyMHz: frequencyMHz)

        var worstClearancePercent = Double.infinity
        var peakDiffractionLoss = 0.0
        var obstructionPoints: [ObstructionPoint] = []

        // Analyze each intermediate sample point (skip endpoints)
        for sample in elevationProfile {
            let distanceFromA = sample.distanceFromAMeters
            let distanceToB = totalDistanceMeters - distanceFromA

            // Skip points at or very near the endpoints
            guard distanceFromA > 1, distanceToB > 1 else { continue }

            // Line of sight height at this point (linear interpolation)
            let fraction = distanceFromA / totalDistanceMeters
            let losHeight = antennaAHeight + fraction * (antennaBHeight - antennaAHeight)

            // Effective terrain height including earth bulge
            let bulge = earthBulge(
                distanceToAMeters: distanceFromA,
                distanceToBMeters: distanceToB,
                refractionK: refractionK
            )
            let effectiveTerrainHeight = sample.elevation + bulge

            // Calculate Fresnel zone radius at this point
            let fresnelZoneRadius = fresnelRadius(
                frequencyMHz: frequencyMHz,
                distanceToAMeters: distanceFromA,
                distanceToBMeters: distanceToB
            )

            // Clearance: distance from terrain to line of sight
            let clearance = losHeight - effectiveTerrainHeight

            // Fresnel clearance percentage
            // 100% = terrain clears full first Fresnel zone
            // 0% = terrain touches line of sight
            // <0% = terrain blocks line of sight
            let clearancePercent: Double
            if fresnelZoneRadius > 0 {
                clearancePercent = (clearance / fresnelZoneRadius) * 100
            } else {
                clearancePercent = clearance > 0 ? 100 : 0
            }

            // Track worst clearance
            if clearancePercent < worstClearancePercent {
                worstClearancePercent = clearancePercent
            }

            // Calculate diffraction loss if there's an obstruction
            // Obstruction height is negative clearance (positive = blocked)
            let obstructionHeight = effectiveTerrainHeight - losHeight
            if obstructionHeight > -fresnelZoneRadius {
                let diffLoss = diffractionLoss(
                    obstructionHeightMeters: obstructionHeight,
                    distanceToAMeters: distanceFromA,
                    distanceToBMeters: distanceToB,
                    frequencyMHz: frequencyMHz
                )
                if diffLoss > peakDiffractionLoss {
                    peakDiffractionLoss = diffLoss
                }
            }

            // Record obstruction points where clearance < marginal threshold
            if clearancePercent < marginalClearanceThreshold {
                let obstruction = ObstructionPoint(
                    distanceFromAMeters: distanceFromA,
                    obstructionHeightMeters: obstructionHeight,
                    fresnelClearancePercent: clearancePercent
                )
                obstructionPoints.append(obstruction)
            }
        }

        // If no samples were analyzed, set default clearance
        if worstClearancePercent == .infinity {
            worstClearancePercent = 100
        }

        // Determine clearance status
        let clearanceStatus: ClearanceStatus
        if worstClearancePercent >= clearClearanceThreshold {
            clearanceStatus = .clear
        } else if worstClearancePercent >= marginalClearanceThreshold {
            clearanceStatus = .marginal
        } else if worstClearancePercent >= 0 {
            clearanceStatus = .partialObstruction
        } else {
            clearanceStatus = .blocked
        }

        let totalPathLoss = fspl + peakDiffractionLoss

        return PathAnalysisResult(
            distanceMeters: totalDistanceMeters,
            freeSpacePathLoss: fspl,
            peakDiffractionLoss: peakDiffractionLoss,
            totalPathLoss: totalPathLoss,
            clearanceStatus: clearanceStatus,
            worstClearancePercent: worstClearancePercent,
            obstructionPoints: obstructionPoints,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )
    }

    // MARK: - Segment Analysis

    /// Analyze a segment of the path for clearance and signal propagation.
    /// Uses ArraySlice to avoid copying - critical for 60fps drag performance.
    ///
    /// - Parameters:
    ///   - elevationProfile: Slice of elevation samples for this segment.
    ///   - startHeightMeters: Antenna height at segment start in meters above ground.
    ///   - endHeightMeters: Antenna height at segment end in meters above ground.
    ///   - frequencyMHz: The operating frequency in megahertz.
    ///   - refractionK: The effective earth radius factor.
    /// - Returns: A PathAnalysisResult for this segment.
    static func analyzePathSegment(
        elevationProfile: ArraySlice<ElevationSample>,
        startHeightMeters: Double,
        endHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double
    ) -> PathAnalysisResult {
        guard elevationProfile.count >= 2,
              let firstSample = elevationProfile.first,
              let lastSample = elevationProfile.last else {
            return PathAnalysisResult(
                distanceMeters: 0,
                freeSpacePathLoss: 0,
                peakDiffractionLoss: 0,
                totalPathLoss: 0,
                clearanceStatus: .blocked,
                worstClearancePercent: 0,
                obstructionPoints: [],
                frequencyMHz: frequencyMHz,
                refractionK: refractionK
            )
        }

        // Distance within this segment (relative to segment start)
        let segmentStartDistance = firstSample.distanceFromAMeters
        let segmentEndDistance = lastSample.distanceFromAMeters
        let segmentLength = segmentEndDistance - segmentStartDistance

        guard segmentLength > 0 else {
            return PathAnalysisResult(
                distanceMeters: 0,
                freeSpacePathLoss: 0,
                peakDiffractionLoss: 0,
                totalPathLoss: 0,
                clearanceStatus: .blocked,
                worstClearancePercent: 0,
                obstructionPoints: [],
                frequencyMHz: frequencyMHz,
                refractionK: refractionK
            )
        }

        // Antenna heights above sea level
        let antennaStartHeight = firstSample.elevation + startHeightMeters
        let antennaEndHeight = lastSample.elevation + endHeightMeters

        // Calculate free-space path loss
        let fspl = pathLoss(distanceMeters: segmentLength, frequencyMHz: frequencyMHz)

        var worstClearancePercent = Double.infinity
        var peakDiffractionLoss = 0.0
        var obstructionPoints: [ObstructionPoint] = []

        // Analyze each sample within the segment
        for sample in elevationProfile {
            let distanceFromSegmentStart = sample.distanceFromAMeters - segmentStartDistance
            let distanceToSegmentEnd = segmentLength - distanceFromSegmentStart

            // Skip points at or very near the endpoints
            guard distanceFromSegmentStart > 1, distanceToSegmentEnd > 1 else { continue }

            // Line of sight height at this point (linear interpolation within segment)
            let fraction = distanceFromSegmentStart / segmentLength
            let losHeight = antennaStartHeight + fraction * (antennaEndHeight - antennaStartHeight)

            // Effective terrain height including earth bulge
            let bulge = earthBulge(
                distanceToAMeters: distanceFromSegmentStart,
                distanceToBMeters: distanceToSegmentEnd,
                refractionK: refractionK
            )
            let effectiveTerrainHeight = sample.elevation + bulge

            // Calculate Fresnel zone radius at this point
            let fresnelZoneRadius = fresnelRadius(
                frequencyMHz: frequencyMHz,
                distanceToAMeters: distanceFromSegmentStart,
                distanceToBMeters: distanceToSegmentEnd
            )

            // Clearance
            let clearance = losHeight - effectiveTerrainHeight
            let clearancePercent: Double
            if fresnelZoneRadius > 0 {
                clearancePercent = (clearance / fresnelZoneRadius) * 100
            } else {
                clearancePercent = clearance > 0 ? 100 : 0
            }

            if clearancePercent < worstClearancePercent {
                worstClearancePercent = clearancePercent
            }

            // Diffraction loss
            let obstructionHeight = effectiveTerrainHeight - losHeight
            if obstructionHeight > -fresnelZoneRadius {
                let diffLoss = diffractionLoss(
                    obstructionHeightMeters: obstructionHeight,
                    distanceToAMeters: distanceFromSegmentStart,
                    distanceToBMeters: distanceToSegmentEnd,
                    frequencyMHz: frequencyMHz
                )
                if diffLoss > peakDiffractionLoss {
                    peakDiffractionLoss = diffLoss
                }
            }

            // Record obstruction points
            if clearancePercent < marginalClearanceThreshold {
                let obstruction = ObstructionPoint(
                    distanceFromAMeters: sample.distanceFromAMeters, // Keep original distance
                    obstructionHeightMeters: obstructionHeight,
                    fresnelClearancePercent: clearancePercent
                )
                obstructionPoints.append(obstruction)
            }
        }

        if worstClearancePercent == .infinity {
            worstClearancePercent = 100
        }

        let clearanceStatus: ClearanceStatus
        if worstClearancePercent >= clearClearanceThreshold {
            clearanceStatus = .clear
        } else if worstClearancePercent >= marginalClearanceThreshold {
            clearanceStatus = .marginal
        } else if worstClearancePercent >= 0 {
            clearanceStatus = .partialObstruction
        } else {
            clearanceStatus = .blocked
        }

        return PathAnalysisResult(
            distanceMeters: segmentLength,
            freeSpacePathLoss: fspl,
            peakDiffractionLoss: peakDiffractionLoss,
            totalPathLoss: fspl + peakDiffractionLoss,
            clearanceStatus: clearanceStatus,
            worstClearancePercent: worstClearancePercent,
            obstructionPoints: obstructionPoints,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )
    }
}
