import Foundation
@testable import MC1
import Testing

@Suite("LinkPreviewPreferences Tests")
struct LinkPreviewPreferencesTests {
  private let defaults: UserDefaults

  init() {
    defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  @Test
  func `Has expected defaults: previews off, auto-resolve on`() {
    let prefs = LinkPreviewPreferences(defaults: defaults)
    #expect(prefs.previewsEnabled == false)
    #expect(prefs.autoResolveDM == true)
    #expect(prefs.autoResolveChannels == true)
  }

  @Test
  func `shouldAutoResolve for DM respects settings`() {
    var prefs = LinkPreviewPreferences(defaults: defaults)

    // Master on, auto on -> true
    prefs.previewsEnabled = true
    prefs.autoResolveDM = true
    #expect(prefs.shouldAutoResolve(isChannelMessage: false) == true)

    // Master on, auto off -> false
    prefs.autoResolveDM = false
    #expect(prefs.shouldAutoResolve(isChannelMessage: false) == false)

    // Master off -> false regardless
    prefs.previewsEnabled = false
    prefs.autoResolveDM = true
    #expect(prefs.shouldAutoResolve(isChannelMessage: false) == false)
  }

  @Test
  func `shouldAutoResolve for channel respects settings`() {
    var prefs = LinkPreviewPreferences(defaults: defaults)

    prefs.previewsEnabled = true
    prefs.autoResolveChannels = true
    #expect(prefs.shouldAutoResolve(isChannelMessage: true) == true)

    prefs.autoResolveChannels = false
    #expect(prefs.shouldAutoResolve(isChannelMessage: true) == false)
  }

  @Test
  func `shouldShowPreview reflects global toggle`() {
    var prefs = LinkPreviewPreferences(defaults: defaults)

    prefs.previewsEnabled = true
    #expect(prefs.shouldShowPreview == true)

    prefs.previewsEnabled = false
    #expect(prefs.shouldShowPreview == false)
  }
}
