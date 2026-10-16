@testable import MC1
import Testing

@Suite("WhatsNewVersion")
struct WhatsNewVersionTests {
  @Test
  func `parses major.minor`() {
    let version = WhatsNewVersion(marketingVersion: "1.2")
    #expect(version?.major == 1)
    #expect(version?.minor == 2)
  }

  @Test
  func `ignores the patch component`() {
    #expect(WhatsNewVersion(marketingVersion: "1.0.2") == WhatsNewVersion(major: 1, minor: 0))
  }

  @Test
  func `comparison is lexicographic on (major, minor)`() {
    #expect(WhatsNewVersion(major: 1, minor: 0) < WhatsNewVersion(major: 1, minor: 1))
    #expect(WhatsNewVersion(major: 1, minor: 9) < WhatsNewVersion(major: 2, minor: 0))
    #expect(WhatsNewVersion(major: 1, minor: 0) < WhatsNewVersion(major: 1, minor: 2))
    #expect(WhatsNewVersion(major: 2, minor: 0) > WhatsNewVersion(major: 1, minor: 9))
  }

  @Test(arguments: [
    ("1.0", "1.0.2"),
    ("1.0.0", "1.0.9")
  ])
  func `a patch bump is not greater`(baseline: String, current: String) throws {
    let lhs = WhatsNewVersion(marketingVersion: current)
    let rhs = WhatsNewVersion(marketingVersion: baseline)
    #expect(lhs == rhs)
    #expect(try !(#require(lhs) > rhs!))
  }

  @Test(arguments: [
    "unknown", "2", "2.0-beta", "1.0 (123)", "", "x.y", "1."
  ])
  func `unparseable strings yield nil`(input: String) {
    #expect(WhatsNewVersion(marketingVersion: input) == nil)
  }
}
