import CoreLocation
@testable import MC1Services
import Testing

@Suite("RF Scientific Regressions")
struct RFScientificRegressionTests {
  private func sample(_ distance: Double, elevation: Double = 0) -> ElevationSample {
    ElevationSample(
      coordinate: CLLocationCoordinate2D(latitude: 0, longitude: distance / 6_371_000 * 180 / .pi),
      elevation: elevation,
      distanceFromAMeters: distance
    )
  }

  private func profile(distance: Double, ridgeIndex: Int? = nil, ridgeHeight: Double = 0) -> [ElevationSample] {
    (0...10).map { index in
      sample(Double(index) * distance / 10, elevation: index == ridgeIndex ? ridgeHeight : 0)
    }
  }

  private func analyze(
    _ samples: [ElevationSample],
    startHeight: Double = 7,
    endHeight: Double = 7,
    frequency: Double = 906,
    kFactor: Double = 1
  ) -> PathAnalysisResult {
    RFCalculator.analyzePath(
      elevationProfile: samples,
      pointAHeightMeters: startHeight,
      pointBHeightMeters: endHeight,
      frequencyMHz: frequency,
      refractionK: kFactor
    )
  }

  /// Reference values use c=299792458 m/s, R=6371000 m, common-datum elevations,
  /// and independent SI arithmetic; the loss tolerance allows the rounded FSPL constant.
  @Test
  func `flat ten kilometer reference`() {
    let result = analyze(profile(distance: 10000), startHeight: 50, endHeight: 50)
    #expect(result.isValid)
    #expect(result.clearanceStatus == .clear)
    #expect(abs(result.worstClearancePercent - 167.02001632263816) < 1e-9)
    #expect(abs(result.freeSpacePathLoss - 111.59034717541964) < 0.0023)
    #expect(result.peakDiffractionLoss == 0)
  }

  @Test
  func `single ridge reference`() {
    let result = analyze(profile(distance: 10000, ridgeIndex: 5, ridgeHeight: 100))
    #expect(result.isValid)
    #expect(result.clearanceStatus == .blocked)
    #expect(abs(result.worstClearancePercent + 330.16700191225306) < 1e-9)
    #expect(abs(result.peakDiffractionLoss - 26.219716530826638) < 1e-10)
  }

  @Test
  func `unequal endpoint heights reference`() {
    let result = analyze(profile(distance: 10000), startHeight: 5, endHeight: 50)
    #expect(abs(result.worstClearancePercent - 50.95688560947363) < 1e-9)
    #expect(abs(result.peakDiffractionLoss - 0.3968418683880186) < 1e-10)
  }

  @Test
  func `short and long flat path references`() {
    let short = analyze(profile(distance: 1000))
    #expect(short.clearanceStatus == .marginal)
    #expect(abs(short.worstClearancePercent - 76.74724578644971) < 1e-9)
    let long = analyze(profile(distance: 100_000), startHeight: 10, endHeight: 10)
    #expect(long.clearanceStatus == .blocked)
    #expect(abs(long.worstClearancePercent + 204.72317459077038) < 1e-9)
  }

  @Test(arguments: [
    (19.219103128499036, ClearanceStatus.partialObstruction, ClearanceStatus.marginal),
    (24.971465710598515, ClearanceStatus.marginal, ClearanceStatus.clear)
  ])
  func `threshold sides`(height: Double, below: ClearanceStatus, above: ClearanceStatus) {
    let samples = profile(distance: 10000)
    #expect(analyze(samples, startHeight: height - 1e-6, endHeight: height - 1e-6).clearanceStatus == below)
    #expect(analyze(samples, startHeight: height + 1e-6, endHeight: height + 1e-6).clearanceStatus == above)
  }

  @Test(arguments: [10.0, 1000, 10000, 100_000])
  func `reversal height and datum invariants`(distance: Double) {
    let samples = profile(distance: distance, ridgeIndex: 3, ridgeHeight: 100)
    let result = analyze(samples, startHeight: 5, endHeight: 50)
    let reversed = samples.reversed().map {
      sample(distance - $0.distanceFromAMeters, elevation: $0.elevation)
    }
    let reverseResult = analyze(reversed, startHeight: 50, endHeight: 5)
    #expect(abs(result.worstClearancePercent - reverseResult.worstClearancePercent) < 1e-7)
    #expect(abs(result.totalPathLoss - reverseResult.totalPathLoss) < 1e-9)
    let raised = analyze(samples, startHeight: 15, endHeight: 60)
    #expect(raised.worstClearancePercent >= result.worstClearancePercent)
    #expect(raised.peakDiffractionLoss <= result.peakDiffractionLoss)
    let shifted = samples.map { sample($0.distanceFromAMeters, elevation: $0.elevation - 430) }
    #expect(abs(analyze(shifted, startHeight: 5, endHeight: 50).worstClearancePercent -
        result.worstClearancePercent) < 1e-7)
  }

  @Test
  func `interior ridge within one meter is evaluated`() {
    let result = analyze([sample(0), sample(0.5, elevation: 100), sample(5), sample(10)])
    #expect(result.isValid)
    #expect(result.clearanceStatus == .blocked)
    #expect(result.worstObstructionPoint?.distanceFromAMeters == 0.5)
  }

  @Test
  func `endpoints alone do not assert clearance`() {
    let result = analyze([sample(0), sample(100_000)], startHeight: 10, endHeight: 10)
    #expect(!result.isValid)
    #expect(result.distanceMeters == 0)
    #expect(result.clearanceStatus != .clear)
  }

  @Test(arguments: [Double.infinity, Double.greatestFiniteMagnitude, Double.leastNonzeroMagnitude])
  func `invalid frequencies are rejected`(frequency: Double) {
    #expect(RFCalculator.wavelength(frequencyMHz: frequency) == 0)
    #expect(RFCalculator.fresnelRadius(frequencyMHz: frequency, distanceToAMeters: 500, distanceToBMeters: 500) == 0)
    #expect(RFCalculator.pathLoss(distanceMeters: 1000, frequencyMHz: frequency) == 0)
    #expect(!analyze(profile(distance: 1000), frequency: frequency).isValid)
  }

  @Test
  func `antipodal distance remains finite`() {
    let start = CLLocationCoordinate2D(latitude: -13.89791609602186, longitude: -174.63650050114734)
    let end = CLLocationCoordinate2D(latitude: 13.89791609602186, longitude: 5.363499498852661)
    #expect(abs(RFCalculator.distance(from: start, to: end) - 20_015_086.79602057) < 0.2)
    #expect(RFCalculator.interpolateCoordinate(from: start, to: end, fraction: 0.5) == nil)
  }

  @Test(arguments: [0.1, 0.5, 0.9])
  func `spherical interpolation is reversible and equidistant`(fraction: Double) throws {
    let start = CLLocationCoordinate2D(latitude: 70, longitude: 179.9)
    let end = CLLocationCoordinate2D(latitude: 71, longitude: -170)
    let forward = try #require(RFCalculator.interpolateCoordinate(from: start, to: end, fraction: fraction))
    let reverse = try #require(RFCalculator.interpolateCoordinate(from: end, to: start, fraction: 1 - fraction))
    #expect(RFCalculator.distance(from: forward, to: reverse) < 1e-6)
    let total = RFCalculator.distance(from: start, to: end)
    #expect(abs(RFCalculator.distance(from: start, to: forward) - fraction * total) < 1e-6)
  }

  @Test
  func `near antipodal interpolation remains defined`() throws {
    let midpoint = try #require(RFCalculator.interpolateCoordinate(
      from: .init(latitude: 0, longitude: 0), to: .init(latitude: 0, longitude: 179.999999), fraction: 0.5
    ))
    #expect(abs(midpoint.latitude) < 1e-9)
    #expect(abs(midpoint.longitude - 89.9999995) < 1e-6)
  }
}
