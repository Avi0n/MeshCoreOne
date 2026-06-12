import CoreLocation
import Testing

@testable import MC1
@testable import MC1Services

@Suite("ElevationService Tests")
struct ElevationServiceTests {

    // MARK: - Sample Count Tests

    @Suite("optimalSampleCount")
    struct OptimalSampleCountTests {

        @Test("Returns 20 samples for distances under 1km")
        func sampleCountUnder1km() {
            #expect(ElevationService.optimalSampleCount(distanceMeters: 0) == 20)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 500) == 20)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 999) == 20)
        }

        @Test("Returns 50 samples for distances 1-5km")
        func sampleCount1to5km() {
            #expect(ElevationService.optimalSampleCount(distanceMeters: 1000) == 50)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 2500) == 50)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 4999) == 50)
        }

        @Test("Returns 80 samples for distances 5-20km")
        func sampleCount5to20km() {
            #expect(ElevationService.optimalSampleCount(distanceMeters: 5000) == 80)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 10000) == 80)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 19999) == 80)
        }

        @Test("Returns 100 samples for distances over 20km")
        func sampleCountOver20km() {
            #expect(ElevationService.optimalSampleCount(distanceMeters: 20000) == 100)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 50000) == 100)
            #expect(ElevationService.optimalSampleCount(distanceMeters: 100000) == 100)
        }

        @Test("Sample count never exceeds 100")
        func sampleCountNeverExceeds100() {
            // Test a range of distances to ensure we never exceed 100
            let distances = [0, 100, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 1_000_000]
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

        @Test("First coordinate equals pointA")
        func firstCoordinateIsPointA() {
            let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 10)

            #expect(samples.first?.latitude == pointA.latitude)
            #expect(samples.first?.longitude == pointA.longitude)
        }

        @Test("Last coordinate equals pointB")
        func lastCoordinateIsPointB() {
            let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 10)

            #expect(samples.last?.latitude == pointB.latitude)
            #expect(samples.last?.longitude == pointB.longitude)
        }

        @Test("Returns correct number of samples")
        func correctSampleCount() {
            for count in [2, 5, 10, 20, 50, 100] {
                let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: count)
                #expect(samples.count == count, "Expected \(count) samples, got \(samples.count)")
            }
        }

        @Test("Sample count clamped to minimum of 2")
        func minimumSampleCount() {
            let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 1)
            #expect(samples.count == 2)

            let zeroSamples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 0)
            #expect(zeroSamples.count == 2)
        }

        @Test("Sample count clamped to maximum of 100")
        func maximumSampleCount() {
            let samples = ElevationService.sampleCoordinates(from: pointA, to: pointB, sampleCount: 150)
            #expect(samples.count == 100)
        }

        @Test("Coordinates are evenly distributed")
        func evenlyDistributed() {
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

        @Test("Identical points return same coordinate repeated")
        func identicalPoints() {
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

        @Test("networkError has descriptive message")
        func networkErrorDescription() {
            let underlyingError = NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "Connection failed"])
            let error = ElevationServiceError.networkError(underlyingError.localizedDescription)

            #expect(error.errorDescription?.contains("Network error") == true)
            #expect(error.errorDescription?.contains("Connection failed") == true)
        }

        @Test("invalidResponse has descriptive message")
        func invalidResponseDescription() {
            let error = ElevationServiceError.invalidResponse
            #expect(error.errorDescription == "Invalid response from elevation API")
        }

        @Test("apiError includes message")
        func apiErrorDescription() {
            let error = ElevationServiceError.apiError("Rate limit exceeded")
            #expect(error.errorDescription?.contains("API error") == true)
            #expect(error.errorDescription?.contains("Rate limit exceeded") == true)
        }

        @Test("noData has descriptive message")
        func noDataDescription() {
            let error = ElevationServiceError.noData
            #expect(error.errorDescription == "No elevation data returned")
        }
    }

    // MARK: - Integration with RFCalculator Distance

    @Suite("Distance Integration")
    struct DistanceIntegrationTests {

        @Test("Sample coordinates work with RFCalculator distance")
        func sampleCoordinatesDistanceIntegration() {
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
