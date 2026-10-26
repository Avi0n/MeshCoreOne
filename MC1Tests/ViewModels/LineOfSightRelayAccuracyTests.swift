import CoreLocation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Line of Sight relay accuracy")
@MainActor
struct LineOfSightRelayAccuracyTests {
  @Test(arguments: [11, 80, 100])
  func `Relay keeps the ridge interior and uses the exact repeater distance`(count: Int) throws {
    let ridgeIndex = Int(0.59 * Double(count - 1))
    let profile = (0..<count).map { index in
      sample(Double(index) * 10000 / Double(count - 1), index == ridgeIndex ? 100 : 0)
    }
    let model = model(profile: profile)
    try placeRepeater(in: model, fraction: 0.59)
    model.analyzeWithRepeater()

    let result = try relayResult(model)
    #expect(abs(result.segmentAR.distanceMeters - 5900) < 1e-8)
    #expect(abs(result.segmentRB.distanceMeters - 4100) < 1e-8)
    #expect(result.segmentAR.clearanceStatus == .blocked)
    #expect(model.profileSamples.contains { $0.x == profile[ridgeIndex].distanceFromAMeters })
    #expect(model.profileSamples.last?.x == model.profileSamplesRB.first?.x)
    #expect(model.profileSamples.last?.yTerrain == model.profileSamplesRB.first?.yTerrain)
    #expect(model.profileSamples.last?.yLOS == model.profileSamplesRB.first?.yLOS)

    let reversedProfile = profile.reversed().map {
      ElevationSample(coordinate: $0.coordinate, elevation: $0.elevation, distanceFromAMeters: 10000 - $0.distanceFromAMeters)
    }
    let reversed = self.model(profile: reversedProfile)
    try placeRepeater(in: reversed, fraction: 0.41)
    reversed.analyzeWithRepeater()

    let reversedResult = try relayResult(reversed)
    #expect(result.overallStatus == reversedResult.overallStatus)
    #expect(abs(result.segmentAR.worstClearancePercent - reversedResult.segmentRB.worstClearancePercent) < 1e-7)
    #expect(abs(result.segmentRB.worstClearancePercent - reversedResult.segmentAR.worstClearancePercent) < 1e-7)
  }

  @Test
  func `Nonuniform samples interpolate by distance and share the same junction`() throws {
    let profile = [sample(0, 0), sample(1000, 100), sample(4000, 20), sample(10000, 0)]
    let model = model(profile: profile)
    try placeRepeater(in: model, fraction: 0.25)
    model.analyzeWithRepeater()

    let result = try relayResult(model)
    let coordinate = try #require(model.coordinateAt(pathFraction: 0.25))
    #expect(abs(result.segmentAR.distanceMeters - 2500) < 1e-8)
    #expect(abs(result.segmentRB.distanceMeters - 7500) < 1e-8)
    #expect(model.elevationAt(pathFraction: 0.25) == 60)
    #expect(abs(coordinate.longitude - 2500 / 6_371_000 * 180 / .pi) < 1e-10)
    #expect(model.profileSamples.map(\.x) == [0, 1000, 2500])
    #expect(model.profileSamplesRB.map(\.x) == [2500, 4000, 10000])
    #expect(model.profileSamples.last?.yTerrain == 60)
    #expect(model.profileSamplesRB.first?.yTerrain == 60)
    #expect(model.profileSamples.last?.yLOS == 70)
    #expect(model.profileSamplesRB.first?.yLOS == 70)
  }

  @Test(arguments: [20, 101])
  func `Moving to five percent replaces the previous relay result`(count: Int) throws {
    let profile = (0..<count).map { index in
      sample(Double(index) * 900 / Double(count - 1), index == count / 2 ? 100 : 0)
    }
    let model = model(profile: profile)
    try placeRepeater(in: model, fraction: 0.5)
    model.analyzeWithRepeater()
    _ = try relayResult(model)

    model.updateRepeaterPosition(pathFraction: 0.05)
    model.analyzeWithRepeater()

    if count == 20 {
      #expect(model.analysisStatus == .error(ElevationServiceError.noData.localizedDescription))
      #expect(model.profileSamples.isEmpty)
      #expect(model.profileSamplesRB.isEmpty)
    } else {
      let result = try relayResult(model)
      #expect(abs(result.segmentAR.distanceMeters - 45) < 1e-8)
      #expect(result.segmentRB.clearanceStatus == .blocked)
    }
  }

  @Test(arguments: [0.01, 0.99])
  func `Clamped repeater fraction determines both coordinate and elevation`(requestedFraction: Double) throws {
    let profile = (0...100).map { sample(Double($0) * 10, Double($0)) }
    let model = model(profile: profile)
    try placeRepeater(in: model, fraction: 0.5)

    model.updateRepeaterPosition(pathFraction: requestedFraction)

    let repeater = try #require(model.repeaterPoint)
    let expectedFraction = requestedFraction < 0.5 ? 0.05 : 0.95
    #expect(repeater.pathFraction == expectedFraction)
    #expect(abs(repeater.coordinate.longitude - expectedFraction * 1000 / 6_371_000 * 180 / .pi) < 1e-10)
    #expect(try abs(#require(repeater.groundElevation) - expectedFraction * 100) < 1e-10)
  }

  @Test
  func `Adding a repeater near an endpoint uses its clamped position`() throws {
    let profile = (0...100).map { index in sample(Double(index) * 9, index == 1 ? 100 : 0) }
    let model = model(profile: profile)
    let result = RFCalculator.analyzePath(
      elevationProfile: profile, pointAHeightMeters: 7, pointBHeightMeters: 7, frequencyMHz: 906, refractionK: 1
    )
    model.setAnalysisStatusForTesting(result)

    model.addRepeater()

    let repeater = try #require(model.repeaterPoint)
    #expect(repeater.pathFraction == 0.05)
    #expect(abs(repeater.coordinate.longitude - 45 / 6_371_000 * 180 / .pi) < 1e-10)
    #expect(repeater.groundElevation == 0)
  }

  @Test
  func `Direct analysis rejects a profile without measured interior terrain`() async {
    let profile = [sample(0, 0), sample(100_000, 0)]
    let model = self.model(profile: profile, service: FixedProfileService(profile: profile))

    model.analyze()
    await model.analysisTaskForTesting?.value

    #expect(model.analysisStatus == .error(ElevationServiceError.noData.localizedDescription))
    #expect(!model.isAnalyzing)
    #expect(model.profileSamples.isEmpty)
  }

  private func sample(_ distance: Double, _ elevation: Double) -> ElevationSample {
    ElevationSample(
      coordinate: CLLocationCoordinate2D(latitude: 0, longitude: distance / 6_371_000 * 180 / .pi),
      elevation: elevation,
      distanceFromAMeters: distance
    )
  }

  private func model(
    profile: [ElevationSample],
    service: any ElevationServiceProtocol = MockElevationService()
  ) -> LineOfSightViewModel {
    let model = LineOfSightViewModel(elevationService: service)
    model.pointA = SelectedPoint(coordinate: profile[0].coordinate, contact: nil, groundElevation: profile[0].elevation)
    model.pointB = SelectedPoint(coordinate: profile[profile.count - 1].coordinate, contact: nil, groundElevation: profile[profile.count - 1].elevation)
    model.setElevationProfileForTesting(profile)
    return model
  }

  private func placeRepeater(in model: LineOfSightViewModel, fraction: Double) throws {
    model.repeaterPoint = try RepeaterPoint(
      coordinate: #require(model.coordinateAt(pathFraction: fraction)),
      groundElevation: model.elevationAt(pathFraction: fraction),
      additionalHeight: 10,
      pathFraction: fraction
    )
  }

  private func relayResult(_ model: LineOfSightViewModel) throws -> RelayPathAnalysisResult {
    guard case let .relayResult(result) = model.analysisStatus else {
      Issue.record("Expected relay result, got \(model.analysisStatus)")
      throw MissingRelayResult()
    }
    return result
  }

  private struct MissingRelayResult: Error {}

  private actor FixedProfileService: ElevationServiceProtocol {
    let profile: [ElevationSample]

    init(profile: [ElevationSample]) {
      self.profile = profile
    }

    func fetchElevation(at coordinate: CLLocationCoordinate2D) async throws -> Double {
      0
    }

    func fetchElevations(along path: [CLLocationCoordinate2D]) async throws -> [ElevationSample] {
      profile
    }
  }
}
