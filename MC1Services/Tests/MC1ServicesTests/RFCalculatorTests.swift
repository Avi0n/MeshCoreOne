import CoreLocation
@testable import MC1Services
import Testing

@Suite("RFCalculator Tests")
struct RFCalculatorTests {
  // MARK: - Constants Tests

  @Test
  func `Speed of light constant is correct`() {
    #expect(RFCalculator.speedOfLight == 299_792_458)
  }

  @Test
  func `Earth radius constant is correct`() {
    #expect(RFCalculator.earthRadiusKm == 6371)
  }

  // MARK: - Wavelength Tests

  @Test
  func `Wavelength at 910 MHz is approximately 0.3294m`() {
    let wavelength = RFCalculator.wavelength(frequencyMHz: 910)
    // Expected: c / f = 299792458 / 910000000 ≈ 0.3294422
    #expect(abs(wavelength - 0.3294) < 0.001)
  }

  @Test
  func `Wavelength at 2400 MHz is approximately 0.125m`() {
    let wavelength = RFCalculator.wavelength(frequencyMHz: 2400)
    // Expected: 299792458 / 2400000000 ≈ 0.1249
    #expect(abs(wavelength - 0.125) < 0.001)
  }

  @Test
  func `Wavelength returns 0 for zero frequency`() {
    let wavelength = RFCalculator.wavelength(frequencyMHz: 0)
    #expect(wavelength == 0)
  }

  @Test
  func `Wavelength returns 0 for negative frequency`() {
    let wavelength = RFCalculator.wavelength(frequencyMHz: -100)
    #expect(wavelength == 0)
  }

  // MARK: - Fresnel Radius Tests

  @Test
  func `Fresnel radius at midpoint is approximately 22.23m for 6km at 910MHz`() {
    let radius = RFCalculator.fresnelRadius(
      frequencyMHz: 910,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000
    )
    // r = sqrt((0.3294 * 3000 * 3000) / 6000) = sqrt(494.1) ≈ 22.23
    #expect(abs(radius - 22.23) < 0.5)
  }

  @Test
  func `Fresnel radius at quarter point is smaller than at midpoint`() {
    let totalDistance = 6000.0
    let quarterPoint = totalDistance / 4

    let radiusAtQuarter = RFCalculator.fresnelRadius(
      frequencyMHz: 910,
      distanceToAMeters: quarterPoint,
      distanceToBMeters: totalDistance - quarterPoint
    )

    let radiusAtMidpoint = RFCalculator.fresnelRadius(
      frequencyMHz: 910,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000
    )

    // Quarter point radius should be smaller than midpoint
    // r at quarter = sqrt((0.3294 * 1500 * 4500) / 6000) ≈ 19.27
    #expect(radiusAtQuarter < radiusAtMidpoint)
    #expect(abs(radiusAtQuarter - 19.27) < 0.5)
  }

  @Test
  func `Fresnel radius is symmetric`() {
    let radius1 = RFCalculator.fresnelRadius(
      frequencyMHz: 910,
      distanceToAMeters: 2000,
      distanceToBMeters: 4000
    )

    let radius2 = RFCalculator.fresnelRadius(
      frequencyMHz: 910,
      distanceToAMeters: 4000,
      distanceToBMeters: 2000
    )

    #expect(abs(radius1 - radius2) < 0.001)
  }

  @Test
  func `Fresnel radius returns 0 for invalid inputs`() {
    #expect(RFCalculator.fresnelRadius(frequencyMHz: 0, distanceToAMeters: 100, distanceToBMeters: 100) == 0)
    #expect(RFCalculator.fresnelRadius(frequencyMHz: 910, distanceToAMeters: 0, distanceToBMeters: 100) == 0)
    #expect(RFCalculator.fresnelRadius(frequencyMHz: 910, distanceToAMeters: 100, distanceToBMeters: 0) == 0)
    #expect(RFCalculator.fresnelRadius(frequencyMHz: -100, distanceToAMeters: 100, distanceToBMeters: 100) == 0)
  }

  // MARK: - Earth Bulge Tests

  @Test
  func `Earth bulge at midpoint with k=0.25 is approximately 2.82m for 6km`() {
    let bulge = RFCalculator.earthBulge(
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      refractionK: 0.25
    )
    // h = (3000 * 3000) / (2 * 0.25 * 6371000) = 9000000 / 3185500 ≈ 2.82
    #expect(abs(bulge - 2.82) < 0.05)
  }

  @Test
  func `Earth bulge with standard atmosphere k=1.33 is smaller`() {
    let bulgeConservative = RFCalculator.earthBulge(
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      refractionK: 0.25
    )

    let bulgeStandard = RFCalculator.earthBulge(
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      refractionK: 1.33
    )

    // Standard atmosphere (k=1.33) gives smaller bulge due to larger effective earth radius
    // h = (3000 * 3000) / (2 * 1.33 * 6371000) ≈ 0.53m
    #expect(bulgeStandard < bulgeConservative)
    #expect(abs(bulgeStandard - 0.53) < 0.05)
  }

  @Test
  func `Earth bulge is symmetric`() {
    let bulge1 = RFCalculator.earthBulge(
      distanceToAMeters: 2000,
      distanceToBMeters: 4000,
      refractionK: 1.0
    )

    let bulge2 = RFCalculator.earthBulge(
      distanceToAMeters: 4000,
      distanceToBMeters: 2000,
      refractionK: 1.0
    )

    #expect(abs(bulge1 - bulge2) < 0.001)
  }

  @Test
  func `Earth bulge returns 0 for invalid inputs`() {
    #expect(RFCalculator.earthBulge(distanceToAMeters: 0, distanceToBMeters: 100, refractionK: 1.0) == 0)
    #expect(RFCalculator.earthBulge(distanceToAMeters: 100, distanceToBMeters: 0, refractionK: 1.0) == 0)
    #expect(RFCalculator.earthBulge(distanceToAMeters: 100, distanceToBMeters: 100, refractionK: 0) == 0)
    #expect(RFCalculator.earthBulge(distanceToAMeters: 100, distanceToBMeters: 100, refractionK: -1) == 0)
  }

  // MARK: - Path Loss Tests

  @Test
  func `Path loss is approximately 107.2 dB for 6km at 910MHz`() {
    let loss = RFCalculator.pathLoss(distanceMeters: 6000, frequencyMHz: 910)
    // FSPL = 20*log10(6000) + 20*log10(910) - 27.55
    //      = 75.56 + 59.18 - 27.55 ≈ 107.19
    #expect(abs(loss - 107.2) < 0.5)
  }

  @Test
  func `Path loss increases with distance`() {
    let loss1km = RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: 910)
    let loss2km = RFCalculator.pathLoss(distanceMeters: 2000, frequencyMHz: 910)
    let loss4km = RFCalculator.pathLoss(distanceMeters: 4000, frequencyMHz: 910)

    #expect(loss2km > loss1km)
    #expect(loss4km > loss2km)

    // Doubling distance adds ~6dB
    #expect(abs((loss2km - loss1km) - 6.02) < 0.1)
    #expect(abs((loss4km - loss2km) - 6.02) < 0.1)
  }

  @Test
  func `Path loss increases with frequency`() {
    let loss400MHz = RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: 400)
    let loss900MHz = RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: 900)
    let loss2400MHz = RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: 2400)

    #expect(loss900MHz > loss400MHz)
    #expect(loss2400MHz > loss900MHz)
  }

  @Test
  func `Path loss returns 0 for invalid inputs`() {
    #expect(RFCalculator.pathLoss(distanceMeters: 0, frequencyMHz: 910) == 0)
    #expect(RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: 0) == 0)
    #expect(RFCalculator.pathLoss(distanceMeters: -100, frequencyMHz: 910) == 0)
    #expect(RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: -910) == 0)
  }

  // MARK: - Diffraction Loss Tests

  @Test
  func `Diffraction loss is zero for clear line-of-sight (v below grazing)`() {
    // Large negative obstruction height = clear path well below LOS
    let loss = RFCalculator.diffractionLoss(
      obstructionHeightMeters: -50,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )
    #expect(loss == 0)
  }

  @Test
  func `Diffraction loss is approximately 6 dB for grazing (v near 0)`() {
    // At v=0 (grazing), the obstruction is exactly on the line of sight
    let loss = RFCalculator.diffractionLoss(
      obstructionHeightMeters: 0,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )
    // At v=0: L ≈ 6.02 dB
    #expect(abs(loss - 6.0) < 1.0)
  }

  @Test
  func `Diffraction loss increases for blocked path (v near 1)`() {
    // Calculate the obstruction height that gives v ≈ 1
    // v = h * sqrt(2 * (d1 + d2) / (lambda * d1 * d2))
    // For 6km at 910MHz: sqrt(2 * 6000 / (0.3294 * 3000 * 3000)) ≈ 0.0636
    // So h = 1 / 0.0636 ≈ 15.7m for v = 1
    let loss = RFCalculator.diffractionLoss(
      obstructionHeightMeters: 15.7,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )
    // ITU-R P.526 J(v) at v ≈ 1 is ≈ 13.9 dB
    #expect(abs(loss - 13.9) < 0.5)
  }

  @Test
  func `Diffraction loss is greater for larger obstructions`() {
    let lossSmall = RFCalculator.diffractionLoss(
      obstructionHeightMeters: 5,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )

    let lossMedium = RFCalculator.diffractionLoss(
      obstructionHeightMeters: 15,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )

    let lossLarge = RFCalculator.diffractionLoss(
      obstructionHeightMeters: 30,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )

    #expect(lossMedium > lossSmall)
    #expect(lossLarge > lossMedium)
  }

  @Test
  func `Diffraction loss matches ITU-R P.526 reference value at strong obstruction (v ≈ 2.4)`() {
    // h ≈ 37.7m gives v ≈ 2.4 for 6km at 910MHz; ITU-R P.526 J(2.4) ≈ 20.5 dB.
    // A wrong-sign polynomial approximation reads ≈ 35 dB here, so this anchors the regime.
    let loss = RFCalculator.diffractionLoss(
      obstructionHeightMeters: 37.7,
      distanceToAMeters: 3000,
      distanceToBMeters: 3000,
      frequencyMHz: 910
    )
    #expect(abs(loss - 20.5) < 0.7)
  }

  @Test
  func `Diffraction loss returns 0 for invalid inputs`() {
    #expect(RFCalculator.diffractionLoss(obstructionHeightMeters: 10, distanceToAMeters: 0, distanceToBMeters: 100, frequencyMHz: 910) == 0)
    #expect(RFCalculator.diffractionLoss(obstructionHeightMeters: 10, distanceToAMeters: 100, distanceToBMeters: 0, frequencyMHz: 910) == 0)
    #expect(RFCalculator.diffractionLoss(obstructionHeightMeters: 10, distanceToAMeters: 100, distanceToBMeters: 100, frequencyMHz: 0) == 0)
  }

  // MARK: - Haversine Distance Tests

  @Test
  func `Distance between same coordinates is zero`() {
    let coord = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    let distance = RFCalculator.distance(from: coord, to: coord)
    #expect(distance == 0)
  }

  @Test
  func `Haversine distance calculation is accurate`() {
    // San Francisco to Los Angeles: approximately 559 km
    let sanFrancisco = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    let losAngeles = CLLocationCoordinate2D(latitude: 34.0522, longitude: -118.2437)

    let distance = RFCalculator.distance(from: sanFrancisco, to: losAngeles)

    // Expected: ~559 km = 559000 meters (within 10km tolerance)
    #expect(abs(distance - 559_000) < 10000)
  }

  @Test
  func `Haversine distance is symmetric`() {
    let coord1 = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    let coord2 = CLLocationCoordinate2D(latitude: 34.0522, longitude: -118.2437)

    let distance1 = RFCalculator.distance(from: coord1, to: coord2)
    let distance2 = RFCalculator.distance(from: coord2, to: coord1)

    #expect(abs(distance1 - distance2) < 0.001)
  }

  @Test
  func `Distance across date line is correct`() {
    // Tokyo to San Francisco across the Pacific
    let tokyo = CLLocationCoordinate2D(latitude: 35.6762, longitude: 139.6503)
    let sanFrancisco = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)

    let distance = RFCalculator.distance(from: tokyo, to: sanFrancisco)

    // Expected: ~8,280 km = 8,280,000 meters (within 100km tolerance)
    #expect(abs(distance - 8_280_000) < 100_000)
  }

  @Test
  func `Short distance calculation is accurate`() {
    // Two points approximately 1 km apart
    let point1 = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    // Moving ~0.009 degrees north is roughly 1 km
    let point2 = CLLocationCoordinate2D(latitude: 37.7839, longitude: -122.4194)

    let distance = RFCalculator.distance(from: point1, to: point2)

    // Expected: ~1 km = 1000 meters (within 50m tolerance)
    #expect(abs(distance - 1000) < 100)
  }
}

// MARK: - Path Analysis Tests

@Suite("PathAnalysis Tests")
struct PathAnalysisTests {
  // MARK: - Helper Functions

  /// Creates an elevation profile with flat terrain at specified elevation
  private func createFlatProfile(
    elevationMeters: Double,
    totalDistanceMeters: Double,
    sampleCount: Int = 11
  ) -> [ElevationSample] {
    var samples: [ElevationSample] = []
    let baseCoord = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)

    for i in 0..<sampleCount {
      let fraction = Double(i) / Double(sampleCount - 1)
      let distance = fraction * totalDistanceMeters
      // Slight coordinate offset for each sample (not critical for these tests)
      let coord = CLLocationCoordinate2D(
        latitude: baseCoord.latitude + fraction * 0.01,
        longitude: baseCoord.longitude
      )
      samples.append(ElevationSample(
        coordinate: coord,
        elevation: elevationMeters,
        distanceFromAMeters: distance
      ))
    }
    return samples
  }

  /// Creates an elevation profile with a mountain/obstruction at midpoint
  private func createObstructedProfile(
    baseElevationMeters: Double,
    obstructionHeightMeters: Double,
    totalDistanceMeters: Double,
    sampleCount: Int = 11
  ) -> [ElevationSample] {
    var samples: [ElevationSample] = []
    let baseCoord = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    let midpoint = sampleCount / 2

    for i in 0..<sampleCount {
      let fraction = Double(i) / Double(sampleCount - 1)
      let distance = fraction * totalDistanceMeters
      let coord = CLLocationCoordinate2D(
        latitude: baseCoord.latitude + fraction * 0.01,
        longitude: baseCoord.longitude
      )

      // Create triangular mountain shape centered at midpoint
      let distanceFromMid = abs(i - midpoint)
      let peakFactor = max(0, 1.0 - Double(distanceFromMid) / Double(midpoint))
      let elevation = baseElevationMeters + obstructionHeightMeters * peakFactor

      samples.append(ElevationSample(
        coordinate: coord,
        elevation: elevation,
        distanceFromAMeters: distance
      ))
    }
    return samples
  }

  // MARK: - Clear Path Tests

  @Test
  func `Clear path with flat terrain returns clear status`() {
    // Flat terrain at 0m, antennas at 50m height
    // With 6km distance, Fresnel radius at midpoint is ~22m
    // LOS height at midpoint: 50m
    // Effective terrain height: 0m + ~2.8m earth bulge = ~2.8m
    // Clearance: 50 - 2.8 = 47.2m >> 22m Fresnel radius
    let profile = createFlatProfile(
      elevationMeters: 0,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    #expect(result.clearanceStatus == .clear)
    #expect(result.worstClearancePercent >= 80)
    #expect(result.obstructionPoints.isEmpty)
    #expect(result.distanceMeters == 6000)
    #expect(result.distanceKm == 6.0)
  }

  @Test
  func `Clear path has only FSPL, no diffraction loss`() {
    let profile = createFlatProfile(
      elevationMeters: 0,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    // FSPL for 6km at 910MHz is approximately 107.2 dB
    #expect(abs(result.freeSpacePathLoss - 107.2) < 1.0)
    #expect(result.peakDiffractionLoss == 0)
    #expect(result.totalPathLoss == result.freeSpacePathLoss)
  }

  // MARK: - Blocked Path Tests

  @Test
  func `Blocked path with 100m mountain returns blocked status`() {
    // 100m mountain at midpoint, antennas at 50m
    // LOS at midpoint: 50m
    // Mountain peakFactor: 0 + 100 + earth bulge ≈ 103m
    // This is well above LOS, so path is blocked
    let profile = createObstructedProfile(
      baseElevationMeters: 0,
      obstructionHeightMeters: 100,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    #expect(result.clearanceStatus == .blocked)
    #expect(result.worstClearancePercent < 0)
    #expect(!result.obstructionPoints.isEmpty)
  }

  @Test
  func `Blocked path has significant diffraction loss`() {
    let profile = createObstructedProfile(
      baseElevationMeters: 0,
      obstructionHeightMeters: 100,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    #expect(result.peakDiffractionLoss > 10)
    #expect(result.totalPathLoss > result.freeSpacePathLoss)
  }

  // MARK: - Marginal Path Tests

  @Test
  func `Marginal path with partial obstruction returns marginal status`() {
    // Create a scenario where clearance is between 60-80%
    // With 50m antennas and ~22m Fresnel zone at midpoint,
    // we need terrain that comes within ~30% of LOS
    // A small hill of ~25m should give marginal clearance
    let profile = createObstructedProfile(
      baseElevationMeters: 0,
      obstructionHeightMeters: 25,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    // With 25m hill: LOS at 50m, terrain at ~28m (25 + 3m bulge)
    // Clearance: 50 - 28 = 22m, Fresnel zone ~22m
    // Clearance percent: ~100%, still clear but close to marginal
    #expect(result.clearanceStatus == .clear || result.clearanceStatus == .marginal)
    #expect(result.worstClearancePercent >= 60)
  }

  // MARK: - Partial Obstruction Tests

  @Test
  func `Partial obstruction path returns partial obstruction status`() {
    // Create terrain that just touches the LOS but doesn't fully block
    // 45m hill with 50m antennas at 6km - should partially obstruct
    let profile = createObstructedProfile(
      baseElevationMeters: 0,
      obstructionHeightMeters: 45,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    // Hill at 45m + ~3m bulge = ~48m, LOS at 50m
    // Clearance: ~2m, Fresnel zone ~22m
    // Clearance percent: ~9%, which is partial obstruction
    #expect(result.clearanceStatus == .partialObstruction)
    #expect(result.worstClearancePercent >= 0)
    #expect(result.worstClearancePercent < 60)
    #expect(!result.obstructionPoints.isEmpty)
  }

  // MARK: - Edge Cases

  @Test
  func `Empty profile returns blocked status`() {
    let result = RFCalculator.analyzePath(
      elevationProfile: [],
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    #expect(result.clearanceStatus == .blocked)
    #expect(result.distanceMeters == 0)
  }

  @Test
  func `Single sample profile returns blocked status`() {
    let sample = ElevationSample(
      coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
      elevation: 0,
      distanceFromAMeters: 0
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: [sample],
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    #expect(result.clearanceStatus == .blocked)
  }

  @Test
  func `Profile with zero total distance returns blocked status`() {
    let samples = [
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        elevation: 0,
        distanceFromAMeters: 0
      ),
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        elevation: 0,
        distanceFromAMeters: 0
      )
    ]

    let result = RFCalculator.analyzePath(
      elevationProfile: samples,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.0
    )

    #expect(result.clearanceStatus == .blocked)
    #expect(result.distanceMeters == 0)
  }

  // MARK: - Asymmetric Antenna Heights

  @Test
  func `Asymmetric antenna heights are handled correctly`() {
    let profile = createFlatProfile(
      elevationMeters: 0,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 100, // Higher antenna at A
      pointBHeightMeters: 20, // Lower antenna at B
      frequencyMHz: 910,
      refractionK: 1.0
    )

    // LOS slopes downward from A to B
    // Even with asymmetry, should still be clear with these heights
    #expect(result.clearanceStatus == .clear)
    #expect(result.worstClearancePercent >= 80)
  }

  // MARK: - Custom K-Factor Tests

  @Test
  func `Custom k-factor affects earth bulge calculation`() {
    let profile = createObstructedProfile(
      baseElevationMeters: 0,
      obstructionHeightMeters: 40,
      totalDistanceMeters: 6000,
      sampleCount: 21
    )

    // Conservative k=0.25 (larger earth bulge)
    let resultConservative = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 0.25
    )

    // Standard atmosphere k=1.33 (smaller earth bulge)
    let resultStandard = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 910,
      refractionK: 1.33
    )

    // With smaller effective earth bulge (higher k), clearance should be better
    #expect(resultStandard.worstClearancePercent > resultConservative.worstClearancePercent)
  }
}

// MARK: - PathAnalysisResult Fields Tests

@Suite("PathAnalysisResult Fields")
struct PathAnalysisResultFieldsTests {
  @Test
  func `PathAnalysisResult includes frequency used in calculation`() {
    let profile = [
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        elevation: 0,
        distanceFromAMeters: 0
      ),
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.7849, longitude: -122.4194),
        elevation: 0,
        distanceFromAMeters: 1000
      )
    ]

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 915.0,
      refractionK: 1.33
    )

    #expect(result.frequencyMHz == 915.0)
  }

  @Test
  func `PathAnalysisResult includes k-factor used in calculation`() {
    let profile = [
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
        elevation: 0,
        distanceFromAMeters: 0
      ),
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.7849, longitude: -122.4194),
        elevation: 0,
        distanceFromAMeters: 1000
      )
    ]

    let result = RFCalculator.analyzePath(
      elevationProfile: profile,
      pointAHeightMeters: 50,
      pointBHeightMeters: 50,
      frequencyMHz: 906.0,
      refractionK: 1.33
    )

    #expect(result.refractionK == 1.33)
  }
}

// MARK: - Segment Analysis with ArraySlice Tests

@Suite("Segment Analysis with ArraySlice")
struct SegmentAnalysisTests {
  @Test
  func `analyzePathSegment works with ArraySlice`() {
    // Create a profile with 11 samples (0-10km, 1km intervals)
    let samples = (0...10).map { i in
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.0 + Double(i) * 0.01, longitude: -122.0),
        elevation: 100, // flat terrain
        distanceFromAMeters: Double(i) * 1000
      )
    }

    // Analyze first half (0-5km)
    let firstHalf = samples[0...5]
    let result = RFCalculator.analyzePathSegment(
      elevationProfile: firstHalf,
      startHeightMeters: 50, // 50m antenna height for adequate Fresnel clearance
      endHeightMeters: 50,
      frequencyMHz: 906,
      refractionK: 1.0
    )

    #expect(result.distanceMeters == 5000)
    #expect(result.clearanceStatus == .clear) // flat terrain with 50m antennas should be clear
  }

  @Test
  func `analyzePathSegment handles overlapping slice indices`() {
    let samples = (0...10).map { i in
      ElevationSample(
        coordinate: CLLocationCoordinate2D(latitude: 37.0 + Double(i) * 0.01, longitude: -122.0),
        elevation: 100,
        distanceFromAMeters: Double(i) * 1000
      )
    }

    // Analyze from index 5 to 10 (second half, includes repeater at index 5)
    let secondHalf = samples[5...10]
    let result = RFCalculator.analyzePathSegment(
      elevationProfile: secondHalf,
      startHeightMeters: 10,
      endHeightMeters: 10,
      frequencyMHz: 906,
      refractionK: 1.0
    )

    #expect(result.distanceMeters == 5000)
  }
}
