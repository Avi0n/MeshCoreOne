@testable import MC1
import SwiftUI
import Testing

@MainActor
@Suite("MessageText theme colors")
struct MessageTextThemeTests {
  @Test
  func `incoming hashtag color is baked: different hashtagColor yields different output`() {
    let cyan = MessageText.buildFormattedText(
      text: "see #news", isOutgoing: false, currentUserName: nil,
      isHighContrast: false, outgoingTextColor: .white, hashtagColor: .cyan,
      identityGamut: Theme.default.identityGamut, identityBackgroundLuminances: [1.0]
    )
    let orange = MessageText.buildFormattedText(
      text: "see #news", isOutgoing: false, currentUserName: nil,
      isHighContrast: false, outgoingTextColor: .white, hashtagColor: .orange,
      identityGamut: Theme.default.identityGamut, identityBackgroundLuminances: [1.0]
    )
    #expect(cyan.text != orange.text)
  }

  @Test
  func `outgoing text color is baked: different outgoingTextColor yields different output`() {
    let white = MessageText.buildFormattedText(
      text: "hello #news", isOutgoing: true, currentUserName: nil,
      isHighContrast: false, outgoingTextColor: .white, hashtagColor: .cyan,
      identityGamut: Theme.default.identityGamut, identityBackgroundLuminances: [1.0]
    )
    let pink = MessageText.buildFormattedText(
      text: "hello #news", isOutgoing: true, currentUserName: nil,
      isHighContrast: false, outgoingTextColor: .pink, hashtagColor: .cyan,
      identityGamut: Theme.default.identityGamut, identityBackgroundLuminances: [1.0]
    )
    #expect(white.text != pink.text)
  }

  @Test
  func `default theme's hashtag color is the one baked into the hashtag run`() {
    // Drive the input from the real default theme rather than a literal, so retinting the
    // default theme's hashtag asset cannot pass this test by coincidence.
    let themed = MessageText.buildFormattedText(
      text: "tap #news", isOutgoing: false, currentUserName: nil,
      isHighContrast: false,
      outgoingTextColor: Theme.default.outgoingTextColor,
      hashtagColor: Theme.default.hashtagColor,
      identityGamut: Theme.default.identityGamut, identityBackgroundLuminances: [1.0]
    )
    let hashtagRun = themed.text.runs.first { $0.link != nil }
    #expect(hashtagRun?.foregroundColor == Theme.default.hashtagColor)
  }
}
