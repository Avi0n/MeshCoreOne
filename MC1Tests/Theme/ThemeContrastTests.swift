@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Guards the legibility of outgoing message text against its bubble fill (the theme accent) for
/// every paid theme, in every appearance/contrast combination, and the legibility of identity and
/// category avatar colors against the surfaces they render on. These are surfaces a user reads under
/// stress and outdoor glare, so they must clear the WCAG AA 4.5:1 floor.
@Suite("Theme contrast")
struct ThemeContrastTests {
  /// Derived from the registry so a future paid theme is covered automatically rather than
  /// shipping with no contrast validation because someone forgot to extend a hand-written list.
  private static let paidThemes: [Theme] = ThemeRegistry.allThemes.filter { $0.productID != nil }

  private static let traits: [(name: String, style: UIUserInterfaceStyle, collection: UITraitCollection)] = [
    ("light", .light, UITraitCollection(userInterfaceStyle: .light)),
    ("dark", .dark, UITraitCollection(userInterfaceStyle: .dark)),
    ("light+highContrast", .light, UITraitCollection(userInterfaceStyle: .light).modifyingTraits {
      $0.accessibilityContrast = .high
    }),
    ("dark+highContrast", .dark, UITraitCollection(userInterfaceStyle: .dark).modifyingTraits {
      $0.accessibilityContrast = .high
    })
  ]

  /// The appearances a theme actually renders in. A forced color scheme pins the theme to one
  /// appearance, so asserting contrast in the other tests a combination users never see — e.g.
  /// Ember is dark-only, and its hashtag is tuned for its dark surface, not the light bubble it
  /// never shows on.
  private static func traits(
    for theme: Theme
  ) -> [(name: String, style: UIUserInterfaceStyle, collection: UITraitCollection)] {
    guard let forced = theme.preferredColorScheme else { return traits }
    let style: UIUserInterfaceStyle = (forced == .dark) ? .dark : .light
    return traits.filter { $0.style == style }
  }

  /// A broad spread of names, including anagrams and near-duplicates that stress hash collisions.
  private static let identityNames: [String] =
    (0..<400).map { "Sender \($0)" } + ["Bob", "obB", "Alice", "alice", "灯火", "Søren", "#general"]

  private static func floor(forTraitNamed name: String) -> Double {
    name.contains("highContrast") ? WCAGContrast.increasedContrastFloor : WCAGContrast.aaFloor
  }

  private static func luminance(of color: Color, _ collection: UITraitCollection) -> Double {
    WCAGContrast.relativeLuminance(of: UIColor(color).resolvedColor(with: collection))
  }

  /// Canvas (or system background for the surfaceless default theme) and incoming bubble — the two
  /// surfaces identity colors must stay legible against.
  private static func surfaceLuminances(_ theme: Theme, _ collection: UITraitCollection) -> [Double] {
    [theme.surfaces?.canvas ?? Color(.systemBackground), theme.incomingBubbleColor]
      .map { luminance(of: $0, collection) }
  }

  /// The list canvas — the only surface a category avatar renders on.
  private static func canvasLuminance(_ theme: Theme, _ collection: UITraitCollection) -> Double {
    luminance(of: theme.surfaces?.canvas ?? Color(.systemBackground), collection)
  }

  @Test
  func `outgoing text clears WCAG AA 4.5:1 against the accent in every appearance`() {
    for theme in Self.paidThemes {
      for trait in Self.traits(for: theme) {
        let text = Self.luminance(of: theme.outgoingTextColor, trait.collection)
        let fill = Self.luminance(of: theme.accentColor, trait.collection)
        let ratio = WCAGContrast.contrastRatio(text, fill)
        #expect(ratio >= WCAGContrast.aaFloor, "\(theme.id) outgoing text vs accent in \(trait.name) is \(ratio)")
      }
    }
  }

  @Test
  func `incoming hashtag links clear WCAG AA 4.5:1 against the incoming bubble in every appearance`() {
    for theme in Self.paidThemes {
      for trait in Self.traits(for: theme) {
        let hashtag = Self.luminance(of: theme.hashtagColor, trait.collection)
        let bubble = Self.luminance(of: theme.incomingBubbleColor, trait.collection)
        let ratio = WCAGContrast.contrastRatio(hashtag, bubble)
        #expect(ratio >= WCAGContrast.aaFloor, "\(theme.id) incoming hashtag vs bubble in \(trait.name) is \(ratio)")
      }
    }
  }

  @Test
  func `identity colors clear AA against their surfaces for every theme and appearance`() {
    for theme in ThemeRegistry.allThemes {
      for trait in Self.traits(for: theme) {
        let floor = Self.floor(forTraitNamed: trait.name)
        let highContrast = trait.name.contains("highContrast")
        let backgrounds = Self.surfaceLuminances(theme, trait.collection)
        for name in Self.identityNames {
          let color = theme.identityGamut.color(
            forName: name,
            backgroundLuminances: backgrounds,
            highContrast: highContrast
          )
          let colorLuminance = Self.luminance(of: color, trait.collection)
          for background in backgrounds {
            let ratio = WCAGContrast.contrastRatio(colorLuminance, background)
            #expect(ratio >= floor, "\(theme.id) identity '\(name)' vs surface \(background) in \(trait.name) is \(ratio)")
          }
        }
      }
    }
  }

  /// Resolves the channel / repeater / room avatar colors the way `Theme.categoryAvatarColor` does:
  /// each at its curated hue, against the list canvas only, at the darkest legible brightness.
  private static func categoryColors(_ theme: Theme, canvas: Double, highContrast: Bool) -> [(AvatarCategory, Color)] {
    AvatarCategory.anchorPriority.map { category in
      (category, theme.identityGamut.color(
        forName: category.gamutSeed,
        backgroundLuminances: [canvas],
        highContrast: highContrast,
        atHue: theme.categoryHue(for: category),
        atVariety: Theme.categoryDarkestVariety
      ))
    }
  }

  @Test
  func `gamut-derived category colors clear AA against the list canvas`() {
    // The System theme pins category colors to fixed legacy values that predate this AA bar, so
    // it is exempt; every gamut-derived theme must clear it against the canvas it renders on.
    for theme in ThemeRegistry.allThemes where theme.categoryAvatarOverride == nil {
      for trait in Self.traits(for: theme) {
        let floor = Self.floor(forTraitNamed: trait.name)
        let highContrast = trait.name.contains("highContrast")
        let canvas = Self.canvasLuminance(theme, trait.collection)
        for (category, color) in Self.categoryColors(theme, canvas: canvas, highContrast: highContrast) {
          let ratio = WCAGContrast.contrastRatio(Self.luminance(of: color, trait.collection), canvas)
          #expect(ratio >= floor, "\(theme.id) category \(category) vs canvas \(canvas) in \(trait.name) is \(ratio)")
        }
      }
    }
  }

  @Test
  func `channel, repeater, and room avatars resolve to distinct on-anchor hues for every gamut theme`() {
    for theme in ThemeRegistry.allThemes where theme.categoryAvatarOverride == nil {
      let anchors = Set(theme.identityGamut.sortedAnchors)
      let hues = AvatarCategory.anchorPriority.map { theme.categoryHue(for: $0) }
      #expect(Set(hues).count == hues.count, "\(theme.id) category hues collide: \(hues)")
      #expect(hues.allSatisfy { anchors.contains($0) }, "\(theme.id) category hue left the palette: \(hues)")
    }
  }

  @Test
  func `avatar glyph clears AA against the identity fill for every theme and appearance`() {
    for theme in ThemeRegistry.allThemes {
      for trait in Self.traits(for: theme) {
        let backgrounds = Self.surfaceLuminances(theme, trait.collection)
        for name in Self.identityNames.prefix(100) {
          let fill = theme.identityGamut.color(forName: name, backgroundLuminances: backgrounds, highContrast: false)
          let fillLuminance = Self.luminance(of: fill, trait.collection)
          let glyph = IdentityGamut.glyphColor(forFillLuminance: fillLuminance)
          let ratio = WCAGContrast.contrastRatio(Self.luminance(of: glyph, trait.collection), fillLuminance)
          #expect(ratio >= WCAGContrast.aaFloor, "\(theme.id) glyph vs fill '\(name)' in \(trait.name) is \(ratio)")
        }
      }
    }
  }
}
