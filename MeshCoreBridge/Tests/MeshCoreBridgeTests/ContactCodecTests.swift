import Foundation
import MeshCore
import Testing
@testable import MeshCoreBridgeKit

/// Exercises the one genuinely new path in the follow-up: decoding a contact
/// envelope back into a `MeshContact` (the inverse of the contact encoder) and
/// the entry points that consume it.
struct ContactCodecTests {
  /// The bytes Kotlin would hand back to `addContactEncoded` — a `contact`
  /// envelope, identical in shape to a `contact` event.
  private static func contactEnvelope(_ contact: MeshContact) -> [UInt8] {
    BridgeProtocol.envelope(kind: "contact", payload: PayloadEncoder.contact(contact)).serialized()
  }

  @Test("a contact round-trips through encode then decode")
  func contactRoundTrips() throws {
    let original = BridgeFixtures.contact
    let decoded = try ContactDecoder.decode(envelope: Self.contactEnvelope(original))
    #expect(decoded == original)
  }

  @Test("decoding rejects a wrong-kind envelope")
  func rejectsWrongKind() {
    let notAContact = CommandResultEncoder.battery(BridgeFixtures.batteryInfo).serialized()
    #expect(throws: MeshCoreError.self) {
      _ = try ContactDecoder.decode(envelope: notAContact)
    }
  }

  @Test("addContactEncoded surfaces a malformed envelope as invalidInput")
  func malformedEnvelopeFailsThroughBridge() async throws {
    let recorder = BridgeRecorder()
    let bridge = MeshCoreBridge(
      sendListener: recorder,
      resultListener: recorder,
      eventListener: recorder,
      logListener: recorder
    )
    // Garbage bytes: the decode fails before any device I/O, so the failure is
    // deterministic without a connection.
    bridge.addContactEncoded(requestID: 9, contactBytes: Array("not json".utf8))
    let failed = await recorder.wait { recorder.failures.contains { $0.id == 9 } }
    #expect(failed, "addContactEncoded with garbage should fail")
    let failure = try #require(recorder.failures.first { $0.id == 9 })
    #expect(failure.code == BridgeErrorCode.invalidInput.rawValue)
  }
}
