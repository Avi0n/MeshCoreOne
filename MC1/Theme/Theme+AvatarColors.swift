import SwiftUI
import UIKit

/// Resolves a theme's avatar and identity colors for the current appearance. Identity colors are
/// solved to clear WCAG AA against every surface they render on (list canvas and incoming bubble),
/// so a given identity keeps one legible color across the avatar, its sender name, and its mentions.
///
/// Main-actor isolated: these resolve adaptive `UIColor`s and build trait collections, which UIKit
/// requires on the main actor. Every caller (the avatar views and the main-actor message bake)
/// already runs there.
@MainActor
extension Theme {

    /// Relative luminances of the surfaces avatars and identity names sit on, resolved for the given
    /// appearance. The canvas (or the system background, for the surfaceless default theme) and the
    /// incoming bubble are the two surfaces an identity color must stay legible against.
    func avatarSurfaceLuminances(colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> [Double] {
        let traits = Self.traitCollection(colorScheme: colorScheme, contrast: contrast)
        let surfaces = [self.surfaces?.canvas ?? Color(.systemBackground), incomingBubbleColor]
        return surfaces.map { WCAGContrast.relativeLuminance(of: UIColor($0).resolvedColor(with: traits)) }
    }

    /// Relative luminance of the only surface a category avatar (channel / repeater / room) sits on:
    /// the list canvas. Unlike identity colors, these never render as a sender name on the incoming
    /// bubble, so constraining them against it only forces them needlessly bright.
    func categorySurfaceLuminance(colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> Double {
        let traits = Self.traitCollection(colorScheme: colorScheme, contrast: contrast)
        let canvas = self.surfaces?.canvas ?? Color(.systemBackground)
        return WCAGContrast.relativeLuminance(of: UIColor(canvas).resolvedColor(with: traits))
    }

    /// Color for a contact avatar / channel sender name / mention with the given identity name.
    func identityColor(forName name: String, colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> Color {
        identityGamut.color(
            forName: name,
            backgroundLuminances: avatarSurfaceLuminances(colorScheme: colorScheme, contrast: contrast),
            highContrast: contrast == .increased
        )
    }

    /// The single fixed color for a channel / repeater / room avatar. The System theme pins these to
    /// legacy values; every other theme resolves each category at its curated `categoryHue`, against
    /// the list canvas only and at the darkest legible brightness, so the swatch is a deep, on-theme
    /// color (and the three stay distinct even when a room and a channel share the Chats list).
    func categoryAvatarColor(_ category: AvatarCategory, colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> Color {
        if let override = categoryAvatarOverride { return override.color(for: category) }
        return identityGamut.color(
            forName: category.gamutSeed,
            backgroundLuminances: [categorySurfaceLuminance(colorScheme: colorScheme, contrast: contrast)],
            highContrast: contrast == .increased,
            atHue: categoryHue(for: category),
            atVariety: Self.categoryDarkestVariety
        )
    }

    /// Glyph (initials / icon) color for an avatar of the given fill. The System category override
    /// keeps the historical white glyph; gamut-derived avatars adapt so the glyph reads whether the
    /// fill resolved light (dark appearance) or dark (light appearance).
    func avatarGlyphColor(
        forFill fill: Color,
        usesCategoryOverride: Bool,
        colorScheme: ColorScheme,
        contrast: ColorSchemeContrast
    ) -> Color {
        guard !usesCategoryOverride else { return .white }
        let traits = Self.traitCollection(colorScheme: colorScheme, contrast: contrast)
        let luminance = WCAGContrast.relativeLuminance(of: UIColor(fill).resolvedColor(with: traits))
        return IdentityGamut.glyphColor(forFillLuminance: luminance)
    }

    /// True when this theme pins its category avatars to fixed colors (System) rather than deriving
    /// them from the gamut. Drives the glyph choice in the avatar views.
    var usesCategoryAvatarOverride: Bool { categoryAvatarOverride != nil }

    static func traitCollection(colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> UITraitCollection {
        UITraitCollection { traits in
            traits.userInterfaceStyle = colorScheme == .dark ? .dark : .light
            traits.accessibilityContrast = contrast == .increased ? .high : .unspecified
        }
    }
}

extension Theme {
    /// Brightness draw category avatars resolve at: the darkest legible value (no variety), so they
    /// read as deep, on-theme swatches instead of the washed-out brights the bubble surface forced.
    static let categoryDarkestVariety = 0.0

    /// Hue (degrees) a category avatar resolves at: the theme's curated `categoryHues` value, or a
    /// distinct on-anchor pick for a gamut theme that hasn't curated them. Pure hue selection, so it
    /// stays off the main actor for the color bake and tests.
    func categoryHue(for category: AvatarCategory) -> Double {
        if let curated = categoryHues?.hue(for: category) { return curated }
        let hues = identityGamut.distinctAnchorHues(forNames: AvatarCategory.anchorPriority.map(\.gamutSeed))
        switch category {
        case .channel: return hues[0]
        case .repeater: return hues[1]
        case .room: return hues[2]
        }
    }
}
