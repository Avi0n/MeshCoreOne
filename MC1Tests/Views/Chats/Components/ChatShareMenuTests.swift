import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ChatShareMenu predicate tests")
struct ChatShareMenuTests {
  private static let validKey = Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize)
  private static let sampleCoordinate = CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0)

  // MARK: - canShareLocation

  @Test
  func `Location is shareable when authorized, even with no current fix or node coordinate`() {
    #expect(ChatShareMenu.canShareLocation(
      phoneCoordinate: nil,
      nodeCoordinate: nil,
      locationAuthorized: true
    ))
  }

  @Test
  func `Location is shareable from a node coordinate without authorization`() {
    #expect(ChatShareMenu.canShareLocation(
      phoneCoordinate: nil,
      nodeCoordinate: Self.sampleCoordinate,
      locationAuthorized: false
    ))
  }

  @Test
  func `Location is not shareable with no coordinate and no authorization`() {
    #expect(!ChatShareMenu.canShareLocation(
      phoneCoordinate: nil,
      nodeCoordinate: nil,
      locationAuthorized: false
    ))
  }

  // MARK: - canShareMyInfo

  @Test
  func `My info is shareable with a full-length key and a non-empty name`() {
    #expect(ChatShareMenu.canShareMyInfo(publicKey: Self.validKey, nodeName: "Base 1"))
  }

  @Test
  func `My info is not shareable with a wrong-length public key`() {
    #expect(!ChatShareMenu.canShareMyInfo(
      publicKey: Data(repeating: 0xAB, count: 8),
      nodeName: "Base 1"
    ))
  }

  @Test
  func `My info is not shareable with a blank name`() {
    #expect(!ChatShareMenu.canShareMyInfo(publicKey: Self.validKey, nodeName: "   "))
  }
}
