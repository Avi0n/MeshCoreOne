import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("resolveMentionTap")
@MainActor
struct ResolveMentionTapTests {
  private func makeContact(
    name: String,
    radioID: UUID,
    isBlocked: Bool = false
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: UInt8.random(in: 0...255), count: 32),
      name: name,
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: isBlocked,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  @Test
  func `Single match returns navigate outcome with the matched contact`() {
    let radio = UUID()
    let alice = makeContact(name: "Alice", radioID: radio)
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "Alice",
      contacts: [alice, makeContact(name: "Bob", radioID: radio)],
      connectedDeviceName: "Me",
      radioID: radio
    )
    if case let .navigate(contact) = outcome {
      #expect(contact.id == alice.id)
    } else {
      Issue.record("Expected .navigate, got \(outcome)")
    }
  }

  @Test
  func `Multiple matches returns picker outcome with all matches and isSelfMention false`() {
    let radio = UUID()
    let alice1 = makeContact(name: "Alice", radioID: radio)
    let alice2 = makeContact(name: "Alice", radioID: radio)
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "Alice",
      contacts: [alice1, alice2, makeContact(name: "Bob", radioID: radio)],
      connectedDeviceName: "Me",
      radioID: radio
    )
    if case let .picker(context) = outcome {
      #expect(context.matches.count == 2)
      #expect(context.isSelfMention == false)
      #expect(context.radioID == radio)
    } else {
      Issue.record("Expected .picker, got \(outcome)")
    }
  }

  @Test
  func `Zero matches with non-self name returns picker outcome with empty matches and isSelfMention false`() {
    let radio = UUID()
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "Charlie",
      contacts: [makeContact(name: "Alice", radioID: radio)],
      connectedDeviceName: "Me",
      radioID: radio
    )
    if case let .picker(context) = outcome {
      #expect(context.matches.isEmpty)
      #expect(context.isSelfMention == false)
      #expect(context.name == "Charlie")
    } else {
      Issue.record("Expected .picker, got \(outcome)")
    }
  }

  @Test
  func `Zero matches with self-name returns picker outcome with isSelfMention true (case-insensitive)`() {
    let radio = UUID()
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "mydevice",
      contacts: [makeContact(name: "Alice", radioID: radio)],
      connectedDeviceName: "MyDevice",
      radioID: radio
    )
    if case let .picker(context) = outcome {
      #expect(context.matches.isEmpty)
      #expect(context.isSelfMention == true)
    } else {
      Issue.record("Expected .picker, got \(outcome)")
    }
  }

  @Test
  func `Blocked single match still navigates (excludeBlocked is false)`() {
    let radio = UUID()
    let blockedAlice = makeContact(name: "Alice", radioID: radio, isBlocked: true)
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "Alice",
      contacts: [blockedAlice],
      connectedDeviceName: "Me",
      radioID: radio
    )
    if case let .navigate(contact) = outcome {
      #expect(contact.id == blockedAlice.id)
      #expect(contact.isBlocked == true)
    } else {
      Issue.record("Expected .navigate for blocked match, got \(outcome)")
    }
  }

  @Test
  func `Offline localNodeName fallback Me does NOT mark inbound @[Me] as self`() {
    let radio = UUID()
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "Me",
      contacts: [],
      connectedDeviceName: nil,
      radioID: radio
    )
    if case let .picker(context) = outcome {
      #expect(context.isSelfMention == false)
    } else {
      Issue.record("Expected .picker, got \(outcome)")
    }
  }

  @Test
  func `Bidi-control characters are stripped from the name before matching`() {
    let radio = UUID()
    let alice = makeContact(name: "Alice", radioID: radio)
    let bidi = "Alice\u{202E}"
    let outcome = MentionTapEvaluator.evaluate(
      rawName: bidi,
      contacts: [alice],
      connectedDeviceName: "Me",
      radioID: radio
    )
    if case let .navigate(contact) = outcome {
      #expect(contact.id == alice.id)
    } else {
      Issue.record("Expected .navigate after sanitization, got \(outcome)")
    }
  }

  @Test
  func `Whitespace-only name short-circuits to empty picker without touching contacts`() {
    let radio = UUID()
    let alice = makeContact(name: "Alice", radioID: radio)
    let outcome = MentionTapEvaluator.evaluate(
      rawName: "   ",
      contacts: [alice],
      connectedDeviceName: "Me",
      radioID: radio
    )
    if case let .picker(context) = outcome {
      #expect(context.matches.isEmpty)
      #expect(context.isSelfMention == false)
    } else {
      Issue.record("Expected .picker, got \(outcome)")
    }
  }
}
