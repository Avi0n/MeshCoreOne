import SwiftUI
import MC1Services
import os

/// Third screen of onboarding - pairs MeshCore device via AccessorySetupKit
struct DeviceScanView: View {
    @Environment(\.appState) private var appState
    @State private var showTroubleshooting = false
    @State private var showingWiFiConnection = false
    @State private var pairingSuccessTrigger = false
    @State private var demoModeUnlockTrigger = false
    @State private var didInitiatePairing = false
    @State private var tapTimes: [Date] = []
    @State private var showDemoModeAlert = false
    @State private var otherAppDeviceID: UUID?
    private var demoModeManager = DemoModeManager.shared

    private var hasConnectedDevice: Bool {
        appState.connectionState == .ready
    }

    var body: some View {
        VStack(spacing: 24) {
            // Header
            VStack(spacing: 12) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 50))
                    .foregroundStyle(.tint)
                    .frame(height: 120)

                Button {
                    handleTitleTap()
                } label: {
                    Text(L10n.Onboarding.DeviceScan.title)
                        .font(.largeTitle)
                        .bold()
                }
                .buttonStyle(.plain)

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
                VStack(spacing: 12) {
                    Text("\(L10n.Onboarding.DeviceScan.alreadyPaired) 🎉")
                        .font(.title2)
                        .multilineTextAlignment(.center)
                }
                .padding()
            } else if !hasConnectedDevice {
                // Instructions
                VStack(alignment: .leading, spacing: 16) {
                    instructionRow(number: 1, text: L10n.Onboarding.DeviceScan.Instruction.powerOn)
                    instructionRow(number: 2, text: L10n.Onboarding.DeviceScan.Instruction.tapAdd)
                    instructionRow(number: 3, text: L10n.Onboarding.DeviceScan.Instruction.selectDevice)
                    instructionRow(number: 4, text: L10n.Onboarding.DeviceScan.Instruction.enterPin)
                }
                .padding()
                .liquidGlass(in: .rect(cornerRadius: 12))
                .padding(.horizontal)
            }

            Spacer()

            // Action buttons
            VStack(spacing: 12) {
                if hasConnectedDevice {
                    Button {
                        appState.onboarding.onboardingPath.append(.radioPreset)
                    } label: {
                        Text(L10n.Onboarding.DeviceScan.continue)
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                    }
                    .liquidGlassProminentButtonStyle()
                } else {
                    #if targetEnvironment(simulator)
                    // Simulator build - always show Connect Simulator
                    Button {
                        connectSimulator()
                    } label: {
                        HStack(spacing: 8) {
                            if appState.connectionUI.isBusy {
                                ProgressView()
                                    .controlSize(.small)
                                Text(L10n.Onboarding.DeviceScan.connecting)
                            } else {
                                Image(systemName: "laptopcomputer.and.iphone")
                                Text(L10n.Onboarding.DeviceScan.connectSimulator)
                            }
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                    }
                    .liquidGlassProminentButtonStyle()
                    .disabled(appState.connectionUI.isBusy)
                    #else
                    // Device build - show demo mode button if enabled, otherwise Add Device
                    if demoModeManager.isEnabled {
                        Button {
                            connectSimulator()
                        } label: {
                            HStack(spacing: 8) {
                                if appState.connectionUI.isBusy {
                                    ProgressView()
                                        .controlSize(.small)
                                    Text(L10n.Onboarding.DeviceScan.connecting)
                                } else {
                                    Image(systemName: "play.circle.fill")
                                    Text(L10n.Onboarding.DeviceScan.continueDemo)
                                }
                            }
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                        }
                        .liquidGlassProminentButtonStyle()
                        .disabled(appState.connectionUI.isBusy)
                    } else if let deviceID = otherAppDeviceID {
                        Button {
                            retryConnection(deviceID: deviceID)
                        } label: {
                            HStack(spacing: 8) {
                                if appState.connectionUI.isBusy {
                                    ProgressView()
                                        .controlSize(.small)
                                    Text(L10n.Onboarding.DeviceScan.connecting)
                                } else {
                                    Image(systemName: "arrow.clockwise.circle.fill")
                                    Text(L10n.Onboarding.DeviceScan.retryConnection)
                                }
                            }
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                        }
                        .liquidGlassProminentButtonStyle()
                        .disabled(appState.connectionUI.isBusy)
                    } else {
                        Button {
                            startPairing()
                        } label: {
                            HStack(spacing: 8) {
                                if appState.connectionUI.isBusy {
                                    ProgressView()
                                        .controlSize(.small)
                                    Text(L10n.Onboarding.DeviceScan.connecting)
                                } else {
                                    Image(systemName: "plus.circle.fill")
                                    Text(L10n.Onboarding.DeviceScan.addDevice)
                                }
                            }
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                        }
                        .liquidGlassProminentButtonStyle()
                        .disabled(appState.connectionUI.isBusy)
                    }
                    #endif

                    Button(L10n.Onboarding.DeviceScan.deviceNotAppearing) {
                        showTroubleshooting = true
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                    Button(L10n.Onboarding.DeviceScan.connectViaWifi) {
                        showingWiFiConnection = true
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
        .sensoryFeedback(.success, trigger: pairingSuccessTrigger)
        .sensoryFeedback(.success, trigger: demoModeUnlockTrigger)
        .sheet(isPresented: $showTroubleshooting) {
            TroubleshootingSheet()
        }
        .sheet(isPresented: $showingWiFiConnection) {
            WiFiConnectionSheet()
        }
        .alert(L10n.Onboarding.DeviceScan.DemoModeAlert.title, isPresented: $showDemoModeAlert) {
            Button(L10n.Localizable.Common.ok) { }
        } message: {
            Text(L10n.Onboarding.DeviceScan.DemoModeAlert.message)
        }
    }

    private func instructionRow(number: Int, text: String) -> some View {
        HStack(spacing: 12) {
            Text("\(number)")
                .font(.caption.bold())
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(.tint, in: .circle)

            Text(text)
                .font(.subheadline)
        }
    }

    private func startPairing() {
        appState.connectionUI.isBusy = true
        didInitiatePairing = true
        // Clear any previous pairing failure state
        appState.connectionUI.failedPairingDeviceID = nil

        Task { @MainActor in
            defer { appState.connectionUI.isBusy = false }

            do {
                try await appState.connectionManager.pairNewDevice()
                await appState.wireServicesIfConnected()
                pairingSuccessTrigger.toggle()
                appState.onboarding.onboardingPath.append(.radioPreset)
            } catch AccessorySetupKitError.pickerDismissed {
                // User cancelled - no error to show
            } catch AccessorySetupKitError.pickerAlreadyActive {
                // Picker is already showing - ignore
            } catch let pairingError as PairingError {
                if case .deviceConnectedToOtherApp(let deviceID) = pairingError {
                    otherAppDeviceID = deviceID
                }
                appState.connectionUI.presentPairingFailure(pairingError)
            } catch {
                // Other errors - show via AppState's alert
                appState.connectionUI.presentConnectionFailure(message: error.localizedDescription)
            }
        }
    }

    private func retryConnection(deviceID: UUID) {
        appState.connectionUI.isBusy = true

        Task { @MainActor in
            defer { appState.connectionUI.isBusy = false }

            do {
                try await appState.connectionManager.connect(to: deviceID)
                await appState.wireServicesIfConnected()
                pairingSuccessTrigger.toggle()
                appState.onboarding.onboardingPath.append(.radioPreset)
            } catch BLEError.deviceConnectedToOtherApp {
                appState.connectionUI.otherAppWarningDeviceID = deviceID
            } catch {
                appState.connectionUI.presentConnectionFailure(message: error.localizedDescription)
            }
        }
    }

    private func connectSimulator() {
        appState.connectionUI.isBusy = true
        didInitiatePairing = true

        Task {
            defer { appState.connectionUI.isBusy = false }

            do {
                try await appState.connectionManager.simulatorConnect()
                await appState.wireServicesIfConnected()
                pairingSuccessTrigger.toggle()
                appState.onboarding.onboardingPath.append(.radioPreset)
            } catch {
                appState.connectionUI.presentConnectionFailure(message: "Simulator connection failed: \(error.localizedDescription)")
            }
        }
    }

    private func handleTitleTap() {
        let now = Date()
        tapTimes.append(now)

        // Keep only taps within last 1 second
        tapTimes = tapTimes.filter { now.timeIntervalSince($0) <= 1.0 }

        // Check if we have 3 taps within 1 second
        if tapTimes.count >= 3 {
            tapTimes.removeAll()
            demoModeManager.unlock()
            demoModeUnlockTrigger.toggle()
            showDemoModeAlert = true
        }
    }
}

/// Troubleshooting sheet for when devices don't appear in the ASK picker
/// Per Apple Developer Forums: Factory-reset devices won't appear until the stale
/// system pairing is removed via removeAccessory()
private struct TroubleshootingSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @State private var isClearing = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.powerOn, systemImage: "power")
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.moveCloser, systemImage: "iphone.radiowaves.left.and.right")
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.restart, systemImage: "arrow.clockwise")
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.BasicChecks.header)
                }

                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.explanation)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.confirmationNote)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Button {
                            clearStalePairings()
                        } label: {
                            HStack {
                                if isClearing {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Image(systemName: "trash")
                                }
                                Text(L10n.Onboarding.Troubleshooting.FactoryReset.clearPairing)
                            }
                        }
                        .disabled(isClearing || appState.connectionManager.pairedAccessoriesCount == 0)
                    }
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.FactoryReset.header)
                } footer: {
                    if appState.connectionManager.pairedAccessoriesCount == 0 {
                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.noPairings)
                    } else {
                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.pairingsFound(appState.connectionManager.pairedAccessoriesCount))
                    }
                }

                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L10n.Onboarding.Troubleshooting.SystemSettings.manageAccessories)
                            .font(.subheadline)
                        Text(L10n.Onboarding.Troubleshooting.SystemSettings.path)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.SystemSettings.header)
                }
            }
            .navigationTitle(L10n.Onboarding.Troubleshooting.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.Localizable.Common.done) {
                        dismiss()
                    }
                }
            }
        }
    }

    private func clearStalePairings() {
        isClearing = true

        Task {
            defer { isClearing = false }

            // Remove all stale pairings via ConnectionManager
            // Note: iOS 26 shows a confirmation dialog for each removal
            await appState.connectionManager.clearStalePairings()

            dismiss()
        }
    }
}

#Preview {
    DeviceScanView()
        .environment(\.appState, AppState())
}
