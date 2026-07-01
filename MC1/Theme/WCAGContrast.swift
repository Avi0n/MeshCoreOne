import SwiftUI
import UIKit

/// WCAG relative-luminance and contrast-ratio math, shared by the identity-color solver
/// (`IdentityGamut`) and the theme contrast tests. Kept in one place so a safety-critical
/// app never ships two divergent copies of the formula.
///
/// The pure-`Double` entry points carry no UIKit dependency, so the identity-color solver can
/// run off the main actor (it is invoked from the off-main message-text bake).
enum WCAGContrast {
  /// Standard WCAG AA floor for normal-size text.
  static let aaFloor = 4.5

  /// Floor applied when the user has Increased Contrast enabled. Targets the AAA ratio so the
  /// identity palette tightens the same way the legacy high-contrast palette did.
  static let increasedContrastFloor = 7.0

  /// sRGB linearization threshold and curve constants from the WCAG 2.x definition.
  private static let linearThreshold = 0.03928
  private static let linearDivisor = 12.92
  private static let curveOffset = 0.055
  private static let curveDivisor = 1.055
  private static let curveExponent = 2.4

  /// Rec. 709 luminance coefficients (a standard, fixed table — not tunable).
  private static let redCoefficient = 0.2126
  private static let greenCoefficient = 0.7152
  private static let blueCoefficient = 0.0722

  /// Additive term in the WCAG contrast ratio, modelling ambient flare.
  private static let contrastFlare = 0.05

  /// Relative luminance of an sRGB color expressed as 0...1 components. Components are clamped
  /// because a wide-gamut or HSB-derived color can resolve slightly outside the unit range.
  static func relativeLuminance(red: Double, green: Double, blue: Double) -> Double {
    func linearize(_ component: Double) -> Double {
      let c = min(max(component, 0), 1)
      return c <= linearThreshold ? c / linearDivisor : pow((c + curveOffset) / curveDivisor, curveExponent)
    }
    return redCoefficient * linearize(red)
      + greenCoefficient * linearize(green)
      + blueCoefficient * linearize(blue)
  }

  /// Contrast ratio between two relative-luminance values (order-independent).
  static func contrastRatio(_ lhs: Double, _ rhs: Double) -> Double {
    let lighter = max(lhs, rhs)
    let darker = min(lhs, rhs)
    return (lighter + contrastFlare) / (darker + contrastFlare)
  }

  /// Relative luminance of a resolved `UIColor`. Main-actor / UIKit path used by live views to
  /// turn an adaptive theme surface into the `Double` the solver consumes.
  static func relativeLuminance(of color: UIColor) -> Double {
    var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
    color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
    return relativeLuminance(red: Double(red), green: Double(green), blue: Double(blue))
  }
}
