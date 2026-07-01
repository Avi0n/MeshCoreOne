import MC1Services
import StoreKit
import SwiftUI

struct ContributionsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.purchase) private var purchase

  private var storeState: StoreState {
    appState.storeState
  }

  /// Ordered tip product IDs (low to high) with their high-value flag.
  private var contributions: [(id: String, highValue: Bool)] {
    [
      (StoreCatalog.Tip.coffee, false),
      (StoreCatalog.Tip.lunch, false),
      (StoreCatalog.Tip.dinner, false),
      (StoreCatalog.Tip.generous, false),
      (StoreCatalog.Tip.massive, true),
      (StoreCatalog.Tip.epic, true)
    ]
  }

  var body: some View {
    Section {
      ForEach(contributions, id: \.id) { contribution in
        if let product = storeState.service.product(for: contribution.id) {
          ContributionRow(
            displayName: product.displayName,
            displayPrice: product.displayPrice,
            requiresConfirmation: contribution.highValue,
            onPurchase: { await storeState.purchase(productID: contribution.id) { try await purchase($0) } }
          )
        }
      }
    } header: {
      Text(L10n.Settings.Support.Contributions.title)
    } footer: {
      Text(L10n.Settings.Support.Contributions.footer)
    }
    .themedRowBackground(theme)
  }
}
