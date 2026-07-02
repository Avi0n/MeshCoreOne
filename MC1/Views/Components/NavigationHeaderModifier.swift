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
  /// iOS 26 only: render the title/subtitle inside a Liquid Glass capsule as a principal toolbar
  /// item, so the name stays legible above content that now scrolls edge-to-edge behind the bar.
  let glassTitleCapsule: Bool

  @State private var showHeader = false

  func body(content: Content) -> some View {
    #if os(iOS)
      if #available(iOS 26, *) {
        if glassTitleCapsule {
          content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
              ToolbarItem(placement: .principal) {
                // Self-contained fade: a toolbar item is hosted inside UIKit's nav bar, so an
                // animation driven from the modifier's state snaps instead of animating. Running
                // the fade from the item's own @State/onAppear keeps it in the hosted view's
                // rendering context, where it actually animates.
                GlassCapsuleTitle(
                  title: title,
                  subtitle: subtitle,
                  subtitleAccessibilityLabel: subtitleAccessibilityLabel,
                  minimumScaleFactor: Self.legacySubtitleMinimumScaleFactor
                )
              }
            }
        } else {
          // TODO: subtitleAccessibilityLabel is not applied here — .navigationSubtitle()
          // renders in system chrome with no public API to override its accessibility label.
          // VoiceOver may read separators (e.g. "·") literally. Verify with VoiceOver testing.
          content
            .navigationTitle(title)
            .navigationSubtitle(subtitle)
            .navigationBarTitleDisplayMode(.inline)
        }
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

/// iOS 26 Liquid Glass title capsule for the principal toolbar slot. Fades itself in on appear
/// so it doesn't pop in after the chat's first-load layout settles.
@available(iOS 26.0, *)
private struct GlassCapsuleTitle: View {
  let title: String
  let subtitle: String
  let subtitleAccessibilityLabel: String?
  let minimumScaleFactor: CGFloat

  @State private var visible = false

  var body: some View {
    VStack(spacing: 0) {
      Text(title)
        .font(.headline)

      if !subtitle.isEmpty {
        Text(subtitle)
          .font(.caption2)
          .foregroundStyle(.secondary)
          .lineLimit(1)
          .minimumScaleFactor(minimumScaleFactor)
          .truncationMode(.tail)
          .accessibilityLabel(subtitleAccessibilityLabel ?? subtitle)
      }
    }
    .padding(.horizontal, 14)
    .padding(.vertical, 5)
    .glassEffect(.regular, in: .capsule)
    .opacity(visible ? 1 : 0)
    .onAppear {
      withAnimation(.easeIn(duration: 0.25)) { visible = true }
    }
  }
}

extension View {
  /// Applies an animated navigation header with title and subtitle.
  /// Uses native `.navigationSubtitle()` on iOS 26+, with animated fallback for earlier versions.
  func navigationHeader(
    title: String,
    subtitle: String,
    subtitleAccessibilityLabel: String? = nil,
    glassTitleCapsule: Bool = false
  ) -> some View {
    modifier(NavigationHeaderModifier(
      title: title,
      subtitle: subtitle,
      subtitleAccessibilityLabel: subtitleAccessibilityLabel,
      glassTitleCapsule: glassTitleCapsule
    ))
  }
}
