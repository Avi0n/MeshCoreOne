import CoreLocation
import MapKit
import MC1Services
import SwiftUI

/// Map-based location picker for setting node position
struct LocationPickerView: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme

  // Configuration
  private let initialCoordinate: CLLocationCoordinate2D?
  private let onSave: (CLLocationCoordinate2D) async throws -> Void

  /// Stable marker identity
  private let markerID = UUID()

  // UI State
  @State private var cameraRegion: MKCoordinateRegion?
  @State private var cameraRegionVersion = 0
  @State private var selectedCoordinate: CLLocationCoordinate2D?
  @State private var isSaving = false
  @State private var errorMessage: String?

  /// Generic initializer for any location-setting context
  init(
    initialCoordinate: CLLocationCoordinate2D? = nil,
    onSave: @escaping (CLLocationCoordinate2D) async throws -> Void
  ) {
    self.initialCoordinate = initialCoordinate
    self.onSave = onSave
  }

  var body: some View {
    NavigationStack {
      ZStack {
        MC1MapView(
          points: markerPoints,
          lines: [],
          mapStyle: .standard,
          isDarkMode: colorScheme == .dark,
          showLabels: false,
          showsUserLocation: true,
          isInteractive: true,
          showsScale: false,
          cameraRegion: $cameraRegion,
          cameraRegionVersion: cameraRegionVersion,
          onPointTap: nil,
          onMapTap: { coord in selectedCoordinate = coord },
          onCameraRegionChange: { region in cameraRegion = region }
        )

        // Center crosshair for precise placement
        Image(systemName: "plus")
          .font(.title)
          .foregroundStyle(.secondary)

        // Coordinate display and actions
        VStack {
          Spacer()

          if let coord = selectedCoordinate {
            VStack(spacing: 4) {
              CoordinateText(
                label: L10n.Settings.LocationPicker.latitude,
                value: coord.latitude
              )
              CoordinateText(
                label: L10n.Settings.LocationPicker.longitude,
                value: coord.longitude
              )
            }
            .font(.caption.monospacedDigit())
            .padding()
            .background {
              if #available(iOS 26.0, *) {
                Color.clear
              } else {
                RoundedRectangle(cornerRadius: 8)
                  .fill(.ultraThinMaterial)
              }
            }
            .modifier(CoordinateGlassModifier())
          }

          Group {
            if #available(iOS 26.0, *) {
              GlassEffectContainer {
                makeButtonContent()
              }
            } else {
              makeButtonContent()
            }
          }
          .padding()
          .padding(.bottom, 16)
        }
      }
      .navigationTitle(L10n.Settings.LocationPicker.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.save) { saveLocation() }
            .radioDisabled(for: appState.connectionState, or: isSaving)
        }
      }
      .onAppear {
        loadCurrentLocation()
      }
      .onChange(of: appState.locationService.currentLocation) { _, newLocation in
        // Only react if we haven't set a camera region yet (no saved location case)
        guard let newLocation,
              initialCoordinate == nil
              || (initialCoordinate?.latitude == 0 && initialCoordinate?.longitude == 0),
              cameraRegion == nil else { return }

        cameraRegion = MKCoordinateRegion(
          center: newLocation.coordinate,
          span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        )
        cameraRegionVersion += 1
      }
      .errorAlert($errorMessage)
    }
  }

  private var markerPoints: [MapPoint] {
    guard let coord = selectedCoordinate else { return [] }
    return [MapPoint(
      id: markerID,
      coordinate: coord,
      pinStyle: .pointA,
      label: nil,
      isClusterable: false,
      hopIndex: nil,
      badgeText: nil
    )]
  }

  private func loadCurrentLocation() {
    // Case 1: Existing saved location
    if let coord = initialCoordinate,
       CLLocationCoordinate2DIsValid(coord),
       coord.latitude != 0 || coord.longitude != 0 {
      selectedCoordinate = coord
      cameraRegion = MKCoordinateRegion(
        center: coord,
        span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
      )
      cameraRegionVersion += 1
      return
    }

    // Invalid coordinates (not nil, not zero, but out of range) — show default map
    if let coord = initialCoordinate,
       coord.latitude != 0 || coord.longitude != 0 {
      return
    }

    // Case 2: No saved location (nil or 0,0), check user location
    let locationService = appState.locationService
    if locationService.isAuthorized {
      if let userLocation = locationService.currentLocation {
        cameraRegion = MKCoordinateRegion(
          center: userLocation.coordinate,
          span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        )
        cameraRegionVersion += 1
      } else if !locationService.isRequestingLocation {
        locationService.requestLocation()
      }
    }
  }

  private func dropPinAtCenter() {
    if let region = cameraRegion {
      selectedCoordinate = region.center
    }
  }

  private func saveLocation() {
    let coord = selectedCoordinate ?? CLLocationCoordinate2D(latitude: 0, longitude: 0)

    isSaving = true
    Task {
      do {
        try await onSave(coord)
        dismiss()
      } catch {
        errorMessage = error.userFacingMessage
      }
      isSaving = false
    }
  }

  private func makeButtonContent() -> some View {
    ButtonContent(
      hasSelectedCoordinate: selectedCoordinate != nil,
      onClear: { selectedCoordinate = nil },
      onDropPin: { dropPinAtCenter() }
    )
  }
}

private struct ButtonContent: View {
  let hasSelectedCoordinate: Bool
  let onClear: () -> Void
  let onDropPin: () -> Void

  var body: some View {
    HStack(spacing: 12) {
      if hasSelectedCoordinate {
        Button(L10n.Settings.LocationPicker.clearLocation, role: .destructive) {
          onClear()
        }
        .liquidGlassSecondaryButtonStyle()
        .controlSize(.regular)
      }

      Button(L10n.Settings.LocationPicker.dropPin) {
        onDropPin()
      }
      .liquidGlassProminentButtonStyle()
      .controlSize(.regular)
    }
  }
}

// MARK: - Local Device Convenience

extension LocationPickerView {
  /// Convenience initializer for local device location setting (Settings screen)
  /// Retry handling for SettingsServiceError is handled at the call site since it requires RetryAlertState
  static func forLocalDevice(appState: AppState) -> LocationPickerView {
    let device = appState.connectedDevice
    let initialCoord = device.map {
      CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
    }
    let devicePreferenceStore = DevicePreferenceStore()

    return LocationPickerView(initialCoordinate: initialCoord) { coordinate in
      guard let settingsService = appState.services?.settingsService else {
        throw SettingsServiceError.notConnected
      }
      let previousDevice = appState.connectedDevice
      let verifiedInfo = try await settingsService.setManualLocationVerified(
        latitude: coordinate.latitude,
        longitude: coordinate.longitude
      )

      guard let previousDevice else { return }

      let wasUsingDeviceGPSAutoUpdate =
        devicePreferenceStore.isAutoUpdateLocationEnabled(deviceID: previousDevice.id) &&
        devicePreferenceStore.gpsSource(deviceID: previousDevice.id) == .device

      if wasUsingDeviceGPSAutoUpdate {
        devicePreferenceStore.setAutoUpdateLocationEnabled(false, deviceID: previousDevice.id)
      }

      if previousDevice.sharesLocationPublicly,
         previousDevice.advertLocationPolicyMode == .share {
        let telemetryModes = TelemetryModes(
          base: verifiedInfo.telemetryModeBase,
          location: verifiedInfo.telemetryModeLocation,
          environment: verifiedInfo.telemetryModeEnvironment
        )
        _ = try await settingsService.setOtherParamsVerified(
          autoAddContacts: !verifiedInfo.manualAddContacts,
          telemetryModes: telemetryModes,
          advertLocationPolicy: .prefs,
          multiAcks: verifiedInfo.multiAcks
        )
      }
    }
  }
}

// MARK: - Liquid Glass Modifiers

private struct CoordinateGlassModifier: ViewModifier {
  func body(content: Content) -> some View {
    if #available(iOS 26.0, *) {
      content.glassEffect(.regular, in: .rect(cornerRadius: 8))
    } else {
      content
    }
  }
}

private struct CoordinateText: View {
  let label: String
  let value: Double

  var body: some View {
    Text("\(label) \(value, format: .number.precision(.fractionLength(6)))")
  }
}
