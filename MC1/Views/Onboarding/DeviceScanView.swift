import MC1Services
import SwiftUI

struct DeviceScanView: View {
  private static let busyLabelLineLimit = 2
  private static let busyLabelMinimumScale: CGFloat = 0.8

  @Environment(\.appState) private var appState
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
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

  private var hasConnectedDevice: Bool {
    appState.connectionState == .ready
  }

  var body: some View {
    VStack(spacing: OnboardingMetrics.largeSpacing) {
      VStack(spacing: OnboardingMetrics.mediumSpacing) {
        PulsingAntenna()

        Text(L10n.Onboarding.DeviceScan.title)
          .font(.largeTitle)
          .bold()
          .accessibilityAddTraits(.isHeader)
          .simultaneousGesture(
            TapGesture(count: 3).onEnded { unlockDemoMode() }
          )

        if !hasConnectedDevice {
          Text(appState.isRecoveringPairing ? L10n.Onboarding.DeviceScan.rePairingSubtitle : L10n.Onboarding.DeviceScan.subtitle)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal)
        }
      }

      Spacer()

      if hasConnectedDevice, !didInitiatePairing {
        VStack(spacing: OnboardingMetrics.mediumSpacing) {
          Text(L10n.Onboarding.DeviceScan.alreadyPaired)
            .font(.title2)
            .multilineTextAlignment(.center)
        }
        .padding()
      }

      Spacer()

      VStack(spacing: OnboardingMetrics.mediumSpacing) {
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
            HStack(spacing: OnboardingMetrics.largeSpacing) {
              secondaryButtons
            }
            VStack(spacing: OnboardingMetrics.mediumSpacing) {
              secondaryButtons
            }
          }
          .frame(minHeight: OnboardingMetrics.minHitTarget)

          Button(L10n.Onboarding.DeviceScan.noDeviceYet) {
            showingNoDeviceSheet = true
          }
          .font(.subheadline)
          .foregroundStyle(.secondary)
          .padding(.vertical, 8)
          .frame(minHeight: OnboardingMetrics.minHitTarget)
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
    if appState.isRecoveringPairing {
      PairingRecoveryStatus()
        .font(.headline)
        .frame(maxWidth: .infinity)
        .padding()
    } else {
      #if targetEnvironment(simulator)
        Button { connectSimulator() } label: { ctaLabel(systemImage: "laptopcomputer.and.iphone",
                                                        text: L10n.Onboarding.DeviceScan.connectSimulator) }
          .liquidGlassProminentButtonStyle()
          .disabled(appState.connectionUI.isBusy)
          .accessibilityAddTraits(appState.connectionUI.isBusy ? .updatesFrequently : [])
      #else
        if demoModeManager.isEnabled {
          Button { connectSimulator() } label: { ctaLabel(systemImage: "play.circle.fill",
                                                          text: L10n.Onboarding.DeviceScan.continueDemo) }
            .liquidGlassProminentButtonStyle()
            .disabled(appState.connectionUI.isBusy)
            .accessibilityAddTraits(appState.connectionUI.isBusy ? .updatesFrequently : [])
        } else if let deviceID = otherAppDeviceID {
          Button { retryConnection(deviceID: deviceID) } label: { ctaLabel(systemImage: "arrow.clockwise.circle.fill",
                                                                           text: L10n.Onboarding.DeviceScan.retryConnection) }
            .liquidGlassProminentButtonStyle()
            .disabled(appState.connectionUI.isBusy)
            .accessibilityAddTraits(appState.connectionUI.isBusy ? .updatesFrequently : [])
        } else {
          Button { startPairing() } label: { ctaLabel(systemImage: "plus.circle.fill",
                                                      text: L10n.Onboarding.DeviceScan.addDevice) }
            .liquidGlassProminentButtonStyle()
            .disabled(appState.connectionUI.isBusy)
            .accessibilityAddTraits(appState.connectionUI.isBusy ? .updatesFrequently : [])
        }
      #endif
    }
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

  private var busyTitle: String {
    appState.statusPillState.busyProgressTitle
  }

  private func ctaLabel(systemImage: String, text: String) -> some View {
    HStack(spacing: 8) {
      if appState.connectionUI.isBusy {
        ProgressView().controlSize(.small)
        Text(busyTitle)
          .lineLimit(Self.busyLabelLineLimit)
          .multilineTextAlignment(.center)
          .minimumScaleFactor(Self.busyLabelMinimumScale)
          .contentTransition(reduceMotion ? .identity : .opacity)
          .animation(reduceMotion ? nil : .default, value: busyTitle)
      } else {
        Image(systemName: systemImage)
        Text(text)
      }
    }
    .font(.headline)
    .frame(maxWidth: .infinity)
    .padding()
    .onChange(of: busyTitle) { _, newTitle in
      guard appState.connectionUI.isBusy, newTitle != L10n.Localizable.Common.Status.connecting else { return }
      appState.connectionUI.announceConnectionState(newTitle)
    }
  }

  private func startPairing() {
    didInitiatePairing = true
    appState.startDeviceScan()
  }

  private func retryConnection(deviceID: UUID) {
    let actionID = appState.connectionUI.beginConnectionAction()
    Task { @MainActor in
      guard appState.connectionUI.isCurrentConnectionAction(actionID) else { return }
      defer { appState.connectionUI.finishConnectionAction(actionID) }
      do {
        let result = try await appState.connectionManager.connect(to: deviceID, forceReconnect: true)
        guard case let .operational(snapshot) = result, appState.connectionManager.isCurrent(snapshot) else { return }
        await appState.wireServices(for: snapshot)
        guard appState.connectionManager.isCurrent(snapshot) else { return }
        appState.onboarding.onboardingPath.append(.region)
      } catch {
        guard let failure = appState.currentConnectionFailure(error) else { return }
        appState.connectionUI.presentSavedDeviceConnectFailure(deviceID: deviceID, error: failure)
      }
    }
  }

  private func connectSimulator() {
    let actionID = appState.connectionUI.beginConnectionAction()
    didInitiatePairing = true
    Task { @MainActor in
      guard appState.connectionUI.isCurrentConnectionAction(actionID) else { return }
      defer { appState.connectionUI.finishConnectionAction(actionID) }
      do {
        let result = try await appState.connectionManager.simulatorConnect()
        guard case let .operational(snapshot) = result, appState.connectionManager.isCurrent(snapshot) else { return }
        await appState.wireServices(for: snapshot)
        guard appState.connectionManager.isCurrent(snapshot) else { return }
        appState.onboarding.onboardingPath.append(.region)
      } catch {
        guard let failure = appState.currentConnectionFailure(error) else { return }
        appState.connectionUI.presentConnectionFailure(message: failure.userFacingMessage)
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
