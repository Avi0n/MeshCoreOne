@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("Theme structure")
struct ThemeStructureTests {
  @Test
  func `Default theme paints no surfaces`() {
    #expect(Theme.default.surfaces == nil)
  }

  @Test
  func `Default theme imposes no chrome tint, deferring to the system`() {
    #expect(Theme.default.chromeTint == nil)
  }

  /// Derived from the registry so a future paid theme is covered automatically.
  private static let paidThemes: [Theme] = ThemeRegistry.allThemes.filter { $0.productID != nil }

  @Test
  func `Paid themes impose their accent on chrome`() {
    for theme in Self.paidThemes {
      #expect(theme.chromeTint == theme.accentColor, "\(theme.id) must tint chrome with its accent")
    }
  }

  @Test
  func `Ember paints the canvas but not the card tier`() throws {
    let surfaces = try #require(Theme.ember.surfaces)
    #expect(surfaces.canvas == .black)
    #expect(surfaces.card == nil)
  }

  @Test
  func `Every painted theme defines both canvas and card tiers`() throws {
    // Painted = every paid theme except the canvas-only Ember. Excluding by identity (not by
    // card-presence) keeps the assertion meaningful: a future paid theme that forgets its card
    // tier lands here and fails instead of being silently filtered out.
    let painted = Self.paidThemes.filter { $0.id != Theme.ember.id }
    for theme in painted {
      let surfaces = try #require(theme.surfaces, "\(theme.id) must have surfaces")
      #expect(surfaces.card != nil, "\(theme.id) must define card tier")
    }
  }

  @Test
  func `Every theme defines a usable identity gamut`() {
    for theme in ThemeRegistry.allThemes {
      let gamut = theme.identityGamut
      #expect(!gamut.hueAnchors.isEmpty, "\(theme.id) must define identity hue anchors")
      #expect(gamut.hueAnchors.allSatisfy { (0..<360).contains($0) }, "\(theme.id) hue anchors must be 0..<360")
      #expect(gamut.saturation.lowerBound >= 0 && gamut.saturation.upperBound <= 1,
              "\(theme.id) saturation must be within 0...1")
      #expect(gamut.saturation.lowerBound < gamut.saturation.upperBound,
              "\(theme.id) saturation must be a non-empty range")
    }
  }

  @Test
  func `Only the System theme pins fixed category avatar colors`() {
    #expect(Theme.default.categoryAvatarOverride != nil, "System theme must pin category colors")
    for theme in Self.paidThemes {
      #expect(theme.categoryAvatarOverride == nil, "\(theme.id) must derive category colors from its gamut")
    }
  }

  @Test
  func `Migrated themes' outgoingTextColor resolves differently in light vs dark`() {
    let migrated: [Theme] = [.fern, .olive, .lavender, .sakura]
    let light = UITraitCollection(userInterfaceStyle: .light)
    let dark = UITraitCollection(userInterfaceStyle: .dark)
    for theme in migrated {
      let lightResolved = UIColor(theme.outgoingTextColor).resolvedColor(with: light)
      let darkResolved = UIColor(theme.outgoingTextColor).resolvedColor(with: dark)
      #expect(lightResolved != darkResolved, "\(theme.id) outgoingText must differ across appearance")
    }
  }
}
