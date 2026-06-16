import SwiftUI

/// The shared hero chrome for the rich-preview families. Reserves an
/// aspect-ratio frame clamped to the rich-preview min/max hero height so the
/// bubble does not jump when image bytes arrive after layout, then overlays the
/// resolved content (an image, a skeleton, or a retry affordance). The reserved
/// frame and corner radius previously lived in three separate fragment views;
/// sharing them keeps the height-stability contract in one place.
///
/// Opaque by design: per the project's Liquid Glass rule, preview cards never
/// use glass because they must stay readable under outdoor glare and in
/// high-contrast modes.
struct RichPreviewCard<Content: View>: View {
    /// Which corners receive the card radius.
    enum CornerStyle {
        /// Top corners only, for a hero stacked above in-card text rows.
        case top
        /// No clip; the enclosing view supplies the rounding.
        case none
    }

    let aspect: CGFloat
    let minHeight: CGFloat
    let maxHeight: CGFloat
    var cornerStyle: CornerStyle = .none
    @ViewBuilder let content: () -> Content

    var body: some View {
        let hero = Color.clear
            .aspectRatio(aspect, contentMode: .fit)
            .frame(minHeight: minHeight, maxHeight: maxHeight)
            .frame(maxWidth: .infinity)
            .overlay { content() }

        switch cornerStyle {
        case .top:
            hero.clipShape(.rect(
                topLeadingRadius: RichPreviewMetrics.cornerRadius,
                topTrailingRadius: RichPreviewMetrics.cornerRadius
            ))
        case .none:
            hero
        }
    }
}
