import MC1Services
import SwiftUI

/// Manual radio parameter configuration
struct AdvancedRadioSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @Binding var radioWriteInFlight: Bool
  @State private var frequency: Double? // MHz
  @State private var bandwidth: UInt32? // Hz
  @State private var spreadingFactor: Int?
  @State private var codingRate: Int?
  @State private var txPower: Int? // dBm
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
    return frequency != Double(device.frequency) / 1000.0 ||
      bandwidth != RadioOptions.nearestBandwidth(to: device.bandwidth) ||
      spreadingFactor != Int(device.spreadingFactor) ||
      codingRate != Int(device.codingRate) ||
      txPower != Int(device.txPower)
  }

  private var canApply: Bool {
    appState.connectionState == .ready && settingsModified && !isApplying && !showSuccess
      && !radioWriteInFlight
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
        .disabled(appState.connectedDevice?.clientRepeat == true)

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

        Button {
          applySettings()
        } label: {
          AsyncActionLabel(isLoading: isApplying, showSuccess: showSuccess) {
            Text(L10n.Settings.AdvancedRadio.apply)
              .foregroundStyle(canApply ? Color.accentColor : .secondary)
              .transition(.opacity)
          }
        }
        .radioDisabled(
          for: appState.connectionState,
          or: isApplying || showSuccess || !settingsModified || radioWriteInFlight
        )
      }
    } header: {
      Text(L10n.Settings.AdvancedRadio.header)
    } footer: {
      VStack(alignment: .leading, spacing: 6) {
        Text(L10n.Settings.AdvancedRadio.footer)
        if appState.connectedDevice?.clientRepeat == true {
          Text(L10n.Settings.AdvancedRadio.frequencyRepeatModeFooter)
        }
      }
    }
    .themedRowBackground(theme)
    .onAppear {
      loadCurrentSettings()
    }
    .onChange(of: deviceRadioSettingsHash) { _, _ in
      // Skip reloads only while this apply is in flight. Frequency and clientRepeat
      // arrive as separate events; reloading that intermediate state flickers the field.
      guard !isApplying else { return }
      if showSuccess, settingsModified {
        withAnimation {
          showSuccess = false
        }
      }
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
    hasLoaded = true
  }

  private func applySettings() {
    guard !radioWriteInFlight else { return }
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
    radioWriteInFlight = true
    Task {
      do {
        guard let device = appState.connectedDevice else {
          throw ConnectionError.notConnected
        }

        // Firmware treats an omitted repeat byte as Repeat Mode off. Read clientRepeat
        // and frequency from the radio at this call, not before the Task.
        let frequencyKHzToSend = device.clientRepeat ? device.frequency : frequencyKHz

        _ = try await settingsService.setRadioParamsVerified(
          frequencyKHz: frequencyKHzToSend,
          // Note: Parameter is misleadingly named "bandwidthKHz" but expects Hz.
          // bandwidthHz is already UInt32 Hz from the picker, pass directly.
          bandwidthKHz: bandwidthHz,
          spreadingFactor: spreadFactorByte,
          codingRate: codeRateByte,
          clientRepeat: device.clientRepeat
        )

        // Then set TX power
        _ = try await settingsService.setTxPowerVerified(powerLevel)

        focusedField = nil // Dismiss keyboard on success
        retryAlert.reset()
        isApplying = false
        radioWriteInFlight = false

        // Show success checkmark briefly
        withAnimation {
          showSuccess = true
        }
        try? await Task.sleep(for: .seconds(1.5))
        withAnimation {
          showSuccess = false
        }
        return // Skip the isApplying = false at the end
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
      radioWriteInFlight = false
    }
  }
}
