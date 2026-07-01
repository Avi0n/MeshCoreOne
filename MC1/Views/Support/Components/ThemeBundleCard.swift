import MC1Services
import SwiftUI

/// Full-width bundle card: the single purchase control for every theme. Shown only while the
/// user does not already own the whole set, so it never renders an "owned" state.
struct ThemeBundleCard: View {
  let isPending: Bool
  let displayPrice: String?
  let onPurchase: () async -> Void

  @Environment(\.appTheme) private var theme
  @State private var isPurchasing = false

  var body: some View {
    VStack(alignment: .center, spacing: 10) {
      Text(L10n.Settings.Support.Bundle.title)
        .font(.headline)
      Text(L10n.Settings.Support.Bundle.subtitle)
        .font(.caption)
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
      action
    }
    .padding(12)
    .frame(maxWidth: .infinity, alignment: .center)
    .background(theme.surfaces?.card ?? Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: ThemeCardMetrics.cornerRadius))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(accessibilityLabel)
    .accessibilityHint(isActionable ? L10n.Settings.Support.Accessibility.BundleCard.lockedHint : "")
    // Only carry the button trait when the price has loaded and no purchase is in flight, so a
    // loading or pending card is not announced as something the user can activate.
    .accessibilityAddTraits(isActionable ? .isButton : [])
  }

  /// True once the card is an activatable purchase control (price loaded, nothing pending).
  private var isActionable: Bool {
    !isPending && displayPrice != nil
  }

  private var accessibilityLabel: String {
    if !isPending, displayPrice == nil {
      return L10n.Settings.Support.Accessibility.BundleCard.loadingLabel
    }
    return L10n.Settings.Support.Accessibility.BundleCard.lockedLabel(displayPrice ?? "")
  }

  @ViewBuilder
  private var action: some View {
    if isPurchasing {
      ProgressView()
        .frame(maxWidth: .infinity)
    } else if isPending {
      Label(L10n.Settings.Support.Pending.button, systemImage: "clock.badge.questionmark")
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.secondary)
    } else {
      Button {
        Task {
          isPurchasing = true
          await onPurchase()
          isPurchasing = false
        }
      } label: {
        Text(displayPrice ?? "—")
          .font(.subheadline.weight(.semibold))
          .frame(maxWidth: .infinity)
      }
      .buttonStyle(.borderedProminent)
      .disabled(displayPrice == nil)
    }
  }
}
