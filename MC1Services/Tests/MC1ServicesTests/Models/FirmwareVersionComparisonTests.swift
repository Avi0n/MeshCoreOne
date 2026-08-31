@testable import MC1Services
import Testing

@Suite("Firmware version string comparison")
struct FirmwareVersionComparisonTests {
  @Test
  func `bare and v-prefixed versions compare on major minor`() {
    #expect("v1.15.0".isAtLeast(major: 1, minor: 15))
    #expect("1.15".isAtLeast(major: 1, minor: 15))
    #expect("v1.15".isAtLeast(major: 1, minor: 15))
    #expect("v1.15.0-abc".isAtLeast(major: 1, minor: 15))
    #expect(!"v1.14.1".isAtLeast(major: 1, minor: 15))
    #expect(!"1.14".isAtLeast(major: 1, minor: 15))
  }

  @Test
  func `CLI ver banner compares on the first major minor`() {
    #expect("MeshCore v1.15.0 (2025-04-18)".isAtLeast(major: 1, minor: 15))
    #expect("MeshCore v1.15.0 (2025-04-18)".isAtLeast(major: 1, minor: 14))
    #expect(!"MeshCore v1.14.1 (2025-04-18)".isAtLeast(major: 1, minor: 15))
  }

  @Test
  func `unparseable strings are not at least any version`() {
    #expect(!"MeshCore".isAtLeast(major: 1, minor: 0))
    #expect(!"".isAtLeast(major: 1, minor: 0))
    #expect(!"v1".isAtLeast(major: 1, minor: 0))
  }
}
