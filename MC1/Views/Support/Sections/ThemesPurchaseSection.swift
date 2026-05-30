import SwiftUI
import StoreKit
import MC1Services

struct ThemesPurchaseSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.purchase) private var purchase

    private var storeState: StoreState { appState.storeState }

    private var purchasableThemes: [Theme] {
        ThemeRegistry.allThemes.filter { $0.productID != nil }
    }

    private var ownsEveryTheme: Bool {
        storeState.service.ownedThemeIDs.isSuperset(of: StoreCatalog.Theme.purchasableIndividually)
    }

    private var columns: [GridItem] {
        dynamicTypeSize.isAccessibilitySize
            ? [GridItem(.flexible())]
            : [GridItem(.adaptive(minimum: ThemeCardMetrics.gridItemMinimum), spacing: ThemeCardMetrics.gridSpacing)]
    }

    var body: some View {
        Section {
            LazyVGrid(columns: columns, spacing: ThemeCardMetrics.gridSpacing) {
                ForEach(purchasableThemes) { theme in
                    ThemePreviewCard(theme: theme, isOwned: isOwned(theme))
                }
            }
            .listRowInsets(ThemeCardMetrics.gridRowInsets)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            if !ownsEveryTheme {
                ThemeBundleCard(
                    isPending: storeState.pendingPurchase?.productID == StoreCatalog.Theme.bundleAll,
                    displayPrice: storeState.service.product(for: StoreCatalog.Theme.bundleAll)?.displayPrice,
                    onPurchase: { await storeState.purchase(productID: StoreCatalog.Theme.bundleAll) { try await purchase($0) } }
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

    private func isOwned(_ theme: Theme) -> Bool {
        guard let productID = theme.productID else { return true }
        return storeState.service.ownedThemeIDs.contains(productID)
    }
}
