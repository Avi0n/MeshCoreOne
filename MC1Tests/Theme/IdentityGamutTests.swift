@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Validates the identity-color solver in isolation: for a representative gamut, every name must
/// resolve to a color that clears WCAG AA against the surfaces it renders on, in both appearances
/// and both contrast settings, and the avatar glyph must clear AA against that color as a fill.
@Suite("IdentityGamut contrast")
struct IdentityGamutTests {
  private let gamut = IdentityGamut(
    hueAnchors: [20, 50, 95, 150, 195, 235, 280, 320],
    saturation: 0.45...0.75
  )

  /// A broad spread of names, including anagrams and near-duplicates that stress hash collisions.
  private static let names: [String] = (0..<600).map { "Sender \($0)" } + ["Bob", "obB", "Alice", "alice", "灯火", "Søren"]

  private static let light = UITraitCollection(userInterfaceStyle: .light)
  private static let dark = UITraitCollection(userInterfaceStyle: .dark)

  private func luminance(of color: Color, _ trait: UITraitCollection) -> Double {
    WCAGContrast.relativeLuminance(of: UIColor(color).resolvedColor(with: trait))
  }

  private func surfaceLuminances(_ trait: UITraitCollection) -> [Double] {
    [
      luminance(of: Color(.systemBackground), trait),
      luminance(of: Color(.secondarySystemBackground), trait),
      luminance(of: Color(UIColor.systemGray5), trait)
    ]
  }

  @Test
  func `every identity color clears AA against its surfaces in both appearances and contrasts`() {
    for trait in [Self.light, Self.dark] {
      for highContrast in [false, true] {
        let backgrounds = surfaceLuminances(trait)
        let floor = highContrast ? WCAGContrast.increasedContrastFloor : WCAGContrast.aaFloor
        for name in Self.names {
          let color = gamut.color(forName: name, backgroundLuminances: backgrounds, highContrast: highContrast)
          let colorLuminance = luminance(of: color, trait)
          for background in backgrounds {
            let ratio = WCAGContrast.contrastRatio(colorLuminance, background)
            #expect(
              ratio >= floor,
              "\(name) color luminance \(colorLuminance) vs surface \(background) is \(ratio), below \(floor)"
            )
          }
        }
      }
    }
  }

  @Test
  func `avatar glyph clears AA against the identity fill in both appearances`() {
    for trait in [Self.light, Self.dark] {
      let backgrounds = surfaceLuminances(trait)
      for name in Self.names {
        let fill = gamut.color(forName: name, backgroundLuminances: backgrounds, highContrast: false)
        let fillLuminance = luminance(of: fill, trait)
        let glyph = IdentityGamut.glyphColor(forFillLuminance: fillLuminance)
        let ratio = WCAGContrast.contrastRatio(luminance(of: glyph, trait), fillLuminance)
        #expect(ratio >= WCAGContrast.aaFloor, "\(name) glyph vs fill is \(ratio)")
      }
    }
  }

  @Test
  func `hue is stable across appearance and contrast for a given name`() {
    let lightBackgrounds = surfaceLuminances(Self.light)
    let darkBackgrounds = surfaceLuminances(Self.dark)
    for name in Self.names.prefix(50) {
      let lightHue = gamut.resolve(forName: name, backgroundLuminances: lightBackgrounds, highContrast: false).hue
      let darkHue = gamut.resolve(forName: name, backgroundLuminances: darkBackgrounds, highContrast: false).hue
      #expect(lightHue == darkHue, "\(name) hue drifted across appearance: \(lightHue) vs \(darkHue)")
    }
  }
}
