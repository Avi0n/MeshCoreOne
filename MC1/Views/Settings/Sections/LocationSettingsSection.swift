import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "LocationSettings")

/// Location settings: set location, share publicly, auto-update from GPS
struct LocationSettingsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @Environment(\.openURL) private var openURL
  @Binding var showingLocationPicker: Bool
  @State private var shareLocation = false
  @State private var autoUpdateLocation = false
  @State private var gpsSource: GPSSource = .phone
  @State private var deviceHasGPS = false
  @State private var deviceGPSEnabled = false
  @State private var errorMessage: String?
  @State private var showLocationDeniedAlert = false
  @State private var retryAlert = RetryAlertState()
  @State private var isSaving = false
  @State private var didLoad = false

  private let devicePreferenceStore = DevicePreferenceStore()

  private var shouldPollDeviceGPS: Bool {
    autoUpdateLocation && gpsSource == .device && deviceGPSEnabled
  }

  var body: some View {
    Group {
      Section {
        Button {
          showingLocationPicker = true
        } label: {
          HStack {
            TintedLabel(L10n.Settings.Node.setLocation, systemImage: "mappin.and.ellipse")
            Spacer()
            if let device = appState.connectedDevice,
               device.latitude != 0 || device.longitude != 0 {
              Text(L10n.Settings.Node.locationSet)
                .foregroundStyle(.secondary)
            } else {
              Text(L10n.Settings.Node.locationNotSet)
                .foregroundStyle(.tertiary)
            }
            Image(systemName: "chevron.right")
              .font(.caption)
              .foregroundStyle(.tertiary)
          }
        }
        .foregroundStyle(.primary)
        .radioDisabled(for: appState.connectionState, or: isSaving || autoUpdateLocation)

        Toggle(isOn: $shareLocation) {
          TintedLabel(L10n.Settings.Node.shareLocationPublicly, systemImage: "location")
        }
        .onChange(of: shareLocation) { _, newValue in
          guard didLoad else { return }
          updateShareLocation(newValue)
        }
        .radioDisabled(for: appState.connectionState, or: isSaving)

        Toggle(isOn: $autoUpdateLocation) {
          TintedLabel(L10n.Settings.Location.autoUpdate, systemImage: "location.circle")
        }
        .onChange(of: autoUpdateLocation) { _, newValue in
          handleAutoUpdateChange(newValue)
        }
        .radioDisabled(for: appState.connectionState, or: isSaving)

        if autoUpdateLocation {
          if deviceHasGPS {
            Picker(L10n.Settings.Location.gpsSource, selection: $gpsSource) {
              Text(L10n.Settings.Location.GpsSource.phone).tag(GPSSource.phone)
              Text(L10n.Settings.Location.GpsSource.device).tag(GPSSource.device)
            }
            .onChange(of: gpsSource) { oldValue, newValue in
              handleGPSSourceChange(from: oldValue, to: newValue)
            }
            .radioDisabled(for: appState.connectionState, or: isSaving)
          } else {
            LabeledContent(L10n.Settings.Location.gpsSource) {
              Text(L10n.Settings.Location.GpsSource.phone)
                .foregroundStyle(.secondary)
            }
          }
        }
      } header: {
        Text(L10n.Settings.Location.header)
      } footer: {
        Text(L10n.Settings.Location.footer)
      }
      .themedRowBackground(theme)

      if deviceHasGPS {
        Section {
          Toggle(isOn: deviceGPSBinding) {
            TintedLabel(L10n.Settings.Location.DeviceGps.toggle, systemImage: "location.circle")
          }
          .radioDisabled(for: appState.connectionState, or: isSaving)
        } header: {
          Text(L10n.Settings.Location.DeviceGps.header)
        } footer: {
          Text(L10n.Settings.Location.DeviceGps.footer)
        }
        .themedRowBackground(theme)
      }
    }
    .task(id: startupTaskID) {
      loadPreferences()
      guard appState.canRunSettingsStartupReads else {
        logger.debug("Deferring location settings startup reads until sync is less contended")
        return
      }
      await loadDeviceGPSState()
    }
    .task(id: shouldPollDeviceGPS) {
      guard shouldPollDeviceGPS,
            let settingsService = appState.services?.settingsService else { return }
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(3))
        guard let device = appState.connectedDevice,
              device.latitude == 0, device.longitude == 0 else { break }
        try? await settingsService.refreshDeviceInfo()
      }
    }
    .errorAlert($errorMessage)
    .retryAlert(retryAlert)
    .alert(L10n.Onboarding.Permissions.LocationAlert.title, isPresented: $showLocationDeniedAlert) {
      Button(L10n.Onboarding.Permissions.LocationAlert.openSettings) {
        if let url = URL(string: UIApplication.openSettingsURLString) {
          openURL(url)
        }
      }
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
    } message: {
      Text(L10n.Onboarding.Permissions.LocationAlert.message)
    }
  }

  private var startupTaskID: String {
    let deviceID = appState.connectedDevice?.id.uuidString ?? "none"
    let syncPhase = appState.connectionUI.currentSyncPhase.map { String(describing: $0) } ?? "none"
    return "\(deviceID)-\(String(describing: appState.connectionState))-\(syncPhase)-picker:\(showingLocationPicker)"
  }

  private var deviceGPSBinding: Binding<Bool> {
    Binding(
      get: { deviceGPSEnabled },
      set: { updateDeviceGPSToggle($0) }
    )
  }

  private func loadPreferences() {
    if let device = appState.connectedDevice {
      shareLocation = device.sharesLocationPublicly
      autoUpdateLocation = devicePreferenceStore.isAutoUpdateLocationEnabled(deviceID: device.id)
      gpsSource = devicePreferenceStore.gpsSource(deviceID: device.id)
    }
    didLoad = true
  }

  private func loadDeviceGPSState() async {
    guard appState.canRunSettingsStartupReads else { return }
    guard let settingsService = appState.services?.settingsService else { return }
    do {
      let state = try await settingsService.getDeviceGPSState()
      deviceHasGPS = state.isSupported
      deviceGPSEnabled = state.isEnabled
      if let deviceID = appState.connectedDevice?.id,
         state.isEnabled,
         !devicePreferenceStore.hasSetGPSSource(deviceID: deviceID) {
        gpsSource = .device
        devicePreferenceStore.setGPSSource(.device, deviceID: deviceID)
      }
      if state.isEnabled {
        try? await settingsService.refreshDeviceInfo()
      }
    } catch {
      deviceHasGPS = false
      deviceGPSEnabled = false
    }
  }

  private func handleAutoUpdateChange(_ newValue: Bool) {
    guard let deviceID = appState.connectedDevice?.id else { return }
    if newValue, gpsSource == .phone, appState.locationService.isLocationDenied {
      autoUpdateLocation = false
      showLocationDeniedAlert = true
      return
    }
    devicePreferenceStore.setAutoUpdateLocationEnabled(newValue, deviceID: deviceID)
    if newValue, gpsSource == .phone {
      appState.locationService.requestPermissionIfNeeded()
    }
    if gpsSource == .device {
      if newValue {
        saveDeviceGPS(
          true,
          onFailure: {
            autoUpdateLocation = false
            devicePreferenceStore.setAutoUpdateLocationEnabled(false, deviceID: deviceID)
          }
        )
      } else if deviceGPSEnabled {
        saveDeviceGPS(false)
      }
    }
    if shareLocation {
      updateShareLocation(shareLocation)
    }
  }

  private func handleGPSSourceChange(from oldValue: GPSSource, to newValue: GPSSource) {
    guard let deviceID = appState.connectedDevice?.id else { return }
    if newValue == .phone, appState.locationService.isLocationDenied {
      gpsSource = oldValue
      showLocationDeniedAlert = true
      return
    }

    devicePreferenceStore.setGPSSource(newValue, deviceID: deviceID)
    if newValue == .phone {
      appState.locationService.requestPermissionIfNeeded()
      if deviceGPSEnabled {
        saveDeviceGPS(false)
      }
    } else if autoUpdateLocation {
      saveDeviceGPS(
        true,
        onFailure: {
          gpsSource = oldValue
          devicePreferenceStore.setGPSSource(oldValue, deviceID: deviceID)
        }
      )
    }

    if shareLocation {
      updateShareLocation(shareLocation)
    }
  }

  private func updateDeviceGPSToggle(_ enabled: Bool) {
    guard let deviceID = appState.connectedDevice?.id else { return }
    let shouldDisableAutoUpdate = !enabled && autoUpdateLocation && gpsSource == .device
    saveDeviceGPS(enabled) {
      if shouldDisableAutoUpdate {
        autoUpdateLocation = false
        devicePreferenceStore.setAutoUpdateLocationEnabled(false, deviceID: deviceID)
        try await applyDeviceGPSDisabledSharePolicyIfNeeded()
      }
    }
  }

  private func updateShareLocation(_ share: Bool) {
    guard let device = appState.connectedDevice,
          let settingsService = appState.services?.settingsService else { return }
    let policy = selectedAdvertLocationPolicy(share: share)

    if device.advertLocationPolicyMode == policy {
      return
    }

    isSaving = true
    Task {
      do {
        try await applyShareLocationPolicy(policy, using: device, settingsService: settingsService)
        retryAlert.reset()
      } catch let error as SettingsServiceError where error.isRetryable {
        shareLocation = !share
        retryAlert.show(
          message: error.userFacingMessage,
          onRetry: { updateShareLocation(share) },
          onMaxRetriesExceeded: { dismiss() }
        )
      } catch {
        shareLocation = !share
        errorMessage = error.userFacingMessage
      }
      isSaving = false
    }
  }

  private func selectedAdvertLocationPolicy(share: Bool) -> AdvertLocationPolicy {
    guard share else { return .none }
    if autoUpdateLocation, deviceHasGPS, gpsSource == .device {
      return .share
    }
    return .prefs
  }

  private func saveDeviceGPS(
    _ enabled: Bool,
    onSuccess: (@MainActor () async throws -> Void)? = nil,
    onFailure: (@MainActor () -> Void)? = nil
  ) {
    guard let settingsService = appState.services?.settingsService else { return }

    let previousEnabled = deviceGPSEnabled
    isSaving = true

    Task {
      do {
        let state = try await settingsService.setDeviceGPSEnabledVerified(enabled)
        deviceHasGPS = state.isSupported
        deviceGPSEnabled = state.isEnabled
        if let onSuccess {
          try await onSuccess()
        }
        retryAlert.reset()
      } catch let error as SettingsServiceError where error.isRetryable {
        deviceGPSEnabled = previousEnabled
        onFailure?()
        retryAlert.show(
          message: error.userFacingMessage,
          onRetry: { saveDeviceGPS(enabled, onSuccess: onSuccess, onFailure: onFailure) },
          onMaxRetriesExceeded: { dismiss() }
        )
      } catch {
        deviceGPSEnabled = previousEnabled
        onFailure?()
        errorMessage = error.userFacingMessage
      }
      isSaving = false
    }
  }

  private func applyDeviceGPSDisabledSharePolicyIfNeeded() async throws {
    guard shareLocation,
          let device = appState.connectedDevice,
          device.advertLocationPolicyMode == .share,
          let settingsService = appState.services?.settingsService else { return }

    try await applyShareLocationPolicy(.prefs, using: device, settingsService: settingsService)
  }

  private func applyShareLocationPolicy(
    _ policy: AdvertLocationPolicy,
    using device: DeviceDTO,
    settingsService: SettingsService
  ) async throws {
    _ = try await settingsService.setOtherParamsVerified(from: device, advertLocationPolicy: policy)
  }
}
