import MC1Services
import SwiftUI

struct DeviceScanView: View {
  @Environment(\.appState) private var appState
  @State private var showTroubleshooting = false
  @State private var showingWiFiConnection = false
  @State private var showingNoDeviceSheet = false
  @State private var pairingSuccessTrigger = false
  @State private var failureHapticTrigger = false
  @State private var demoModeUnlockTrigger = false
  @State private var didInitiatePairing = false
  @State private var showDemoModeAlert = false
  @State private var otherAppDeviceID: UUID?
  private var demoModeManager = DemoModeManager.shared

  @ScaledMetric(relativeTo: .body) private var largeSpacing = OnboardingMetrics.largeSpacing
  @ScaledMetric(relativeTo: .body) private var mediumSpacing = OnboardingMetrics.mediumSpacing
  @ScaledMetric(relativeTo: .body) private var minHitTarget = OnboardingMetrics.minHitTarget

  private var hasConnectedDevice: Bool {
    appState.connectionState == .ready
  }

  var body: some View {
    VStack(spacing: largeSpacing) {
      VStack(spacing: mediumSpacing) {
        PulsingAntenna()

        Text(L10n.Onboarding.DeviceScan.title)
          .font(.largeTitle)
          .bold()
          .accessibilityAddTraits(.isHeader)
          .simultaneousGesture(
            TapGesture(count: 3).onEnded { unlockDemoMode() }
          )

        if !hasConnectedDevice {
          Text(L10n.Onboarding.DeviceScan.subtitle)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal)
        }
      }

      Spacer()

      if hasConnectedDevice, !didInitiatePairing {
        VStack(spacing: mediumSpacing) {
          Text(L10n.Onboarding.DeviceScan.alreadyPaired)
            .font(.title2)
            .multilineTextAlignment(.center)
        }
        .padding()
      }

      Spacer()

      VStack(spacing: mediumSpacing) {
        if hasConnectedDevice {
          Button {
            appState.onboarding.onboardingPath.append(.region)
          } label: {
            Text(L10n.Onboarding.DeviceScan.continue)
              .font(.headline)
              .frame(maxWidth: .infinity)
              .padding()
          }
          .liquidGlassProminentButtonStyle()
        } else {
          primaryCTA

          ViewThatFits {
            HStack(spacing: largeSpacing) {
              secondaryButtons
            }
            VStack(spacing: mediumSpacing) {
              secondaryButtons
            }
          }
          .frame(minHeight: minHitTarget)

          Button(L10n.Onboarding.DeviceScan.noDeviceYet) {
            showingNoDeviceSheet = true
          }
          .font(.subheadline)
          .foregroundStyle(.secondary)
          .padding(.vertical, 8)
          .frame(minHeight: minHitTarget)
        }
      }
      .padding(.horizontal)
      .padding(.bottom)
    }
    .sensoryFeedback(.success, trigger: pairingSuccessTrigger)
    .sensoryFeedback(.success, trigger: demoModeUnlockTrigger)
    .sensoryFeedback(.error, trigger: failureHapticTrigger)
    .onChange(of: hasConnectedDevice) { _, connected in
      guard didInitiatePairing, connected else { return }
      pairingSuccessTrigger.toggle()
    }
    .onChange(of: appState.connectionUI.showingConnectionFailedAlert) { _, showing in
      guard didInitiatePairing, showing else { return }
      failureHapticTrigger.toggle()
    }
    .onChange(of: appState.connectionUI.otherAppWarningDeviceID) { _, newValue in
      // Other-app failures land on ConnectionUIState. Mirror the warning ID so
      // the recovery CTA stays "Retry connection" after the alert is dismissed.
      if let id = newValue {
        otherAppDeviceID = id
        if didInitiatePairing { failureHapticTrigger.toggle() }
      }
    }
    .sheet(isPresented: $showTroubleshooting) {
      TroubleshootingSheet()
    }
    .sheet(isPresented: $showingWiFiConnection) {
      WiFiConnectionSheet()
    }
    .sheet(isPresented: $showingNoDeviceSheet) {
      NoDeviceSheet()
    }
    .alert(L10n.Onboarding.DeviceScan.DemoModeAlert.title, isPresented: $showDemoModeAlert) {
      Button(L10n.Localizable.Common.ok) {}
    } message: {
      Text(L10n.Onboarding.DeviceScan.DemoModeAlert.message)
    }
  }

  @ViewBuilder
  private var primaryCTA: some View {
    #if targetEnvironment(simulator)
      Button { connectSimulator() } label: { ctaLabel(systemImage: "laptopcomputer.and.iphone",
                                                      text: L10n.Onboarding.DeviceScan.connectSimulator) }
        .liquidGlassProminentButtonStyle()
        .disabled(appState.connectionUI.isBusy)
    #else
      if demoModeManager.isEnabled {
        Button { connectSimulator() } label: { ctaLabel(systemImage: "play.circle.fill",
                                                        text: L10n.Onboarding.DeviceScan.continueDemo) }
          .liquidGlassProminentButtonStyle()
          .disabled(appState.connectionUI.isBusy)
      } else if let deviceID = otherAppDeviceID {
        Button { retryConnection(deviceID: deviceID) } label: { ctaLabel(systemImage: "arrow.clockwise.circle.fill",
                                                                         text: L10n.Onboarding.DeviceScan.retryConnection) }
          .liquidGlassProminentButtonStyle()
          .disabled(appState.connectionUI.isBusy)
      } else {
        Button { startPairing() } label: { ctaLabel(systemImage: "plus.circle.fill",
                                                    text: L10n.Onboarding.DeviceScan.addDevice) }
          .liquidGlassProminentButtonStyle()
          .disabled(appState.connectionUI.isBusy)
      }
    #endif
  }

  @ViewBuilder
  private var secondaryButtons: some View {
    Button(L10n.Onboarding.DeviceScan.connectViaWifi) { showingWiFiConnection = true }
      .font(.subheadline)
      .foregroundStyle(.secondary)

    Button(L10n.Onboarding.DeviceScan.deviceNotAppearing) { showTroubleshooting = true }
      .font(.subheadline)
      .foregroundStyle(.secondary)
  }

  private func ctaLabel(systemImage: String, text: String) -> some View {
    HStack(spacing: 8) {
      if appState.connectionUI.isBusy {
        ProgressView().controlSize(.small)
        Text(L10n.Onboarding.DeviceScan.connecting)
      } else {
        Image(systemName: systemImage)
        Text(text)
      }
    }
    .font(.headline)
    .frame(maxWidth: .infinity)
    .padding()
  }

  private func startPairing() {
    didInitiatePairing = true
    appState.startDeviceScan()
  }

  private func retryConnection(deviceID: UUID) {
    appState.connectionUI.isBusy = true
    Task { @MainActor in
      defer { appState.connectionUI.isBusy = false }
      do {
        try await appState.connectionManager.connect(to: deviceID, forceReconnect: true)
        await appState.wireServicesIfConnected()
        appState.onboarding.onboardingPath.append(.region)
      } catch {
        appState.connectionUI.presentSavedDeviceConnectFailure(deviceID: deviceID, error: error)
      }
    }
  }

  private func connectSimulator() {
    appState.connectionUI.isBusy = true
    didInitiatePairing = true
    Task { @MainActor in
      defer { appState.connectionUI.isBusy = false }
      do {
        try await appState.connectionManager.simulatorConnect()
        await appState.wireServicesIfConnected()
        appState.onboarding.onboardingPath.append(.region)
      } catch {
        appState.connectionUI.presentConnectionFailure(message: error.userFacingMessage)
      }
    }
  }

  /// 3-tap easter egg for App Store reviewers — preserved per CLAUDE.md `demo-mode-is-for-app-store-reviewers`.
  private func unlockDemoMode() {
    demoModeManager.unlock()
    demoModeUnlockTrigger.toggle()
    showDemoModeAlert = true
  }
}

#Preview {
  DeviceScanView()
    .environment(\.appState, AppState())
}
