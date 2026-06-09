import SwiftUI

/// Provides animated navigation header with title and subtitle across iOS versions.
/// On iOS 26+, uses native `.navigationSubtitle()` which animates with the navigation transition.
/// On iOS 18-25, uses a custom toolbar principal item that appears after the view renders.
struct NavigationHeaderModifier: ViewModifier {
    /// Minimum scale floor for the legacy iOS 18-25 subtitle, anchored on iPhone SE-class
    /// width (375pt) — caption2 (~12pt) × 0.7 ≈ 8.4pt keeps long region names readable
    /// rather than clipped.
    private static let legacySubtitleMinimumScaleFactor: CGFloat = 0.7

    let title: String
    let subtitle: String
    let subtitleAccessibilityLabel: String?

    @State private var showHeader = false

    func body(content: Content) -> some View {
        #if os(iOS)
        if #available(iOS 26, *) {
            // TODO: subtitleAccessibilityLabel is not applied here — .navigationSubtitle()
            // renders in system chrome with no public API to override its accessibility label.
            // VoiceOver may read separators (e.g. "·") literally. Verify with VoiceOver testing.
            content
                .navigationTitle(title)
                .navigationSubtitle(subtitle)
                .navigationBarTitleDisplayMode(.inline)
        } else {
            legacyHeader(content: content)
        }
        #else
        legacyHeader(content: content)
        #endif
    }

    private func legacyHeader(content: Content) -> some View {
        content
            .navigationTitle(title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                if showHeader {
                    ToolbarItem(placement: .principal) {
                        VStack(spacing: 0) {
                            Text(title)
                                .font(.headline)

                            Text(subtitle)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .minimumScaleFactor(Self.legacySubtitleMinimumScaleFactor)
                                .truncationMode(.tail)
                                .accessibilityLabel(subtitleAccessibilityLabel ?? subtitle)
                        }
                    }
                }
            }
            .task {
                // .task runs after first render, so header appears after navigation begins
                withAnimation {
                    showHeader = true
                }
            }
    }
}

extension View {
    /// Applies an animated navigation header with title and subtitle.
    /// Uses native `.navigationSubtitle()` on iOS 26+, with animated fallback for earlier versions.
    func navigationHeader(title: String, subtitle: String, subtitleAccessibilityLabel: String? = nil) -> some View {
        modifier(NavigationHeaderModifier(title: title, subtitle: subtitle, subtitleAccessibilityLabel: subtitleAccessibilityLabel))
    }
}
