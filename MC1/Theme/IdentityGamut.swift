import SwiftUI

/// A theme's identity-color space: the hues that give the theme its character, plus a saturation
/// band. `color(forName:...)` maps any name to a deterministic color drawn from this gamut, with
/// brightness (and, when needed, saturation) solved so the result clears WCAG AA against the
/// surfaces it renders on. The hue/saturation a name resolves to is stable across launches and
/// appearances; only the brightness flips with light/dark so the color stays legible.
///
/// All math is pure `Double` work — no UIKit, no SwiftUI environment — so it can run off the main
/// actor inside the message-text bake. Callers resolve their surface colors to relative-luminance
/// values and pass them in.
struct IdentityGamut: Equatable {
  /// Hue centers (degrees, 0..<360) that define the theme. A name lands near one of these and is
  /// jittered within the gap to its neighbors, so the union covers a continuous slice of the wheel
  /// while every color still reads as on-theme.
  let hueAnchors: [Double]
  /// Saturation band the solver draws from. The solver may dip below `lowerBound` only when a hue
  /// physically cannot reach the luminance a dark background demands at full saturation.
  let saturation: ClosedRange<Double>

  // MARK: - Tunables

  private static let degreesPerCircle = 360.0
  /// Floor the solver desaturates toward when a vivid hue cannot otherwise reach the luminance a
  /// dark surface requires. Near zero so legibility is always achievable (a gray reaches any
  /// luminance); colors only wash out this far in the rare high-contrast-on-dark case that needs it.
  private static let saturationReachFloor = 0.0
  /// Step used when relaxing saturation to reach an unreachable luminance target.
  private static let saturationRelaxStep = 0.04
  /// Fraction of the AA-feasible luminance span used as the dark-text variety window. Names spread
  /// across `[darkVarietyFloor, boundary]` so brightness varies per identity without breaching AA.
  private static let darkVarietyFloor = 0.45
  /// Upper luminance cap for light-text (dark-appearance) colors so they never go pure white.
  private static let lightLuminanceCap = 0.86
  /// Added to the target contrast ratio so rendering drift (HSB rounding, wide-gamut resolution)
  /// cannot drop the rendered color below the real AA floor.
  private static let contrastSafetyMargin = 0.35
  /// Binary-search iteration count for mapping a luminance target to a brightness value.
  private static let brightnessSearchIterations = 24
  /// Luminance above which a background is treated as "light" (dark text) rather than "dark".
  private static let appearanceMidpointLuminance = 0.3

  private static let contrastFlare = 0.05

  // MARK: - Public

  /// Resolves the on-theme identity color for `name`, guaranteed to clear the contrast floor
  /// against every surface in `backgroundLuminances` (all expected to share the current
  /// appearance's polarity — e.g. the canvas and incoming-bubble luminances for one appearance).
  func color(forName name: String, backgroundLuminances: [Double], highContrast: Bool, atHue: Double? = nil, atVariety: Double? = nil) -> Color {
    let resolved = resolve(forName: name, backgroundLuminances: backgroundLuminances, highContrast: highContrast, atHue: atHue, atVariety: atVariety)
    // Build the color from the exact sRGB components the solver evaluated, rather than letting
    // `Color(hue:saturation:brightness:)` re-derive them in a wider gamut: that would drift the
    // rendered luminance off the solved value and could undercut the AA floor the solver targeted.
    let rgb = Self.hsbToRGB(hue: resolved.hue, saturation: resolved.saturation, brightness: resolved.brightness)
    return Color(.sRGB, red: rgb.red, green: rgb.green, blue: rgb.blue)
  }

  /// Sorted hue anchors, the on-theme hues a name can land on before jitter.
  var sortedAnchors: [Double] {
    hueAnchors.sorted()
  }

  /// Assigns each name in `names` a distinct anchor hue (no jitter), bumping to the next anchor on
  /// collision so a small fixed set — the channel / repeater / room category avatars — never share a
  /// color. Earlier names in the array win their preferred anchor; later ones step to the next free
  /// one. Stays on-palette because every result is one of the theme's declared anchors.
  func distinctAnchorHues(forNames names: [String]) -> [Double] {
    let anchors = sortedAnchors
    var taken = Set<Int>()
    return names.map { name in
      var index = Int(Self.fnv1a(name) % UInt64(anchors.count))
      while taken.contains(index) {
        index = (index + 1) % anchors.count
      }
      taken.insert(index)
      return anchors[index]
    }
  }

  /// The glyph (initials / icon) color that reads on a fill of the given luminance: white when the
  /// fill is dark enough, otherwise near-black. One of the two always clears AA for any fill.
  static func glyphColor(forFillLuminance luminance: Double) -> Color {
    let whiteContrast = (1.0 + contrastFlare) / (luminance + contrastFlare)
    let blackContrast = (luminance + contrastFlare) / contrastFlare
    return whiteContrast >= blackContrast ? .white : Color(white: 0.1)
  }

  // MARK: - Solver (exposed at package level for tests)

  /// Hue (degrees), saturation, and brightness a name resolves to, before constructing the `Color`.
  /// Exposed so tests can resolve the same values the renderer will and assert AA on the real color.
  /// `atHue` overrides the jittered hue with a fixed one — used to pin category avatars to an anchor —
  /// while saturation and brightness still derive from the name's seed. `atVariety` overrides the
  /// per-name brightness draw (0 = darkest legible, 1 = lightest legible); category avatars pass 0 so
  /// they render as deep, consistent swatches rather than washed-out brights.
  func resolve(
    forName name: String,
    backgroundLuminances: [Double],
    highContrast: Bool,
    atHue: Double? = nil,
    atVariety: Double? = nil
  ) -> (hue: Double, saturation: Double, brightness: Double) {
    let seed = Self.fnv1a(name)
    let hue = atHue ?? hue(for: seed)
    let baseSaturation = pickSaturation(for: seed)
    let varietyFraction = atVariety ?? Self.fraction(seed, shift: 48)
    let floor = (highContrast ? WCAGContrast.increasedContrastFloor : WCAGContrast.aaFloor) + Self.contrastSafetyMargin

    let backgrounds = backgroundLuminances.isEmpty ? [1.0] : backgroundLuminances
    let darkText = (backgrounds.min() ?? 1.0) >= Self.appearanceMidpointLuminance

    if darkText {
      // Light surfaces -> dark text. Binding surface is the darkest (least headroom).
      let bindingBackground = backgrounds.min() ?? 1.0
      let maxLuminance = (bindingBackground + Self.contrastFlare) / floor - Self.contrastFlare
      let safeMax = max(maxLuminance, 0)
      let targetLuminance = safeMax * (Self.darkVarietyFloor + (1 - Self.darkVarietyFloor) * varietyFraction)
      let brightness = brightnessFor(luminance: targetLuminance, hue: hue, saturation: baseSaturation)
      return (hue, baseSaturation, brightness)
    } else {
      // Dark surfaces -> light text. Binding surface is the lightest (least headroom).
      let bindingBackground = backgrounds.max() ?? 0.0
      // Required luminance to clear the floor. Not capped: a light dark-mode bubble can demand a
      // near-white text, and undershooting it would breach AA.
      let requiredLuminance = max(floor * (bindingBackground + Self.contrastFlare) - Self.contrastFlare, 0)
      // A vivid hue caps the luminance it can reach at full brightness; desaturate as far as
      // needed to reach the requirement. Legibility outranks saturation in the rare
      // high-contrast-on-dark case that forces this; a gray reaches any luminance.
      var saturation = baseSaturation
      while saturation > Self.saturationReachFloor,
            Self.luminance(hue: hue, saturation: saturation, brightness: 1.0) < requiredLuminance {
        saturation = max(Self.saturationReachFloor, saturation - Self.saturationRelaxStep)
      }
      // Vary lighter than the minimum (lighter = more contrast = still AA), capped at what the
      // hue can reach so the target is always achievable.
      let reachableMax = Self.luminance(hue: hue, saturation: saturation, brightness: 1.0)
      let ceiling = max(requiredLuminance, min(reachableMax, Self.lightLuminanceCap))
      let targetLuminance = min(requiredLuminance + (ceiling - requiredLuminance) * varietyFraction, reachableMax)
      let brightness = brightnessFor(luminance: targetLuminance, hue: hue, saturation: saturation)
      return (hue, saturation, brightness)
    }
  }

  // MARK: - Hue / saturation selection

  private func hue(for seed: UInt64) -> Double {
    let sorted = hueAnchors.sorted()
    let index = Int(seed % UInt64(sorted.count))
    let anchor = sorted[index]
    // Gap to each neighbor on the circle; jitter within half the gap so anchors tile the wheel.
    let next = sorted[(index + 1) % sorted.count]
    let prev = sorted[(index - 1 + sorted.count) % sorted.count]
    let gapNext = Self.circularGap(from: anchor, to: next)
    let gapPrev = Self.circularGap(from: prev, to: anchor)
    let fraction = Self.fraction(seed, shift: 16) * 2 - 1
    let jitter = fraction >= 0 ? fraction * gapNext / 2 : fraction * gapPrev / 2
    return (anchor + jitter).truncatingRemainder(dividingBy: Self.degreesPerCircle) + (anchor + jitter < 0 ? Self.degreesPerCircle : 0)
  }

  private func pickSaturation(for seed: UInt64) -> Double {
    saturation.lowerBound + (saturation.upperBound - saturation.lowerBound) * Self.fraction(seed, shift: 32)
  }

  private static func circularGap(from: Double, to: Double) -> Double {
    let raw = (to - from).truncatingRemainder(dividingBy: degreesPerCircle)
    return raw <= 0 ? raw + degreesPerCircle : raw
  }

  // MARK: - Luminance / brightness math

  private func brightnessFor(luminance target: Double, hue: Double, saturation: Double) -> Double {
    var low = 0.0
    var high = 1.0
    for _ in 0..<Self.brightnessSearchIterations {
      let mid = (low + high) / 2
      if Self.luminance(hue: hue, saturation: saturation, brightness: mid) < target {
        low = mid
      } else {
        high = mid
      }
    }
    return (low + high) / 2
  }

  private static func luminance(hue: Double, saturation: Double, brightness: Double) -> Double {
    let rgb = hsbToRGB(hue: hue, saturation: saturation, brightness: brightness)
    return WCAGContrast.relativeLuminance(red: rgb.red, green: rgb.green, blue: rgb.blue)
  }

  /// Standard sRGB HSB-to-RGB conversion (`hue` in degrees). Matches `Color(hue:saturation:brightness:)`
  /// closely enough that the solver's luminance prediction tracks the rendered color; the
  /// `contrastSafetyMargin` absorbs any residual drift.
  private static func hsbToRGB(hue: Double, saturation: Double, brightness: Double) -> (red: Double, green: Double, blue: Double) {
    let h = (hue.truncatingRemainder(dividingBy: degreesPerCircle) + degreesPerCircle).truncatingRemainder(dividingBy: degreesPerCircle) / 60
    let sector = Int(h) % 6
    let fraction = h - Double(Int(h))
    let p = brightness * (1 - saturation)
    let q = brightness * (1 - fraction * saturation)
    let t = brightness * (1 - (1 - fraction) * saturation)
    switch sector {
    case 0: return (brightness, t, p)
    case 1: return (q, brightness, p)
    case 2: return (p, brightness, t)
    case 3: return (p, q, brightness)
    case 4: return (t, p, brightness)
    default: return (brightness, p, q)
    }
  }

  // MARK: - Hashing

  /// FNV-1a 64-bit: a stable, well-distributed hash. Stable across launches (unlike `Hasher`, which
  /// is per-process seeded) and free of the order-insensitivity that makes XOR-folding collide
  /// anagrams and cluster on a continuous hue space.
  private static func fnv1a(_ string: String) -> UInt64 {
    var hash: UInt64 = 0xCBF2_9CE4_8422_2325
    for byte in string.utf8 {
      hash ^= UInt64(byte)
      hash = hash &* 0x0000_0100_0000_01B3
    }
    return hash
  }

  /// A 0...1 fraction from 16 bits of the hash starting at `shift`, giving independent draws for
  /// hue jitter, saturation, and brightness variety from one hash.
  private static func fraction(_ seed: UInt64, shift: UInt64) -> Double {
    Double((seed >> shift) & 0xFFFF) / Double(0xFFFF)
  }
}
