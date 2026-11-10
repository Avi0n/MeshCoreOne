import Foundation
@testable import MC1Services
import MeshCore
import os
import Testing

@Suite("Binary protocol path reset")
struct BinaryProtocolPathResetTests {
  private static let publicKey = Data(repeating: 0xCD, count: 32)

  private final class RecordingContactRadio: ContactRadioOwning, @unchecked Sendable {
    private let keys = OSAllocatedUnfairLock(initialState: [Data]())

    var resetPathPublicKeys: [Data] {
      keys.withLock { $0 }
    }

    func resetPath(radioID: UUID, publicKey: Data) async throws {
      keys.withLock { $0.append(publicKey) }
    }

    func healAbsentRadioContact(radioID: UUID, publicKey: Data) async throws -> Bool {
      false
    }

    func importRadioContact(radioID: UUID, contact: MeshContact) async throws {}
  }

  private struct Harness {
    let service: BinaryProtocolService
    let session: MockMeshCoreSession
    let radio: RecordingContactRadio
    let suiteName: String
  }

  private func makeHarness(fallbackEnabled: Bool?) throws -> Harness {
    let suiteName = "BinaryProtocolPathReset.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defaults.removePersistentDomain(forName: suiteName)
    if let fallbackEnabled {
      defaults.set(fallbackEnabled, forKey: AppStorageKey.remoteFloodFallback.rawValue)
    }
    let session = MockMeshCoreSession()
    let radio = RecordingContactRadio()
    let service = BinaryProtocolService(
      session: session,
      radioID: UUID(),
      contactRadio: radio,
      defaults: defaults
    )
    return Harness(service: service, session: session, radio: radio, suiteName: suiteName)
  }

  @Test
  func `telemetry timeout resets once when fallback is on`() async throws {
    let harness = try makeHarness(fallbackEnabled: nil)
    defer { UserDefaults(suiteName: harness.suiteName)?.removePersistentDomain(forName: harness.suiteName) }
    await harness.session.setRequestTelemetryResults([
      .failure(MeshCoreError.timeout),
      .failure(MeshCoreError.timeout),
    ])

    await #expect(throws: BinaryProtocolError.self) {
      _ = try await harness.service.requestTelemetry(from: Self.publicKey)
    }
    #expect(harness.radio.resetPathPublicKeys == [Self.publicKey])
    #expect(await harness.session.stubbedRequestTelemetryResults.isEmpty)
  }

  @Test
  func `telemetry timeout does not reset when fallback is off`() async throws {
    let harness = try makeHarness(fallbackEnabled: false)
    defer { UserDefaults(suiteName: harness.suiteName)?.removePersistentDomain(forName: harness.suiteName) }
    await harness.session.setRequestTelemetryResults([
      .failure(MeshCoreError.timeout),
      .failure(MeshCoreError.timeout),
    ])

    await #expect(throws: BinaryProtocolError.self) {
      _ = try await harness.service.requestTelemetry(from: Self.publicKey)
    }
    #expect(harness.radio.resetPathPublicKeys.isEmpty)
    #expect(await harness.session.stubbedRequestTelemetryResults.count == 1)
  }
}
