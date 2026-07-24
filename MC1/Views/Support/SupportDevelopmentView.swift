import MC1Services
import SwiftUI

struct SupportDevelopmentView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  @State private var hasRetriedThisAppearance = false
  @State private var restoreSuccessTrigger = 0
  @State private var showPurchaseThanks = false

  var body: some View {
    // A `@Bindable` local is required to project `$storeState.errorMessage` from the
    // environment-owned `@Observable` (a plain computed property cannot supply a Binding).
    @Bindable var storeState = appState.storeState

    return List {
      SupportHeaderSection()

      if storeState.pendingPurchase != nil {
        PendingPurchaseBanner()
      }

      ThemesPurchaseSection(onPurchaseSucceeded: presentPurchaseThanks)
      ContributionsSection(onPurchaseSucceeded: presentPurchaseThanks)
      restoreSection(storeState)
      RefundLinkSection()
      SupportContactSection()
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Support.title)
    .navigationBarTitleDisplayMode(.inline)
    .errorAlert($storeState.errorMessage, retryAction: { Task { await storeState.service.load() } })
    .sensoryFeedback(.success, trigger: restoreSuccessTrigger)
    .sheet(isPresented: $showPurchaseThanks) {
      PurchaseThankYouSheet()
    }
    .onChange(of: storeState.service.ownedThemeIDs) {
      storeState.reconcilePendingPurchase()
    }
    .onChange(of: storeState.restoreState) { _, newValue in
      if newValue == .completed { restoreSuccessTrigger += 1 }
    }
    .onAppear {
      // Clear a pending banner whose entitlement already arrived while this screen was
      // off-screen: the `.onChange(of: ownedThemeIDs)` above fires only on a live change,
      // not on re-entry, so an Ask-to-Buy approval received elsewhere would otherwise leave
      // a stale "Awaiting approval" banner until app close.
      storeState.reconcilePendingPurchase()
      if storeState.service.loadState == .failed, !hasRetriedThisAppearance {
        hasRetriedThisAppearance = true
        Task { await storeState.service.load() }
      }
    }
    .onDisappear { hasRetriedThisAppearance = false }
  }

  /// Called after a verified immediate purchase. Defers one turn so list rebuild and any
  /// StoreKit confirmation dismiss can settle before the thank-you sheet presents.
  private func presentPurchaseThanks() {
    Task { @MainActor in
      await Task.yield()
      showPurchaseThanks = true
    }
  }

  private func restoreSection(_ storeState: StoreState) -> some View {
    Section {
      Button {
        Task { await storeState.restorePurchases() }
      } label: {
        HStack {
          Text(storeState.restoreState == .syncing
            ? L10n.Settings.Support.Restore.syncing
            : L10n.Settings.Support.Restore.button)
          Spacer()
          if storeState.restoreState == .syncing { ProgressView() }
        }
      }
      .disabled(storeState.restoreState == .syncing)
    }
    .themedRowBackground(theme)
  }
}
