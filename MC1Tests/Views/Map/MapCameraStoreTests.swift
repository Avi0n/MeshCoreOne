import Testing
import MapKit
@testable import MC1

@Suite("MapCameraStore Tests")
struct MapCameraStoreTests {

    @Test("encode then decode round-trips a region")
    func roundTrip() throws {
        let region = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            span: MKCoordinateSpan(latitudeDelta: 0.25, longitudeDelta: 0.5)
        )

        let decoded = try #require(MapCameraStore.decode(MapCameraStore.encode(region)))

        #expect(decoded.center.latitude == 37.7749)
        #expect(decoded.center.longitude == -122.4194)
        #expect(decoded.span.latitudeDelta == 0.25)
        #expect(decoded.span.longitudeDelta == 0.5)
    }

    @Test("decode rejects a region that encode produced from invalid input")
    func decodeGatesInvalidEncodedRegion() {
        let nonFinite = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 10, longitude: 20),
            span: MKCoordinateSpan(latitudeDelta: .nan, longitudeDelta: 1)
        )
        let zeroSpan = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 10, longitude: 20),
            span: MKCoordinateSpan(latitudeDelta: 0, longitudeDelta: 1)
        )
        #expect(MapCameraStore.decode(MapCameraStore.encode(nonFinite)) == nil)
        #expect(MapCameraStore.decode(MapCameraStore.encode(zeroSpan)) == nil)
    }

    @Test("decode rejects malformed input", arguments: [
        "",
        "1,2,3",
        "1,2,3,4,5",
        "a,b,c,d",
        "10,20,nan,1",
        "91,20,1,1",
        "10,20,0,1",
        "10,20,1,-1"
    ])
    func decodeRejectsMalformed(_ input: String) {
        #expect(MapCameraStore.decode(input) == nil)
    }
}
