import MC1Services
import SwiftUI

struct ContentView: View {
  @Environment(\.appState) private var appState
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    @Bindable var connectionUI = appState.connectionUI

    productionShell
      .animation(.default, value: appState.onboarding.hasCompletedOnboarding)
      .onChange(of: scenePhase) { _, newPhase in
        if newPhase == .active {
          appState.handleBecameActive()
        }
      }
      .alert(
        connectionUI.connectionFailedTitle ?? L10n.Localizable.Alert.ConnectionFailed.title,
        isPresented: $connectionUI.showingConnectionFailedAlert
      ) {
        if appState.connectionUI.failedPairingDeviceID != nil {
          switch appState.connectionUI.pairingFailureKind {
          case .authentication, .pinRejected:
            // Bond can't proceed (a dead saved bond or a rejected fresh PIN), so
            // destructive remove is the recovery. The two kinds share these
            // buttons and differ only in the message copy set by the presenter.
            Button(L10n.Localizable.Alert.ConnectionFailed.removeAndRetry, role: .destructive) {
              appState.removeFailedPairingAndRetry()
            }
            .accessibilityLabel(L10n.Localizable.Accessibility.Alert.ConnectionFailed.removeAndRetry)
            Button(L10n.Localizable.Common.cancel, role: .cancel) {
              appState.connectionUI.failedPairingDeviceID = nil
            }
          case .transient, .none:
            // Transient variant — bond is still good, prefer non-destructive retry.
            // `.none` is unreachable in practice (every pairing-failure path routes
            // through `presentPairingFailure`, which always sets the kind). Folding
            // it into the safer branch ensures a missing kind can't promote a working
            // bond into the destructive recovery.
            Button(L10n.Localizable.Common.tryAgain) {
              Task { await appState.retryFailedPairingConnect() }
            }
            Button(L10n.Localizable.Alert.ConnectionFailed.removeAndRetry, role: .destructive) {
              appState.removeFailedPairingAndRetry()
            }
            .accessibilityLabel(L10n.Localizable.Accessibility.Alert.ConnectionFailed.removeAndRetry)
            Button(L10n.Localizable.Common.cancel, role: .cancel) {
              appState.connectionUI.failedPairingDeviceID = nil
            }
          }
        } else {
          Button(L10n.Localizable.Common.ok, role: .cancel) {}
        }
      } message: {
        Text(appState.connectionUI.connectionFailedMessage ?? L10n.Localizable.Alert.ConnectionFailed.defaultMessage)
      }
      .alert(
        L10n.Localizable.Alert.CouldNotConnect.title,
        isPresented: Binding(
          get: { appState.connectionUI.otherAppWarningDeviceID != nil },
          set: { if !$0 { appState.connectionUI.otherAppWarningDeviceID = nil } }
        )
      ) {
        Button(L10n.Localizable.Common.ok) {
          appState.connectionUI.otherAppWarningDeviceID = nil
        }
      } message: {
        Text(L10n.Localizable.Alert.CouldNotConnect.otherAppMessage)
      }
      // macOS "Designed for iPad" device picker. `bluetoothScanPicker` is nil on iOS, where
      // AccessorySetupKit presents its own system picker, so this sheet never appears there.
      .sheet(isPresented: Binding(
        get: { appState.connectionManager.bluetoothScanPicker?.isPresenting ?? false },
        set: { if !$0 { appState.connectionManager.bluetoothScanPicker?.cancel() } }
      )) {
        if let scanPicker = appState.connectionManager.bluetoothScanPicker {
          DeviceScannerSheet(picker: scanPicker)
        }
      }
      .sheet(item: $connectionUI.pendingSystemPairingSetup, onDismiss: {
        appState.handleSystemPairingSetupSheetDismissed()
      }) { prompt in
        SystemPairingSetupSheet(
          prompt: prompt,
          onForget: { appState.confirmSystemPairingSetup() },
          onCancel: { appState.cancelSystemPairingSetup() }
        )
      }
      // SwiftUI does not reliably co-present a sheet and an alert from the same host,
      // so the binding yields a release only while the connection UI above is quiescent;
      // `pendingRelease` stays set and re-presents on the next render once any alert clears.
      .sheet(item: Binding(
        get: { connectionUIQuiescent ? appState.whatsNew.pendingRelease : nil },
        set: { if $0 == nil { appState.whatsNew.markShown() } }
      )) { release in
        WhatsNewSheet(release: release)
      }
  }

  /// True when no connection alert or scan picker from this host is presenting.
  private var connectionUIQuiescent: Bool {
    !appState.connectionUI.showingConnectionFailedAlert
      && appState.connectionUI.otherAppWarningDeviceID == nil
      && appState.connectionUI.pendingSystemPairingSetup == nil
      && !(appState.connectionManager.bluetoothScanPicker?.isPresenting ?? false)
  }

  @ViewBuilder
  private var productionShell: some View {
    if appState.onboarding.hasCompletedOnboarding {
      MainTabView()
    } else {
      OnboardingView()
    }
  }
}

// MARK: - Onboarding View

struct OnboardingView: View {
  @Environment(\.appState) private var appState

  var body: some View {
    @Bindable var onboarding = appState.onboarding

    NavigationStack(path: $onboarding.onboardingPath) {
      WelcomeView()
        .navigationDestination(for: OnboardingStep.self) { step in
          switch step {
          case .welcome:
            WelcomeView()
          case .permissions:
            PermissionsView()
          case .pair:
            DeviceScanView()
          case .region:
            RegionStepView()
          case .preset:
            PresetStepView()
          }
        }
    }
  }
}

#Preview("Content View - Onboarding") {
  ContentView()
    .environment(\.appState, AppState())
}

#Preview("Content View - Main App") {
  let appState = AppState()
  appState.onboarding.hasCompletedOnboarding = true
  return ContentView()
    .environment(\.appState, appState)
}
