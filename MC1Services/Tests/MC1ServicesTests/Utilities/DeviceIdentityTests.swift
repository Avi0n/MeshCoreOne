import CryptoKit
import Foundation
@testable import MC1Services
import Testing

@Suite("DeviceIdentity Tests")
struct DeviceIdentityTests {
  @Test
  func `Derives consistent UUID from public key`() {
    let publicKey = Data(repeating: 0xAB, count: 32)

    let uuid1 = DeviceIdentity.deriveUUID(from: publicKey)
    let uuid2 = DeviceIdentity.deriveUUID(from: publicKey)

    #expect(uuid1 == uuid2)
  }

  @Test
  func `Different public keys produce different UUIDs`() {
    let key1 = Data(repeating: 0xAA, count: 32)
    let key2 = Data(repeating: 0xBB, count: 32)

    let uuid1 = DeviceIdentity.deriveUUID(from: key1)
    let uuid2 = DeviceIdentity.deriveUUID(from: key2)

    #expect(uuid1 != uuid2)
  }

  @Test
  func `UUID derivation uses SHA256`() {
    let publicKey = Data([0x01, 0x02, 0x03, 0x04] + Array(repeating: UInt8(0), count: 28))

    let uuid = DeviceIdentity.deriveUUID(from: publicKey)

    // Manually compute expected hash
    let hash = SHA256.hash(data: publicKey)
    let hashBytes = Array(hash)
    let expectedUUID = UUID(uuid: (
      hashBytes[0], hashBytes[1], hashBytes[2], hashBytes[3],
      hashBytes[4], hashBytes[5], hashBytes[6], hashBytes[7],
      hashBytes[8], hashBytes[9], hashBytes[10], hashBytes[11],
      hashBytes[12], hashBytes[13], hashBytes[14], hashBytes[15]
    ))

    #expect(uuid == expectedUUID)
  }
}
