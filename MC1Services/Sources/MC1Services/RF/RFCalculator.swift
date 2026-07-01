import CoreLocation
import Foundation

/// RF propagation calculator for line-of-sight analysis.
///
/// Provides functions for calculating wavelength, Fresnel zones, earth bulge,
/// path loss, and diffraction loss for radio frequency propagation analysis.
public enum RFCalculator {
  // MARK: - Constants

  /// Speed of light in meters per second
  public static let speedOfLight: Double = 299_792_458

  /// Earth's radius in kilometers
  public static let earthRadiusKm: Double = 6371

  /// Minimum Fresnel zone clearance percentage for a "clear" path
  public static let clearClearanceThreshold: Double = 80

  /// Minimum Fresnel zone clearance percentage for a "marginal" path
  public static let marginalClearanceThreshold: Double = 60

  // MARK: - Wavelength

  /// Calculates the wavelength in meters for a given frequency.
  /// - Parameter frequencyMHz: The frequency in megahertz.
  /// - Returns: The wavelength in meters.
  public static func wavelength(frequencyMHz: Double) -> Double {
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
  public static func fresnelRadius(
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
  public static func earthBulge(
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
  public static func pathLoss(distanceMeters: Double, frequencyMHz: Double) -> Double {
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
  public static func diffractionLoss(
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

  /// Fresnel-Kirchhoff parameter at or below which the path has full clearance and no knife-edge loss.
  private static let diffractionClearThresholdV: Double = -0.78

  /// Calculates diffraction loss from the Fresnel-Kirchhoff v parameter.
  ///
  /// Uses the ITU-R P.526 single-equation knife-edge diffraction model. A single
  /// expression keeps the loss continuous and monotonic across the whole obstruction
  /// range, avoiding the branch-join drift of the piecewise polynomial approximations.
  ///
  /// - Parameter vParam: The Fresnel-Kirchhoff diffraction parameter.
  /// - Returns: The diffraction loss in dB.
  private static func diffractionLossFromV(_ vParam: Double) -> Double {
    guard vParam > diffractionClearThresholdV else { return 0 }
    let shifted = vParam - 0.1
    return 6.9 + 20 * log10(sqrt(shifted * shifted + 1) + shifted)
  }

  // MARK: - Distance Calculation

  /// Calculates the great-circle distance between two coordinates using the Haversine formula.
  ///
  /// - Parameters:
  ///   - from: The starting coordinate.
  ///   - destination: The ending coordinate.
  /// - Returns: The distance in meters.
  public static func distance(from: CLLocationCoordinate2D, to destination: CLLocationCoordinate2D) -> Double {
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
  public static func analyzePath(
    elevationProfile: [ElevationSample],
    pointAHeightMeters: Double,
    pointBHeightMeters: Double,
    frequencyMHz: Double,
    refractionK: Double
  ) -> PathAnalysisResult {
    // Full-path distances are measured from A itself, so the origin is 0
    // even if the first sample carries a non-zero distance.
    analyze(
      elevationProfile: elevationProfile[...],
      startHeightMeters: pointAHeightMeters,
      endHeightMeters: pointBHeightMeters,
      frequencyMHz: frequencyMHz,
      refractionK: refractionK,
      distanceOriginMeters: 0
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
  public static func analyzePathSegment(
    elevationProfile: ArraySlice<ElevationSample>,
    startHeightMeters: Double,
    endHeightMeters: Double,
    frequencyMHz: Double,
    refractionK: Double
  ) -> PathAnalysisResult {
    analyze(
      elevationProfile: elevationProfile,
      startHeightMeters: startHeightMeters,
      endHeightMeters: endHeightMeters,
      frequencyMHz: frequencyMHz,
      refractionK: refractionK,
      distanceOriginMeters: elevationProfile.first?.distanceFromAMeters ?? 0
    )
  }

  // MARK: - Shared Analysis Core

  /// Distance margin (meters) within which a sample counts as an endpoint and is skipped.
  private static let endpointSkipMarginMeters: Double = 1

  /// Shared core for `analyzePath` and `analyzePathSegment`.
  ///
  /// `distanceOriginMeters` anchors the local distance axis: 0 for a full
  /// path, the first sample's `distanceFromAMeters` for a segment. Recorded
  /// obstruction points always keep the sample's original `distanceFromAMeters`
  /// so they stay in full-path coordinates for chart rendering.
  private static func analyze(
    elevationProfile: ArraySlice<ElevationSample>,
    startHeightMeters: Double,
    endHeightMeters: Double,
    frequencyMHz: Double,
    refractionK: Double,
    distanceOriginMeters: Double
  ) -> PathAnalysisResult {
    guard elevationProfile.count >= 2,
          let firstSample = elevationProfile.first,
          let lastSample = elevationProfile.last else {
      return emptyResult(frequencyMHz: frequencyMHz, refractionK: refractionK)
    }

    let lengthMeters = lastSample.distanceFromAMeters - distanceOriginMeters

    guard lengthMeters > 0 else {
      return emptyResult(frequencyMHz: frequencyMHz, refractionK: refractionK)
    }

    // Antenna heights above sea level
    let antennaStartHeight = firstSample.elevation + startHeightMeters
    let antennaEndHeight = lastSample.elevation + endHeightMeters

    // Calculate free-space path loss
    let fspl = pathLoss(distanceMeters: lengthMeters, frequencyMHz: frequencyMHz)

    var worstClearancePercent = Double.infinity
    var peakDiffractionLoss = 0.0
    var obstructionPoints: [ObstructionPoint] = []

    // Analyze each intermediate sample point (skip endpoints)
    for sample in elevationProfile {
      let distanceFromStart = sample.distanceFromAMeters - distanceOriginMeters
      let distanceToEnd = lengthMeters - distanceFromStart

      // Skip points at or very near the endpoints
      guard distanceFromStart > endpointSkipMarginMeters,
            distanceToEnd > endpointSkipMarginMeters else { continue }

      // Line of sight height at this point (linear interpolation)
      let fraction = distanceFromStart / lengthMeters
      let losHeight = antennaStartHeight + fraction * (antennaEndHeight - antennaStartHeight)

      // Effective terrain height including earth bulge
      let bulge = earthBulge(
        distanceToAMeters: distanceFromStart,
        distanceToBMeters: distanceToEnd,
        refractionK: refractionK
      )
      let effectiveTerrainHeight = sample.elevation + bulge

      // Calculate Fresnel zone radius at this point
      let fresnelZoneRadius = fresnelRadius(
        frequencyMHz: frequencyMHz,
        distanceToAMeters: distanceFromStart,
        distanceToBMeters: distanceToEnd
      )

      // Clearance: distance from terrain to line of sight
      let clearance = losHeight - effectiveTerrainHeight

      // Fresnel clearance percentage
      // 100% = terrain clears full first Fresnel zone
      // 0% = terrain touches line of sight
      // <0% = terrain blocks line of sight
      let clearancePercent: Double = if fresnelZoneRadius > 0 {
        (clearance / fresnelZoneRadius) * 100
      } else {
        clearance > 0 ? 100 : 0
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
          distanceToAMeters: distanceFromStart,
          distanceToBMeters: distanceToEnd,
          frequencyMHz: frequencyMHz
        )
        if diffLoss > peakDiffractionLoss {
          peakDiffractionLoss = diffLoss
        }
      }

      // Record obstruction points where clearance < marginal threshold
      if clearancePercent < marginalClearanceThreshold {
        let obstruction = ObstructionPoint(
          distanceFromAMeters: sample.distanceFromAMeters,
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

    return PathAnalysisResult(
      distanceMeters: lengthMeters,
      freeSpacePathLoss: fspl,
      peakDiffractionLoss: peakDiffractionLoss,
      totalPathLoss: fspl + peakDiffractionLoss,
      clearanceStatus: clearanceStatus(forWorstClearancePercent: worstClearancePercent),
      worstClearancePercent: worstClearancePercent,
      obstructionPoints: obstructionPoints,
      frequencyMHz: frequencyMHz,
      refractionK: refractionK
    )
  }

  /// The zeroed blocked result returned for degenerate profiles.
  private static func emptyResult(frequencyMHz: Double, refractionK: Double) -> PathAnalysisResult {
    PathAnalysisResult(
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

  /// Maps a worst-case Fresnel clearance percentage to a clearance status.
  private static func clearanceStatus(forWorstClearancePercent percent: Double) -> ClearanceStatus {
    if percent >= clearClearanceThreshold {
      .clear
    } else if percent >= marginalClearanceThreshold {
      .marginal
    } else if percent >= 0 {
      .partialObstruction
    } else {
      .blocked
    }
  }
}
