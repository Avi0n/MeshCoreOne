import MC1Services
import SwiftUI

/// Radio preset selector with region-based filtering
struct RadioPresetSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @State private var selectedPresetID: String?
  @State private var isApplying = false
  @State private var errorMessage: String?
  @State private var hasInitialized = false
  @State private var retryAlert = RetryAlertState()
  @State private var isRepeatEnabled: Bool = false
  @State private var isApplyingRepeat = false
  @State private var showRepeatConfirmation = false

  private var startupTaskID: String {
    let deviceID = appState.connectedDevice?.id.uuidString ?? "none"
    let syncPhase = appState.connectionUI.currentSyncPhase.map { String(describing: $0) } ?? "none"
    return "\(deviceID)-\(String(describing: appState.connectionState))-\(syncPhase)"
  }

  private var presets: [RadioPreset] {
    let region = appState.regionSelection
    let activeID = currentPreset?.id
    return RadioPresets.presetsForLocale().filter {
      RadioPresets.isSelectable($0, in: region) || $0.id == activeID
    }
  }

  private var repeatPresets: [RadioPreset] {
    RadioPresets.repeatPresets
  }

  private var currentPreset: RadioPreset? {
    guard let device = appState.connectedDevice else { return nil }
    return RadioPresets.matchingPreset(
      frequencyKHz: device.frequency,
      bandwidthKHz: device.bandwidth,
      spreadingFactor: device.spreadingFactor,
      codingRate: device.codingRate
    )
  }

  /// Finds the repeat preset closest to the device's current frequency.
  private var closestRepeatPreset: RadioPreset? {
    guard let device = appState.connectedDevice else { return nil }
    let deviceFreqKHz = device.frequency
    return repeatPresets.min(by: {
      abs(Int($0.frequencyKHz) - Int(deviceFreqKHz)) < abs(Int($1.frequencyKHz) - Int(deviceFreqKHz))
    })
  }

  /// Matches the device's current radio params against repeat presets.
  private var currentRepeatPreset: RadioPreset? {
    guard let device = appState.connectedDevice else { return nil }
    return RadioPresets.matchingRepeatPreset(
      frequencyKHz: device.frequency,
      bandwidthKHz: device.bandwidth,
      spreadingFactor: device.spreadingFactor,
      codingRate: device.codingRate
    )
  }

  private var mismatchHint: String? {
    guard let region = appState.regionSelection,
          let current = currentPreset else { return nil }
    let regionPresets = RadioPresets.presets(for: region).map(\.id)
    guard !regionPresets.contains(current.id) else { return nil }
    return L10n.Settings.Radio.mismatchHint(
      current.name, RegionalAreas.displayName(for: region)
    )
  }

  private var currentMatchingPresetID: String? {
    isRepeatEnabled ? currentRepeatPreset?.id : currentPreset?.id
  }

  var body: some View {
    Section {
      Picker(L10n.Settings.Radio.preset, selection: $selectedPresetID) {
        if isRepeatEnabled {
          Text(L10n.Settings.BatteryCurve.custom).tag(nil as String?)
          ForEach(repeatPresets) { preset in
            Section(preset.repeatSectionHeader ?? "") {
              Text(preset.name).tag(preset.id as String?)
            }
          }
        } else {
          // Only show Custom when device is not using a preset
          if currentPreset == nil {
            Text(L10n.Settings.BatteryCurve.custom).tag(nil as String?)
          }

          ForEach(RadioRegion.allCases, id: \.self) { region in
            let regionPresets = presets.filter { $0.region == region }
            if !regionPresets.isEmpty {
              Section(region.rawValue) {
                ForEach(regionPresets) { preset in
                  Text(preset.name).tag(preset.id as String?)
                }
              }
            }
          }
        }
      }
      .onChange(of: selectedPresetID) { _, newValue in
        // Skip the initial value set from onAppear
        guard hasInitialized else { return }
        // Apply if user selected a preset (newValue is non-nil)
        guard let newID = newValue else { return }
        applyPreset(id: newID)
      }
      .radioDisabled(for: appState.connectionState, or: isApplying || isApplyingRepeat)

      let detailPresets = isRepeatEnabled ? repeatPresets : presets
      if let preset = detailPresets.first(where: { $0.id == selectedPresetID }) {
        RadioParameterText(
          frequencyMHz: preset.frequencyMHz,
          bandwidthKHz: preset.bandwidthKHz,
          spreadingFactor: preset.spreadingFactor,
          codingRate: preset.codingRate
        )
        .foregroundStyle(.secondary)
      } else if let device = appState.connectedDevice {
        RadioParameterText(
          frequencyMHz: Double(device.frequency) / 1000.0,
          bandwidthKHz: Double(device.bandwidth) / 1000.0,
          spreadingFactor: device.spreadingFactor,
          codingRate: device.codingRate
        )
        .foregroundStyle(.secondary)
      }

      if appState.connectedDevice?.supportsClientRepeat == true {
        Toggle(isOn: $isRepeatEnabled) {
          Text(L10n.Settings.Radio.repeatMode)
          Text(L10n.Settings.Radio.RepeatMode.footer)
        }
        .accessibilityHint(L10n.Settings.Radio.RepeatMode.accessibilityHint)
        .onChange(of: isRepeatEnabled) { _, newValue in
          guard hasInitialized else { return }
          if newValue {
            hasInitialized = false
            isRepeatEnabled = false
            Task { @MainActor in
              hasInitialized = true
            }
            showRepeatConfirmation = true
          } else {
            disableRepeatMode()
          }
        }
        .disabled(isApplying || isApplyingRepeat)
      }
    } header: {
      Text(L10n.Settings.Radio.header)
    } footer: {
      VStack(alignment: .leading, spacing: 6) {
        Text(L10n.Settings.Radio.footer)
        if let region = appState.regionSelection {
          Text(L10n.Settings.Radio.regionFooter(RegionalAreas.displayName(for: region)))
        }
        if let mismatch = mismatchHint {
          Text(mismatch)
            .foregroundStyle(.orange)
        }
      }
    }
    .themedRowBackground(theme)
    .onAppear {
      isRepeatEnabled = appState.connectedDevice?.clientRepeat ?? false
      selectedPresetID = currentMatchingPresetID
      // Mark as initialized after setting initial value
      // Using task to defer to next run loop, after onChange processes
      Task { @MainActor in
        hasInitialized = true
      }
    }
    .task(id: startupTaskID) {
      guard appState.canRunSettingsStartupReads,
            let settingsService = appState.services?.settingsService else { return }
      _ = try? await settingsService.getSelfInfo()
    }
    .onChange(of: currentPreset?.id) { _, newPresetID in
      // Sync picker when device settings change externally (e.g., from Advanced Settings)
      guard !isRepeatEnabled else { return }
      hasInitialized = false
      selectedPresetID = newPresetID
      Task { @MainActor in
        hasInitialized = true
      }
    }
    .onChange(of: currentRepeatPreset?.id) { _, newPresetID in
      guard isRepeatEnabled else { return }
      hasInitialized = false
      selectedPresetID = newPresetID
      Task { @MainActor in
        hasInitialized = true
      }
    }
    .onChange(of: appState.connectedDevice?.clientRepeat) { _, newValue in
      let newRepeatEnabled = newValue ?? false
      if newRepeatEnabled != isRepeatEnabled {
        hasInitialized = false
        isRepeatEnabled = newRepeatEnabled
        selectedPresetID = currentMatchingPresetID
        Task { @MainActor in
          hasInitialized = true
        }
      }
    }
    .errorAlert($errorMessage)
    .retryAlert(retryAlert)
    .alert(L10n.Settings.Radio.RepeatMode.Confirm.title, isPresented: $showRepeatConfirmation) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      Button(L10n.Settings.Radio.RepeatMode.Confirm.enable) {
        enableRepeatMode()
      }
    } message: {
      Text(L10n.Settings.Radio.RepeatMode.Confirm.message)
    }
  }

  private func applyPreset(id: String) {
    let allPresets = isRepeatEnabled ? repeatPresets : presets
    guard let preset = allPresets.first(where: { $0.id == id }) else { return }

    isApplying = true
    Task {
      do {
        guard let settingsService = appState.services?.settingsService else {
          throw ConnectionError.notConnected
        }
        if isRepeatEnabled {
          _ = try await settingsService.setRadioParamsVerified(
            frequencyKHz: preset.frequencyKHz,
            bandwidthKHz: preset.bandwidthHz,
            spreadingFactor: preset.spreadingFactor,
            codingRate: preset.codingRate,
            clientRepeat: true
          )
        } else {
          _ = try await settingsService.applyRadioPresetVerified(preset)
        }
        retryAlert.reset()
      } catch {
        selectedPresetID = currentMatchingPresetID
        if let retryable = error as? SettingsServiceError, retryable.isRetryable {
          retryAlert.show(
            message: retryable.userFacingMessage,
            onRetry: { applyPreset(id: id) },
            onMaxRetriesExceeded: { dismiss() }
          )
        } else {
          errorMessage = error.userFacingMessage
        }
      }
      isApplying = false
    }
  }

  private func enableRepeatMode() {
    guard let preset = closestRepeatPreset else { return }

    // Persist current radio settings to Device model before switching
    appState.connectionManager.savePreRepeatSettings()

    // Swap picker to repeat presets and select closest frequency
    hasInitialized = false
    isRepeatEnabled = true
    selectedPresetID = preset.id
    Task { @MainActor in
      hasInitialized = true
      applyPreset(id: preset.id)
    }
  }

  private func disableRepeatMode() {
    guard let device = appState.connectedDevice else { return }
    isApplyingRepeat = true
    Task {
      do {
        guard let settingsService = appState.services?.settingsService else {
          throw ConnectionError.notConnected
        }

        // Restore saved radio settings, or fall back to current params
        let freq = device.preRepeatFrequency ?? device.frequency
        let bw = device.preRepeatBandwidth ?? device.bandwidth
        let sf = device.preRepeatSpreadingFactor ?? device.spreadingFactor
        let cr = device.preRepeatCodingRate ?? device.codingRate

        _ = try await settingsService.setRadioParamsVerified(
          frequencyKHz: freq,
          bandwidthKHz: bw,
          spreadingFactor: sf,
          codingRate: cr,
          clientRepeat: false
        )

        // Clear persisted pre-repeat settings
        appState.connectionManager.clearPreRepeatSettings()

        // Swap picker back to normal presets
        hasInitialized = false
        selectedPresetID = currentPreset?.id
        Task { @MainActor in
          hasInitialized = true
        }
        retryAlert.reset()
      } catch let error as SettingsServiceError where error.isRetryable {
        isRepeatEnabled = true // Revert
        retryAlert.show(
          message: error.userFacingMessage,
          onRetry: { disableRepeatMode() },
          onMaxRetriesExceeded: { dismiss() }
        )
      } catch {
        errorMessage = error.userFacingMessage
        isRepeatEnabled = true // Revert
      }
      isApplyingRepeat = false
    }
  }
}
