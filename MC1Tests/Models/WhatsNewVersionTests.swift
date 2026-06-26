import Testing
@testable import MC1

@Suite("WhatsNewVersion")
struct WhatsNewVersionTests {

    @Test("parses major.minor")
    func parsesMajorMinor() {
        let version = WhatsNewVersion(marketingVersion: "1.2")
        #expect(version?.major == 1)
        #expect(version?.minor == 2)
    }

    @Test("ignores the patch component")
    func ignoresPatch() {
        #expect(WhatsNewVersion(marketingVersion: "1.0.2") == WhatsNewVersion(major: 1, minor: 0))
    }

    @Test("comparison is lexicographic on (major, minor)")
    func comparisonOrdering() {
        #expect(WhatsNewVersion(major: 1, minor: 0) < WhatsNewVersion(major: 1, minor: 1))
        #expect(WhatsNewVersion(major: 1, minor: 9) < WhatsNewVersion(major: 2, minor: 0))
        #expect(WhatsNewVersion(major: 1, minor: 0) < WhatsNewVersion(major: 1, minor: 2))
        #expect(WhatsNewVersion(major: 2, minor: 0) > WhatsNewVersion(major: 1, minor: 9))
    }

    @Test("a patch bump is not greater", arguments: [
        ("1.0", "1.0.2"),
        ("1.0.0", "1.0.9")
    ])
    func patchBumpNotGreater(baseline: String, current: String) {
        let lhs = WhatsNewVersion(marketingVersion: current)
        let rhs = WhatsNewVersion(marketingVersion: baseline)
        #expect(lhs == rhs)
        #expect(!(lhs! > rhs!))
    }

    @Test("unparseable strings yield nil", arguments: [
        "unknown", "2", "2.0-beta", "1.0 (123)", "", "x.y", "1."
    ])
    func unparseableYieldsNil(input: String) {
        #expect(WhatsNewVersion(marketingVersion: input) == nil)
    }
}
