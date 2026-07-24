@testable import MC1
import SwiftUI
import Testing

@Suite("MapAppearance")
struct MapAppearanceTests {
  @Test
  func `system follows environment`() {
    #expect(resolvedMapIsDark(preference: .system, colorScheme: .dark) == true)
    #expect(resolvedMapIsDark(preference: .system, colorScheme: .light) == false)
  }

  @Test
  func `light forces false`() {
    #expect(resolvedMapIsDark(preference: .light, colorScheme: .dark) == false)
    #expect(resolvedMapIsDark(preference: .light, colorScheme: .light) == false)
  }

  @Test
  func `dark forces true`() {
    #expect(resolvedMapIsDark(preference: .dark, colorScheme: .light) == true)
    #expect(resolvedMapIsDark(preference: .dark, colorScheme: .dark) == true)
  }
}
