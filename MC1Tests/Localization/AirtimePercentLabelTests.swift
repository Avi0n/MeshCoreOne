import Testing
@testable import MC1

@Suite("Airtime % label")
struct AirtimePercentLabelTests {

    // A bare `%` in the .strings value is consumed by String(format:) inside L10n.tr,
    // dropping the percent sign. The value must be escaped as `%%` to render literally.
    @Test("airtimePercent renders a literal percent sign")
    func rendersLiteralPercent() {
        #expect(L10n.RemoteNodes.RemoteNodes.Status.airtimePercent == "Airtime %")
    }
}
