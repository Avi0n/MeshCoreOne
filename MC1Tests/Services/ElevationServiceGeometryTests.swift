import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import os
import Testing

@Suite("Elevation profile geometry and provider boundaries")
struct ElevationServiceGeometryTests {
  @Test
  func `Date line profile follows the short arc and detects curvature obstruction`() throws {
    let start = CLLocationCoordinate2D(latitude: 0, longitude: 179.9)
    let end = CLLocationCoordinate2D(latitude: 0, longitude: -179.9)
    let coordinates = ElevationService.sampleCoordinates(from: start, to: end, sampleCount: 100)
    #expect(coordinates.count == 100)
    let profile = coordinates.map { coordinate in
      ElevationSample(coordinate: coordinate, elevation: 0,
                      distanceFromAMeters: RFCalculator.distance(from: start, to: coordinate))
    }
    let length = try #require(profile.last?.distanceFromAMeters)
    #expect(abs(length - 22238.985328912) < 0.000001)
    for pair in zip(profile, profile.dropFirst()) {
      #expect(pair.0.distanceFromAMeters < pair.1.distanceFromAMeters)
      #expect(abs(pair.1.distanceFromAMeters - pair.0.distanceFromAMeters - length / 99) < 0.000001)
    }
    let result = RFCalculator.analyzePath(
      elevationProfile: profile, pointAHeightMeters: 7, pointBHeightMeters: 7,
      frequencyMHz: 906, refractionK: 1
    )
    #expect(result.clearanceStatus == .blocked)
    #expect(result.worstClearancePercent < 0)
  }

  @Test
  func `Polar route reaches the spherical midpoint and reverses consistently`() throws {
    let start = CLLocationCoordinate2D(latitude: 89.9, longitude: -90)
    let end = CLLocationCoordinate2D(latitude: 89.9, longitude: 90)
    let coordinates = ElevationService.sampleCoordinates(from: start, to: end, sampleCount: 3)
    let reversed = ElevationService.sampleCoordinates(from: end, to: start, sampleCount: 3)
    #expect(coordinates.count == 3)
    let midpoint = try #require(coordinates.dropFirst().first)
    #expect(abs(midpoint.latitude - 90) < 0.000000001)
    for pair in zip(coordinates, reversed.reversed()) {
      #expect(RFCalculator.distance(from: pair.0, to: pair.1) < 0.000001)
    }
  }

  @Test(arguments: [2, 20, 100])
  func `Ambiguous antipodal paths have no generated terrain route`(count: Int) {
    let coordinates = ElevationService.sampleCoordinates(
      from: .init(latitude: 0, longitude: 0), to: .init(latitude: 0, longitude: 180), sampleCount: count
    )
    #expect(coordinates.isEmpty)
  }

  @Test(arguments: [180.0, -180.0, 179.9999999])
  func `Elevation requests normalize the rounded positive date line`(longitude: Double) async throws {
    let fixtureID = UUID().uuidString
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ElevationURLProtocol.self]
    configuration.httpAdditionalHeaders = [ElevationURLProtocol.fixtureHeader: fixtureID]
    let session = URLSession(configuration: configuration)
    defer {
      session.invalidateAndCancel()
      ElevationURLProtocol.requests.withLock { $0[fixtureID] = nil }
    }
    let service = ElevationService(session: session)
    let elevation = try await service.fetchElevation(at: .init(latitude: 0, longitude: longitude))
    #expect(elevation == 0)
    let url = try #require(ElevationURLProtocol.requests.withLock { $0[fixtureID] })
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    #expect(components.queryItems?.first(where: { $0.name == "longitude" })?.value == "-180.000000")
  }

  private final class ElevationURLProtocol: URLProtocol {
    static let fixtureHeader = "X-Elevation-Test-Fixture"
    static let requests = OSAllocatedUnfairLock(initialState: [String: URL]())

    override static func canInit(with request: URLRequest) -> Bool {
      true
    }

    override static func canonicalRequest(for request: URLRequest) -> URLRequest {
      request
    }

    override func startLoading() {
      guard let url = request.url,
            let id = request.value(forHTTPHeaderField: Self.fixtureHeader),
            let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil) else { return }
      Self.requests.withLock { $0[id] = url }
      client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
      client?.urlProtocol(self, didLoad: Data("{\"elevation\":[0]}".utf8))
      client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
  }
}
