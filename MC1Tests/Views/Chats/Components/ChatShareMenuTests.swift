import Testing
import CoreLocation
import Foundation
@testable import MC1Services
@testable import MC1

@Suite("ChatShareMenu predicate tests")
struct ChatShareMenuTests {

    private static let validKey = Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize)
    private static let sampleCoordinate = CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0)

    // MARK: - canShareLocation

    @Test("Location is shareable when authorized, even with no current fix or node coordinate")
    func locationShareableWhenAuthorized() {
        #expect(ChatShareMenu.canShareLocation(
            phoneCoordinate: nil,
            nodeCoordinate: nil,
            locationAuthorized: true
        ))
    }

    @Test("Location is shareable from a node coordinate without authorization")
    func locationShareableFromNodeCoordinate() {
        #expect(ChatShareMenu.canShareLocation(
            phoneCoordinate: nil,
            nodeCoordinate: Self.sampleCoordinate,
            locationAuthorized: false
        ))
    }

    @Test("Location is not shareable with no coordinate and no authorization")
    func locationNotShareableWhenNothingAvailable() {
        #expect(!ChatShareMenu.canShareLocation(
            phoneCoordinate: nil,
            nodeCoordinate: nil,
            locationAuthorized: false
        ))
    }

    // MARK: - canShareMyInfo

    @Test("My info is shareable with a full-length key and a non-empty name")
    func myInfoShareableWithValidKeyAndName() {
        #expect(ChatShareMenu.canShareMyInfo(publicKey: Self.validKey, nodeName: "Base 1"))
    }

    @Test("My info is not shareable with a wrong-length public key")
    func myInfoNotShareableWithWrongLengthKey() {
        #expect(!ChatShareMenu.canShareMyInfo(
            publicKey: Data(repeating: 0xAB, count: 8),
            nodeName: "Base 1"
        ))
    }

    @Test("My info is not shareable with a blank name")
    func myInfoNotShareableWithBlankName() {
        #expect(!ChatShareMenu.canShareMyInfo(publicKey: Self.validKey, nodeName: "   "))
    }
}
