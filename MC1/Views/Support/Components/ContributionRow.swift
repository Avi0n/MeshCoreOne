import SwiftUI

/// One tip row: name + price. Tapping the price purchases; high-value tips ($49.99 / $99.99)
/// confirm first to mitigate a fat-finger purchase. On success a checkmark briefly replaces the
/// price. "Contribution" naming avoids the TipKit identifier collision.
struct ContributionRow: View {
    let displayName: String
    let displayPrice: String?
    let requiresConfirmation: Bool
    let onPurchase: () async -> Bool

    @State private var showingConfirmation = false
    @State private var showingThanks = false
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
            Button(L10n.Localizable.Common.cancel, role: .cancel) { }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.Settings.Support.Accessibility.ContributionRow.label(displayName, displayPrice ?? ""))
        .accessibilityHint(L10n.Settings.Support.Accessibility.ContributionRow.hint)
    }

    @ViewBuilder
    private var priceButton: some View {
        if showingThanks {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .transition(.opacity)
        } else {
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
    }

    private func runPurchase() async {
        isPurchasing = true
        let succeeded = await onPurchase()
        isPurchasing = false
        guard succeeded else { return }
        withAnimation { showingThanks = true }
        AccessibilityNotification.Announcement(
            L10n.Settings.Support.Accessibility.tipConfirmAnnouncement
        ).post()
        try? await Task.sleep(for: .seconds(1.5))
        withAnimation { showingThanks = false }
    }
}
