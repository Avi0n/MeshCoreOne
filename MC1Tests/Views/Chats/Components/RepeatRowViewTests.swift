import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("RepeatRowView")
struct RepeatRowViewTests {
  @Test
  func `unique short prefix is exact`() {
    let result = RepeatRowView.resolution(
      repeaterHash: Data([0x3F]),
      repeaters: [createRepeater(prefix: 0x3F, secondByte: 0x01, name: "Ridge")],
      discoveredRepeaters: [],
      userLocation: nil
    )

    #expect(result.displayName == "Ridge")
    #expect(result.matchKind == .exact)
  }

  @Test
  func `colliding short prefix is fallback`() {
    let result = RepeatRowView.resolution(
      repeaterHash: Data([0x3F]),
      repeaters: [
        createRepeater(prefix: 0x3F, secondByte: 0x01, name: "Ridge"),
        createRepeater(prefix: 0x3F, secondByte: 0x02, name: "Valley")
      ],
      discoveredRepeaters: [],
      userLocation: nil
    )

    #expect(result.matchKind == .fallback)
  }

  @Test
  func `unmatched hash is unresolved`() {
    let result = RepeatRowView.resolution(
      repeaterHash: Data([0xAA]),
      repeaters: [createRepeater(prefix: 0x3F, secondByte: 0x01, name: "Ridge")],
      discoveredRepeaters: [],
      userLocation: nil
    )

    #expect(result.matchKind == .unresolved)
  }

  private func createRepeater(prefix: UInt8, secondByte: UInt8, name: String) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([prefix, secondByte] + Array(repeating: UInt8(0), count: 30)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 10,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }
}
