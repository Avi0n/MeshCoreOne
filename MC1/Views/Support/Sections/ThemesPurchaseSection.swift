import MC1Services
import StoreKit
import SwiftUI

struct ThemesPurchaseSection: View {
  let onPurchaseSucceeded: () -> Void

  @Environment(\.appState) private var appState
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @Environment(\.purchase) private var purchase

  @ScaledMetric(relativeTo: .body) private var gridItemMinimum = ThemeCardMetrics.gridItemMinimum
  @ScaledMetric(relativeTo: .body) private var gridSpacing = ThemeCardMetrics.gridSpacing
  @ScaledMetric(relativeTo: .body) private var allUnlockedEmojiSize = ThemeCardMetrics.allUnlockedEmojiSize
  @ScaledMetric(relativeTo: .body) private var allUnlockedSpacing = ThemeCardMetrics.allUnlockedSpacing
  @ScaledMetric(relativeTo: .body) private var allUnlockedVerticalPadding = ThemeCardMetrics.allUnlockedVerticalPadding

  private var storeState: StoreState {
    appState.storeState
  }

  private var purchasableThemes: [Theme] {
    ThemeRegistry.allThemes.filter { $0.productID != nil }
  }

  private var ownsEveryTheme: Bool {
    storeState.service.ownedThemeIDs.isSuperset(of: StoreCatalog.Theme.bundledThemeIDs)
  }

  private var columns: [GridItem] {
    dynamicTypeSize.isAccessibilitySize
      ? [GridItem(.flexible())]
      : [GridItem(.adaptive(minimum: gridItemMinimum), spacing: gridSpacing)]
  }

  var body: some View {
    Section {
      if ownsEveryTheme {
        allUnlockedCard
      } else {
        LazyVGrid(columns: columns, spacing: gridSpacing) {
          ForEach(purchasableThemes) { theme in
            ThemePreviewCard(theme: theme, isOwned: isOwned(theme))
          }
        }
        .listRowInsets(ThemeCardMetrics.gridRowInsets)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)

        ThemeBundleCard(
          isPending: storeState.pendingPurchase?.productID == StoreCatalog.Theme.bundleAll,
          displayPrice: storeState.service.product(for: StoreCatalog.Theme.bundleAll)?.displayPrice,
          onPurchase: {
            if await storeState.purchase(productID: StoreCatalog.Theme.bundleAll, purchase: { try await purchase($0) }) {
              onPurchaseSucceeded()
            }
          }
        )
        .listRowInsets(ThemeCardMetrics.gridRowInsets)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
      }
    } header: {
      Text(L10n.Settings.Support.Themes.title)
    } footer: {
      if !appState.themeService.availableToCurrentUser().filter({ $0.productID != nil }).isEmpty {
        Text(L10n.Settings.Support.Themes.purchasedFooter)
      }
    }
  }

  private var allUnlockedCard: some View {
    VStack(spacing: allUnlockedSpacing) {
      Text(verbatim: "🎉")
        .font(.system(size: allUnlockedEmojiSize))
      Text(L10n.Settings.Support.Themes.allUnlocked)
        .font(.headline)
    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, allUnlockedVerticalPadding)
    .listRowInsets(ThemeCardMetrics.gridRowInsets)
    .listRowBackground(Color.clear)
    .listRowSeparator(.hidden)
    .accessibilityElement(children: .combine)
  }

  private func isOwned(_ theme: Theme) -> Bool {
    guard let productID = theme.productID else { return true }
    return storeState.service.ownedThemeIDs.contains(productID)
  }
}
