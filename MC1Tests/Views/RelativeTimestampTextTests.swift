import Foundation
@testable import MC1
import Testing

@Suite("RelativeTimestampText Tests")
@MainActor
struct RelativeTimestampTextTests {
  private let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

  private func timestamp(secondsAgo: TimeInterval) -> UInt32 {
    UInt32(referenceDate.addingTimeInterval(-secondsAgo).timeIntervalSince1970)
  }

  // MARK: - Now Threshold (< 60 seconds)

  @Test
  func `Returns 'Now' for timestamps under 60 seconds`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 0),
      relativeTo: referenceDate
    )
    #expect(result == L10n.Chats.Chats.Timestamp.now)
  }

  @Test
  func `Returns 'Now' at 59 seconds ago`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 59),
      relativeTo: referenceDate
    )
    #expect(result == L10n.Chats.Chats.Timestamp.now)
  }

  @Test
  func `Returns relative format at exactly 60 seconds`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 60),
      relativeTo: referenceDate
    )
    #expect(result != L10n.Chats.Chats.Timestamp.now)
    #expect(!result.isEmpty)
  }

  // MARK: - Relative Times (1 min to 1 week)

  @Test
  func `Returns non-empty string for minutes ago`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 120),
      relativeTo: referenceDate
    )
    #expect(!result.isEmpty)
  }

  @Test
  func `Returns non-empty string for hours ago`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 3600),
      relativeTo: referenceDate
    )
    #expect(!result.isEmpty)
  }

  @Test
  func `Returns non-empty string for yesterday`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 86400),
      relativeTo: referenceDate
    )
    #expect(!result.isEmpty)
  }

  @Test
  func `Returns non-empty string for days ago`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 172_800),
      relativeTo: referenceDate
    )
    #expect(!result.isEmpty)
  }

  // MARK: - Week+ (formatted date)

  @Test
  func `Returns abbreviated date format for 7+ days ago`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 604_800),
      relativeTo: referenceDate
    )
    // Should return abbreviated month and day, e.g., "Nov 7"
    #expect(result.contains(" "))
  }

  @Test
  func `Returns abbreviated date format for old dates`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 2_592_000), // 30 days
      relativeTo: referenceDate
    )
    // Should return abbreviated month and day
    #expect(result.contains(" "))
  }

  // MARK: - Boundary Tests

  @Test
  func `Uses relative format just before week threshold`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 604_799), // 1 second before 7 days
      relativeTo: referenceDate
    )
    #expect(!result.isEmpty)
  }

  @Test
  func `Uses date format at exactly week threshold`() {
    let result = RelativeTimestampText.format(
      timestamp: timestamp(secondsAgo: 604_800), // exactly 7 days
      relativeTo: referenceDate
    )
    // Date format should contain a space (e.g., "Nov 7")
    #expect(result.contains(" "))
  }
}
