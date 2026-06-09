import Testing
import CoreLocation
@testable import MC1

@Suite("ChatCoordinateDetector Tests")
struct ChatCoordinateDetectorTests {

    @Test("A valid decimal pair is detected")
    func validPairDetected() {
        let coord = ChatCoordinateDetector.firstCoordinate(in: "Meet at 37.334900, -122.009020 tonight")
        #expect(coord != nil)
        #expect(abs((coord?.latitude ?? 0) - 37.3349) < 0.000001)
        #expect(abs((coord?.longitude ?? 0) - (-122.00902)) < 0.000001)
    }

    @Test("An integer pair is not detected")
    func integerPairRejected() {
        #expect(ChatCoordinateDetector.firstCoordinate(in: "ratio is 3, 4 today") == nil)
    }

    @Test("An out-of-range pair is rejected by the clamp, not the regex")
    func outOfRangeRejected() {
        // `200.0, 400.0` passes the \d{1,3} regex; only the -90...90 / -180...180
        // clamp rejects it. This is the sole validity gate for the thumbnail path.
        #expect(ChatCoordinateDetector.firstCoordinate(in: "bad 200.0, 400.0 coord") == nil)
    }

    @Test("A three-number decimal list is treated as a list, not a coordinate")
    func decimalListRejected() {
        #expect(ChatCoordinateDetector.firstCoordinate(in: "values 1.0, 2.0, 3.0 here") == nil)
    }

    @Test("A version-like string is not detected")
    func versionLikeRejected() {
        #expect(ChatCoordinateDetector.firstCoordinate(in: "v1.2, 3.4 release") == nil)
    }

    @Test("firstCoordinate returns nil when there is no coordinate")
    func noneReturnsNil() {
        #expect(ChatCoordinateDetector.firstCoordinate(in: "no coordinates here") == nil)
    }

    @Test("firstCoordinate returns the first of several coordinates")
    func firstOfMany() {
        let coord = ChatCoordinateDetector.firstCoordinate(in: "A 10.0, 20.0 and B 30.0, 40.0")
        #expect(coord?.latitude == 10.0)
        #expect(coord?.longitude == 20.0)
    }

    @Test("matches returns every valid coordinate in document order")
    func matchesInOrder() {
        let matches = ChatCoordinateDetector.matches(in: "A 10.0, 20.0 and B 30.0, 40.0")
        #expect(matches.count == 2)
        #expect(matches.first?.coordinate.latitude == 10.0)
        #expect(matches.last?.coordinate.latitude == 30.0)
    }

    @Test("A coordinate ending a sentence is detected, period excluded")
    func trailingPeriod() {
        let coord = ChatCoordinateDetector.firstCoordinate(in: "Meet at 37.7749, -122.4194.")
        #expect(coord?.latitude == 37.7749)
    }
}
