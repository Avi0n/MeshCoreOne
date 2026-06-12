import SwiftUI
import MC1Services

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
                    Text(L10n.Onboarding.DeviceScan.subtitle)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            }

            Spacer()

            if hasConnectedDevice && !didInitiatePairing {
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
        .onChange(of: appState.connectionUI.otherAppWarningDeviceID) { _, newValue in
            // retryFailedPairingConnect surfaces other-app failures via the ConnectionUI alert
            // without going through startPairing's local catch, so we mirror the warning ID
            // into local state to keep the recovery CTA pinned to "Retry connection" after
            // the user dismisses the alert.
            if let id = newValue { otherAppDeviceID = id }
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
            Button(L10n.Localizable.Common.ok) { }
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

    @ViewBuilder
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
        appState.connectionUI.isBusy = true
        didInitiatePairing = true
        appState.connectionUI.failedPairingDeviceID = nil

        Task { @MainActor in
            defer { appState.connectionUI.isBusy = false }
            do {
                try await appState.connectionManager.pairNewDevice()
                await appState.wireServicesIfConnected()
                pairingSuccessTrigger.toggle()
                appState.onboarding.onboardingPath.append(.region)
            } catch DevicePairingError.cancelled {
            } catch DevicePairingError.alreadyInProgress {
            } catch let pairingError as PairingError {
                if case .deviceConnectedToOtherApp(let deviceID) = pairingError {
                    otherAppDeviceID = deviceID
                }
                failureHapticTrigger.toggle()
                appState.connectionUI.presentPairingFailure(pairingError)
            } catch {
                appState.connectionUI.presentConnectionFailure(message: error.userFacingMessage)
            }
        }
    }

    private func retryConnection(deviceID: UUID) {
        appState.connectionUI.isBusy = true
        Task { @MainActor in
            defer { appState.connectionUI.isBusy = false }
            do {
                try await appState.connectionManager.connect(to: deviceID, forceReconnect: true)
                await appState.wireServicesIfConnected()
                pairingSuccessTrigger.toggle()
                appState.onboarding.onboardingPath.append(.region)
            } catch BLEError.deviceConnectedToOtherApp {
                appState.connectionUI.otherAppWarningDeviceID = deviceID
            } catch {
                appState.connectionUI.presentConnectionFailure(message: error.userFacingMessage)
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
                pairingSuccessTrigger.toggle()
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
