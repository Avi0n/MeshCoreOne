import SwiftUI

/// The single placeholder block shown while a rich preview resolves: a
/// `tertiarySystemFill` rounded rectangle with the shared `Shimmer` overlay.
/// Replaces the three near-identical copies that lived in the link, image, and
/// map fragment views, so a change to the placeholder fill or motion behavior
/// happens once. Reads Reduce Motion itself so call sites do not repeat the
/// `Shimmer(isActive:)` wiring, and is accessibility-hidden because it carries
/// no information of its own.
struct PreviewSkeleton: View {
    var cornerRadius: CGFloat = RichPreviewMetrics.cornerRadius

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(Color(.tertiarySystemFill))
            .modifier(Shimmer(isActive: !reduceMotion))
            .accessibilityHidden(true)
    }
}
