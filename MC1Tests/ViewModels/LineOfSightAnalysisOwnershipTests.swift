import CoreLocation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Line of Sight analysis ownership", .timeLimit(.minutes(1)))
@MainActor
struct LineOfSightAnalysisOwnershipTests {
  @Test(arguments: [false, true])
  func `First profile request restarts with committed RF inputs`(deviceSeed: Bool) async {
    let service = GatedElevationService()
    let model = model(service: service)
    model.analyze()
    let previous = model.analysisTaskForTesting
    await service.waitForRequests(1)

    var intermediate: Task<Void, Never>?
    if deviceSeed {
      model.configure(dataStore: { nil }, radioID: { nil }, deviceFrequencyKHz: 915_000)
    } else {
      model.refractionK = 4.0 / 3.0
      intermediate = model.analysisTaskForTesting
      await service.waitForRequests(2)
      model.frequencyMHz = 433
      model.commitFrequencyChange()
    }
    let current = model.analysisTaskForTesting
    let requestCount = deviceSeed ? 2 : 3
    await service.waitForRequests(requestCount)
    await service.release(Array(0..<requestCount))
    await previous?.value
    await intermediate?.value
    await current?.value

    guard case let .result(result) = model.analysisStatus else {
      Issue.record("Expected current direct result, got \(model.analysisStatus)")
      return
    }
    #expect(result.frequencyMHz == (deviceSeed ? 915 : 433))
    #expect(result.refractionK == (deviceSeed ? 1 : 4.0 / 3.0))
    #expect(!model.isAnalyzing)
  }

  @Test(arguments: [false, true])
  func `Relocation rejects the old route result cache and failure`(oldRequestFails: Bool) async {
    let service = GatedElevationService()
    let model = model(service: service)
    model.setRepeaterOffPath(coordinate: .init(latitude: 0.05, longitude: 0.05))
    model.analyzeWithRepeater()
    let previous = model.analysisTaskForTesting
    await service.waitForRequests(2)

    let moved = CLLocationCoordinate2D(latitude: 0.1, longitude: 0.05)
    model.setRepeaterOffPath(coordinate: moved)
    model.clearAnalysisResults()
    model.analyzeWithRepeater()
    let current = model.analysisTaskForTesting
    await service.waitForRequests(4)
    await service.release([0, 1], fail: oldRequestFails)
    await previous?.value

    #expect(model.analysisStatus == .idle)
    #expect(model.isAnalyzing)
    #expect(model.elevationProfileAR.isEmpty)
    #expect(model.elevationProfileRB.isEmpty)
    #expect(model.profileSamples.isEmpty)
    #expect(model.profileSamplesRB.isEmpty)

    await service.release([2, 3])
    await current?.value

    guard case let .relayResult(result) = model.analysisStatus else {
      Issue.record("Expected relocated relay result, got \(model.analysisStatus)")
      return
    }
    #expect(abs(result.segmentAR.distanceMeters - 12431.969474184857) < 1e-6)
    #expect(model.elevationProfileAR.last?.coordinate.latitude == moved.latitude)
    #expect(model.elevationProfileRB.first?.coordinate.latitude == moved.latitude)
    #expect(!model.isAnalyzing)
  }

  @Test
  func `Repeater height edit revokes the old analysis before reanalysis`() async {
    let service = GatedElevationService()
    let model = model(service: service)
    model.setRepeaterOffPath(coordinate: .init(latitude: 0.05, longitude: 0.05))
    model.analyzeWithRepeater()
    let previous = model.analysisTaskForTesting
    await service.waitForRequests(2)

    model.updateRepeaterHeight(meters: 25)
    await service.release([0, 1])
    await previous?.value

    #expect(model.analysisStatus == .idle)
    #expect(!model.isAnalyzing)
    #expect(model.elevationProfileAR.isEmpty)
    #expect(model.elevationProfileRB.isEmpty)

    model.analyzeWithRepeater()
    let current = model.analysisTaskForTesting
    await service.waitForRequests(4)
    await service.release([2, 3])
    await current?.value
    #expect(model.profileSamples.last?.yLOS == 25)
    #expect(model.profileSamplesRB.first?.yLOS == 25)
  }

  @Test
  func `Cached relay analysis supersedes a pending direct request`() async {
    let service = GatedElevationService()
    let model = model(service: service)
    model.analyze()
    let previous = model.analysisTaskForTesting
    await service.waitForRequests(1)
    model.setElevationProfileForTesting(flatProfile())
    model.repeaterPoint = RepeaterPoint(coordinate: .init(latitude: 0, longitude: 0.05), pathFraction: 0.5)

    model.analyzeWithRepeater()
    let expectedStatus = model.analysisStatus
    let expectedDistances = model.profileSamples.map(\.x)
    await service.release([0])
    await previous?.value

    guard case .relayResult = model.analysisStatus else {
      Issue.record("Expected cached relay result, got \(model.analysisStatus)")
      return
    }
    #expect(model.analysisStatus == expectedStatus)
    #expect(model.profileSamples.map(\.x) == expectedDistances)
    #expect(!model.isAnalyzing)
  }

  private func model(service: any ElevationServiceProtocol) -> LineOfSightViewModel {
    let model = LineOfSightViewModel(elevationService: service)
    model.pointA = SelectedPoint(coordinate: .init(latitude: 0, longitude: 0), contact: nil, groundElevation: 0)
    model.pointB = SelectedPoint(coordinate: .init(latitude: 0, longitude: 0.1), contact: nil, groundElevation: 0)
    return model
  }

  private func flatProfile() -> [ElevationSample] {
    (0...10).map { index in
      ElevationSample(coordinate: .init(latitude: 0, longitude: Double(index) / 100), elevation: 0,
                      distanceFromAMeters: Double(index) * 1111.9492664455875)
    }
  }

  /// Requests remain pending after cancellation so tests can verify rejection of stale completions.
  private actor GatedElevationService: ElevationServiceProtocol {
    private struct Request {
      let path: [CLLocationCoordinate2D]
      let continuation: CheckedContinuation<[ElevationSample], any Error>
    }

    private var requestCount = 0
    private var pending: [Int: Request] = [:]
    private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []

    func fetchElevation(at coordinate: CLLocationCoordinate2D) async throws -> Double {
      0
    }

    func fetchElevations(along path: [CLLocationCoordinate2D]) async throws -> [ElevationSample] {
      let id = requestCount
      requestCount += 1
      return try await withCheckedThrowingContinuation { continuation in
        pending[id] = Request(path: path, continuation: continuation)
        let ready = waiters.filter { $0.0 <= requestCount }
        waiters.removeAll { $0.0 <= requestCount }
        ready.forEach { $0.1.resume() }
      }
    }

    func waitForRequests(_ count: Int) async {
      if requestCount >= count { return }
      await withCheckedContinuation { waiters.append((count, $0)) }
    }

    func release(_ ids: [Int], fail: Bool = false) {
      for id in ids {
        guard let request = pending.removeValue(forKey: id) else { continue }
        if fail {
          request.continuation.resume(throwing: ElevationServiceError.noData)
        } else {
          let start = request.path[0]
          request.continuation.resume(returning: request.path.map {
            ElevationSample(coordinate: $0, elevation: 0, distanceFromAMeters: RFCalculator.distance(from: start, to: $0))
          })
        }
      }
    }
  }
}
