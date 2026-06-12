import SwiftUI
import MC1Services

/// Manual radio parameter configuration
struct AdvancedRadioSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var frequency: Double?  // MHz
    @State private var bandwidth: UInt32?  // Hz
    @State private var spreadingFactor: Int?
    @State private var codingRate: Int?
    @State private var txPower: Int?  // dBm
    @State private var clientRepeat: Bool?
    @State private var hasLoaded = false
    @State private var isApplying = false
    @State private var showSuccess = false
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @FocusState private var focusedField: RadioField?

    private enum RadioField: Hashable {
        case frequency
        case txPower
    }

    private var settingsModified: Bool {
        guard let device = appState.connectedDevice else { return false }
        return clientRepeat != appState.connectedDevice?.clientRepeat ||
            frequency != Double(device.frequency) / 1000.0 ||
            bandwidth != RadioOptions.nearestBandwidth(to: device.bandwidth) ||
            spreadingFactor != Int(device.spreadingFactor) ||
            codingRate != Int(device.codingRate) ||
            txPower != Int(device.txPower)
    }

    private var canApply: Bool {
        appState.connectionState == .ready && settingsModified && !isApplying && !showSuccess
    }

    /// Combined hash of all radio settings for change detection
    private var deviceRadioSettingsHash: Int {
        var hasher = Hasher()
        hasher.combine(appState.connectedDevice?.frequency)
        hasher.combine(appState.connectedDevice?.bandwidth)
        hasher.combine(appState.connectedDevice?.spreadingFactor)
        hasher.combine(appState.connectedDevice?.codingRate)
        hasher.combine(appState.connectedDevice?.txPower)
        hasher.combine(appState.connectedDevice?.clientRepeat)
        return hasher.finalize()
    }

    var body: some View {
        Section {
            if !hasLoaded {
                ProgressView()
                    .frame(maxWidth: .infinity)
            } else {
            HStack {
                Text(L10n.Settings.AdvancedRadio.frequency)
                Spacer()
                TextField(
                    L10n.Settings.AdvancedRadio.frequencyPlaceholder,
                    value: $frequency,
                    format: .number.precision(.fractionLength(3)).locale(.posix)
                )
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 100)
                    .focused($focusedField, equals: .frequency)
            }

            Picker(L10n.Settings.AdvancedRadio.bandwidth, selection: $bandwidth) {
                ForEach(RadioOptions.bandwidthsHz, id: \.self) { bwHz in
                    Text(RadioOptions.formatBandwidth(bwHz))
                        .tag(bwHz as UInt32?)
                        .accessibilityLabel(L10n.Settings.AdvancedRadio.Accessibility.bandwidthLabel(RadioOptions.formatBandwidth(bwHz)))
                }
            }
            .pickerStyle(.menu)
            .tint(.primary)
            .accessibilityHint(L10n.Settings.AdvancedRadio.Accessibility.bandwidthHint)

            Picker(L10n.Settings.AdvancedRadio.spreadingFactor, selection: $spreadingFactor) {
                ForEach(RadioOptions.spreadingFactors, id: \.self) { spreadFactorOption in
                    Text(spreadFactorOption, format: .number)
                        .tag(spreadFactorOption as Int?)
                        .accessibilityLabel(L10n.Settings.AdvancedRadio.Accessibility.spreadingFactorLabel(spreadFactorOption))
                }
            }
            .pickerStyle(.menu)
            .tint(.primary)
            .accessibilityHint(L10n.Settings.AdvancedRadio.Accessibility.spreadingFactorHint)

            Picker(L10n.Settings.AdvancedRadio.codingRate, selection: $codingRate) {
                ForEach(RadioOptions.codingRates, id: \.self) { codeRateOption in
                    Text("\(codeRateOption)")
                        .tag(codeRateOption as Int?)
                        .accessibilityLabel(L10n.Settings.AdvancedRadio.Accessibility.codingRateLabel(codeRateOption))
                }
            }
            .pickerStyle(.menu)
            .tint(.primary)
            .accessibilityHint(L10n.Settings.AdvancedRadio.Accessibility.codingRateHint)

            HStack {
                Text(L10n.Settings.AdvancedRadio.txPower)
                Spacer()
                TextField(L10n.Settings.AdvancedRadio.txPowerPlaceholder, value: $txPower, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 60)
                    .focused($focusedField, equals: .txPower)
            }

            if appState.connectedDevice?.supportsClientRepeat == true {
                Toggle(isOn: Binding(
                    get: { clientRepeat ?? false },
                    set: { clientRepeat = $0 }
                )) {
                    Text(L10n.Settings.AdvancedRadio.repeatMode)
                    Text(L10n.Settings.AdvancedRadio.RepeatMode.footer)
                }
                .accessibilityHint(L10n.Settings.Radio.RepeatMode.accessibilityHint)
                .disabled(!hasLoaded)
            }

            Button {
                applySettings()
            } label: {
                AsyncActionLabel(isLoading: isApplying, showSuccess: showSuccess) {
                    Text(L10n.Settings.AdvancedRadio.apply)
                        .foregroundStyle(canApply ? Color.accentColor : .secondary)
                        .transition(.opacity)
                }
            }
            .radioDisabled(for: appState.connectionState, or: isApplying || showSuccess || !settingsModified)
            }
        } header: {
            Text(L10n.Settings.AdvancedRadio.header)
        } footer: {
            Text(L10n.Settings.AdvancedRadio.footer)
        }
        .themedRowBackground(theme)
        .onAppear {
            loadCurrentSettings()
        }
        .onChange(of: deviceRadioSettingsHash) { _, _ in
            loadCurrentSettings()
        }
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
    }

    private func loadCurrentSettings() {
        guard let device = appState.connectedDevice else { return }
        frequency = Double(device.frequency) / 1000.0
        // Use nearestBandwidth to handle devices with non-standard bandwidth values
        // or firmware float precision issues (e.g., 7799 Hz instead of 7800 Hz)
        bandwidth = RadioOptions.nearestBandwidth(to: device.bandwidth)
        spreadingFactor = Int(device.spreadingFactor)
        codingRate = Int(device.codingRate)
        txPower = Int(device.txPower)
        clientRepeat = device.clientRepeat
        hasLoaded = true
    }

    private func applySettings() {
        guard let freqMHz = frequency,
              let bandwidthHz = bandwidth,
              let spreadFactor = spreadingFactor,
              let codeRate = codingRate,
              let power = txPower,
              let settingsService = appState.services?.settingsService else {
            errorMessage = L10n.Settings.AdvancedRadio.invalidInput
            return
        }

        // Pickers enforce bandwidth, SF, and CR; frequency and TX power are free-text, so
        // validate them with non-trapping conversions before scaling into the wire fields.
        let scaledFreqKHz = (freqMHz * 1000).rounded()
        let freqInRange = freqMHz.isFinite
            && scaledFreqKHz >= Double(PacketBuilder.frequencyRangeKHz.lowerBound)
            && scaledFreqKHz <= Double(PacketBuilder.frequencyRangeKHz.upperBound)
        let maxTxPower = appState.connectedDevice?.maxTxPower ?? PacketBuilder.txPowerFloor
        guard freqInRange,
              let frequencyKHz = UInt32(exactly: scaledFreqKHz),
              let spreadFactorByte = UInt8(exactly: spreadFactor),
              let codeRateByte = UInt8(exactly: codeRate),
              let powerLevel = Int8(exactly: power),
              powerLevel >= PacketBuilder.txPowerFloor,
              powerLevel <= maxTxPower else {
            errorMessage = L10n.Settings.AdvancedRadio.invalidInput
            return
        }

        isApplying = true
        Task {
            do {
                // Save pre-repeat settings when enabling repeat mode
                let wasRepeat = appState.connectedDevice?.clientRepeat ?? false
                let willRepeat = clientRepeat ?? false
                if !wasRepeat && willRepeat {
                    appState.connectionManager.savePreRepeatSettings()
                }

                // Set radio params first
                _ = try await settingsService.setRadioParamsVerified(
                    frequencyKHz: frequencyKHz,
                    // Note: Parameter is misleadingly named "bandwidthKHz" but expects Hz.
                    // bandwidthHz is already UInt32 Hz from the picker, pass directly.
                    bandwidthKHz: bandwidthHz,
                    spreadingFactor: spreadFactorByte,
                    codingRate: codeRateByte,
                    clientRepeat: clientRepeat
                )

                // Clear pre-repeat settings when disabling repeat mode
                if wasRepeat && !willRepeat {
                    appState.connectionManager.clearPreRepeatSettings()
                }

                // Then set TX power
                _ = try await settingsService.setTxPowerVerified(powerLevel)

                focusedField = nil  // Dismiss keyboard on success
                retryAlert.reset()
                isApplying = false  // Clear before showing success

                // Show success checkmark briefly
                withAnimation {
                    showSuccess = true
                }
                try? await Task.sleep(for: .seconds(1.5))
                withAnimation {
                    showSuccess = false
                }
                return  // Skip the isApplying = false at the end
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.userFacingMessage,
                    onRetry: { applySettings() },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                errorMessage = error.userFacingMessage
            }
            isApplying = false
        }
    }
}
