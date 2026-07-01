@testable import MC1
import Testing

@Suite("Airtime % label")
struct AirtimePercentLabelTests {
  /// A bare `%` in the .strings value is consumed by String(format:) inside L10n.tr,
  /// dropping the percent sign. The value must be escaped as `%%` to render literally.
  @Test
  func `airtimePercent renders a literal percent sign`() {
    #expect(L10n.RemoteNodes.RemoteNodes.Status.airtimePercent == "Airtime %")
  }
}
