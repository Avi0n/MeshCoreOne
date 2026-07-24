import SwiftUI

/// One tip row: name + price. Tapping the price purchases; high-value tips ($49.99 / $99.99)
/// confirm first to mitigate a fat-finger purchase. Success celebration is owned by the
/// Support screen (thank-you sheet). "Contribution" naming avoids the TipKit identifier collision.
struct ContributionRow: View {
  let displayName: String
  let displayPrice: String?
  let requiresConfirmation: Bool
  let onPurchase: () async -> Void

  @State private var showingConfirmation = false
  @State private var isPurchasing = false

  var body: some View {
    HStack(alignment: .center, spacing: 12) {
      Text(displayName).font(.body)
      Spacer()
      priceButton
    }
    .confirmationDialog(
      L10n.Settings.Support.Contributions.highValueConfirm(displayName),
      isPresented: $showingConfirmation,
      titleVisibility: .visible
    ) {
      Button(L10n.Settings.Support.Contributions.confirmButton(displayPrice ?? displayName)) {
        Task { await runPurchase() }
      }
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Settings.Support.Accessibility.ContributionRow.label(displayName, displayPrice ?? ""))
    .accessibilityHint(L10n.Settings.Support.Accessibility.ContributionRow.hint)
  }

  private var priceButton: some View {
    Button {
      if requiresConfirmation {
        showingConfirmation = true
      } else {
        Task { await runPurchase() }
      }
    } label: {
      if isPurchasing {
        ProgressView()
      } else {
        Text(displayPrice ?? "—").font(.callout.weight(.semibold))
      }
    }
    .buttonStyle(.bordered)
    .disabled(displayPrice == nil || isPurchasing)
  }

  private func runPurchase() async {
    isPurchasing = true
    await onPurchase()
    isPurchasing = false
  }
}
