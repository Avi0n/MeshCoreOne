import AppIntents
@testable import MC1
import Testing

/// Pins `supportedModes` to `.foreground` so the Control Center control still
/// opens the app on iOS 26.
struct OpenRadioStatusIntentTests {
  @Test func `supported modes is foreground`() {
    if #available(iOS 26, *) {
      #expect(OpenRadioStatusIntent.supportedModes == .foreground)
    }
  }
}
