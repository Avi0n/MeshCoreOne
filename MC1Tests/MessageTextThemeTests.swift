import Testing
import SwiftUI
@testable import MC1

@MainActor
@Suite("MessageText theme colors")
struct MessageTextThemeTests {

    @Test("incoming hashtag color is baked: different hashtagColor yields different output")
    func incomingHashtagColorIsBaked() {
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

    @Test("outgoing text color is baked: different outgoingTextColor yields different output")
    func outgoingTextColorIsBaked() {
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

    @Test("default theme's hashtag color is the one baked into the hashtag run")
    func defaultThemeHashtagColorIsBaked() {
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
