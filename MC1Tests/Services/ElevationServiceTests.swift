import CoreLocation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ElevationService Tests")
struct ElevationServiceTests {
  // MARK: - Sample Count Tests

  @Suite("optimalSampleCount")
  struct OptimalSampleCountTests {
    @Test
    func `Returns 20 samples for distances under 1km`() {
      #expect(ElevationService.optimalSampleCount(distanceMeters: 0) == 20)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 500) == 20)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 999) == 20)
    }

    @Test
    func `Returns 50 samples for distances 1-5km`() {
      #expect(ElevationService.optimalSampleCount(distanceMeters: 1000) == 50)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 2500) == 50)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 4999) == 50)
    }

    @Test
    func `Returns 80 samples for distances 5-20km`() {
      #expect(ElevationService.optimalSampleCount(distanceMeters: 5000) == 80)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 10000) == 80)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 19999) == 80)
    }

    @Test
    func `Returns 100 samples for distances over 20km`() {
      #expect(ElevationService.optimalSampleCount(distanceMeters: 20000) == 100)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 50000) == 100)
      #expect(ElevationService.optimalSampleCount(distanceMeters: 100_000) == 100)
    }

    @Test
    func `Sample count never exceeds 100`() {
      // Test a range of distances to ensure we never exceed 100
      let distances = [0, 100, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100_000, 1_000_000]
      for distance in distances {
        let count = ElevationService.optimalSampleCount(distanceMeters: Double(distance))
        #expect(count <= 100, "Sample count \(count) exceeds 100 for distance \(distance)m")
      }
    }
  }

  // MARK: - Sample Coordinates Tests

  @Suite("sampleCoordinates")
  struct SampleCoordinatesTests {
    private let pointA = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
    private let pointB = CLLocationCoordinate2D(latitude: 37.8049, longitude: -122.3894)

    @Test
    func `First coordinate equals pointA`() {
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 10)

      #expect(samples.first?.latitude == pointA.latitude)
      #expect(samples.first?.longitude == pointA.longitude)
    }

    @Test
    func `Last coordinate equals pointB`() {
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 10)

      #expect(samples.last?.latitude == pointB.latitude)
      #expect(samples.last?.longitude == pointB.longitude)
    }

    @Test
    func `Returns correct number of samples`() {
      for count in [2, 5, 10, 20, 50, 100] {
        let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: count)
        #expect(samples.count == count, "Expected \(count) samples, got \(samples.count)")
      }
    }

    @Test
    func `Sample count clamped to minimum of 2`() {
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 1)
      #expect(samples.count == 2)

      let zeroSamples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 0)
      #expect(zeroSamples.count == 2)
    }

    @Test
    func `Sample count clamped to maximum of 100`() {
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 150)
      #expect(samples.count == 100)
    }

    @Test
    func `Coordinates are evenly distributed`() {
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 5)

      // Calculate expected latitude/longitude step
      let latStep = (pointB.latitude - pointA.latitude) / 4
      let lonStep = (pointB.longitude - pointA.longitude) / 4

      // Check each point is at expected position
      for i in 0..<5 {
        let expectedLat = pointA.latitude + Double(i) * latStep
        let expectedLon = pointA.longitude + Double(i) * lonStep

        #expect(
          abs(samples[i].latitude - expectedLat) < 0.0001,
          "Latitude at index \(i) differs: expected \(expectedLat), got \(samples[i].latitude)"
        )
        #expect(
          abs(samples[i].longitude - expectedLon) < 0.0001,
          "Longitude at index \(i) differs: expected \(expectedLon), got \(samples[i].longitude)"
        )
      }
    }

    @Test
    func `Identical points return same coordinate repeated`() {
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointA, sampleCount: 5)

      #expect(samples.count == 5)
      for sample in samples {
        #expect(sample.latitude == pointA.latitude)
        #expect(sample.longitude == pointA.longitude)
      }
    }
  }

  // MARK: - Error Type Tests

  @Suite("ElevationServiceError")
  struct ErrorTests {
    @Test
    func `networkError has descriptive message`() {
      let underlyingError = NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "Connection failed"])
      let error = ElevationServiceError.networkError(underlyingError.localizedDescription)

      #expect(error.errorDescription?.contains("Network error") == true)
      #expect(error.errorDescription?.contains("Connection failed") == true)
    }

    @Test
    func `invalidResponse has descriptive message`() {
      let error = ElevationServiceError.invalidResponse
      #expect(error.errorDescription == "Invalid response from elevation API")
    }

    @Test
    func `apiError includes message`() {
      let error = ElevationServiceError.apiError("Rate limit exceeded")
      #expect(error.errorDescription?.contains("API error") == true)
      #expect(error.errorDescription?.contains("Rate limit exceeded") == true)
    }

    @Test
    func `noData has descriptive message`() {
      let error = ElevationServiceError.noData
      #expect(error.errorDescription == "No elevation data returned")
    }
  }

  // MARK: - Integration with RFCalculator Distance

  @Suite("Distance Integration")
  struct DistanceIntegrationTests {
    @Test
    func `Sample coordinates work with RFCalculator distance`() {
      let pointA = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
      let pointB = CLLocationCoordinate2D(latitude: 37.8049, longitude: -122.3894)

      let totalDistance = RFCalculator.distance(from: pointA, to: pointB)
      let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 5)

      // Calculate distance between each consecutive pair
      var cumulativeDistance = 0.0
      for i in 1..<samples.count {
        let stepDistance = RFCalculator.distance(from: samples[i - 1], to: samples[i])
        cumulativeDistance += stepDistance
      }

      // Cumulative distance should approximately equal total distance
      #expect(
        abs(cumulativeDistance - totalDistance) < 1.0,
        "Cumulative distance \(cumulativeDistance)m should match total distance \(totalDistance)m"
      )
    }
  }
}
