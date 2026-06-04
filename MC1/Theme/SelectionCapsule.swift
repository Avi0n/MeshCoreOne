import SwiftUI

/// Rounded selection capsule filled with the solid accent (matching the prominent filter pill).
/// Stands in for the native selection highlight, which any `listRowBackground` opts the row out of.
/// `horizontalInset` pulls the capsule in from the row-background edges: `.plain` lists draw their
/// background edge-to-edge under the floating sidebar and need the inset to clear it, while grouped
/// and sidebar lists already inset the background and pass `0` so the capsule frames the row content
/// with its built-in margins instead of biting into the trailing text.
struct SelectionCapsule: View {
    let theme: Theme
    var horizontalInset: CGFloat = defaultHorizontalInset

    private static let defaultHorizontalInset: CGFloat = 8
    private static let cornerRadius: CGFloat = 12
    private static let verticalInset: CGFloat = 2

    var body: some View {
        RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
            .fill(theme.accentColor)
            .padding(.horizontal, horizontalInset)
            .padding(.vertical, Self.verticalInset)
    }
}
