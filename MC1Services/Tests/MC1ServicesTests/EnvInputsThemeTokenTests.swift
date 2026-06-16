import Testing
@testable import MC1Services

@Suite("EnvInputs theme token")
struct EnvInputsThemeTokenTests {

    private func make(themeID: String) -> EnvInputs {
        EnvInputs(
            showInlineImages: true,
            autoPlayGIFs: true,
            showIncomingPath: true,
            showIncomingHopCount: true,
            showIncomingRegion: true,
            showIncomingSendTime: true,
            previewsEnabled: true,
            isHighContrast: false,
            isDark: false,
            showMapPreviews: true,
            isOffline: false,
            currentUserName: "Tester",
            themeID: themeID,
            contentSizeCategory: EnvInputs.defaultContentSizeCategory
        )
    }

    @Test("changing only themeID makes EnvInputs unequal (drives cache invalidation)")
    func themeIDChangeIsObservable() {
        #expect(make(themeID: "default") != make(themeID: "ember"))
        #expect(make(themeID: "default") == make(themeID: "default"))
    }

    @Test("equal themeIDs produce equal hashes")
    func equalThemeIDsHashEqually() {
        // Hashable only guarantees equal values hash equally; distinct values may legally collide,
        // so asserting unequal hashes for distinct themeIDs is non-contractual. The meaningful
        // property — themeID affecting equality — is covered by `themeIDChangeIsObservable`.
        #expect(make(themeID: "marine").hashValue == make(themeID: "marine").hashValue)
    }

    @Test("EnvInputs.default carries the default theme id")
    func defaultTokenIsDefault() {
        #expect(EnvInputs.default.themeID == "default")
    }
}
