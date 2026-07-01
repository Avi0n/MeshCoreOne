import MC1Services
import OSLog
import SwiftUI
import TipKit

private let logger = Logger(subsystem: "com.mc1", category: "BLEStatus")

/// BLE connection status indicator for toolbar display
/// Shows connection state via color-coded icon with menu details
struct BLEStatusIndicatorView: View {
  @Environment(\.appState) private var appState
  @State private var showingDeviceSelection = false
  @State private var showingAdvancedSettings = false
  @State private var isSendingAdvert = false
  @State private var successFeedbackTrigger = false
  @State private var errorFeedbackTrigger = false

  private let deviceMenuTip = DeviceMenuTip()

  /// One always-present Menu, never an if/else between a Menu and a Button: SwiftUI
  /// gives a branch's two arms distinct identities, so toggling on connection state
  /// rebuilds the hosted toolbar item mid-update and trips a graph re-entrancy crash
  /// on iOS 26. Varying only the label and menu content by value updates it in place.
  var body: some View {
    ToolbarMenu {
      menuContent
    } label: {
      StatusIcon(iconName: iconName, iconColor: iconColor, isAnimating: isAnimating)
    }
    .popoverTip(deviceMenuTip)
    .dynamicTypeSize(...DynamicTypeSize.xLarge)
    .sensoryFeedback(.success, trigger: successFeedbackTrigger)
    .sensoryFeedback(.error, trigger: errorFeedbackTrigger)
    .accessibilityLabel(L10n.Settings.BleStatus.accessibilityLabel)
    .accessibilityValue(statusTitle)
    .accessibilityHint(accessibilityHint)
    .onChange(of: appState.connectedDevice != nil, initial: true) { _, isConnected in
      DeviceMenuTip.isConnected = isConnected
    }
    .sheet(isPresented: $showingDeviceSelection) {
      DeviceSelectionSheet()
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
    .navigationDestination(isPresented: $showingAdvancedSettings) {
      AdvancedSettingsView()
    }
  }

  // MARK: - Menu Content

  @ViewBuilder
  private var menuContent: some View {
    if let device = appState.connectedDevice {
      Section {
        if device.clientRepeat {
          Label(L10n.Settings.BleStatus.repeatModeActive, systemImage: "repeat")
            .foregroundStyle(AppColors.Radio.repeatMode)
        }
        VStack(alignment: .leading) {
          Label(device.nodeName, systemImage: "antenna.radiowaves.left.and.right")
          if let battery = appState.batteryMonitor.deviceBattery {
            let ocvArray = appState.batteryMonitor.activeBatteryOCVArray(for: appState.connectedDevice)
            Label(
              "\(battery.percentage(using: ocvArray))% (\(battery.voltage, format: .number.precision(.fractionLength(2)))v)",
              systemImage: battery.iconName(using: ocvArray)
            )
            .font(.caption)
            .foregroundStyle(.secondary)
          }
        }

        Button {
          showingDeviceSelection = true
        } label: {
          Label(L10n.Settings.BleStatus.changeDevice, systemImage: "flipphone")
        }

        Button(role: .destructive) {
          logger.info("Disconnect tapped in BLE status menu")
          Task {
            await appState.disconnect(reason: .statusMenuDisconnectTap)
          }
        } label: {
          Label(L10n.Settings.BleStatus.disconnect, systemImage: "eject")
        }
      }

      Section {
        Button {
          sendAdvert(flood: false)
        } label: {
          Label(L10n.Settings.BleStatus.sendZeroHopAdvert, systemImage: "dot.radiowaves.right")
        }
        .radioDisabled(for: appState.connectionState, or: isSendingAdvert)
        .accessibilityHint(L10n.Settings.BleStatus.SendZeroHopAdvert.hint)

        Button {
          sendAdvert(flood: true)
        } label: {
          Label(L10n.Settings.BleStatus.sendFloodAdvert, systemImage: "dot.radiowaves.left.and.right")
        }
        .radioDisabled(for: appState.connectionState, or: isSendingAdvert)
        .accessibilityHint(L10n.Settings.BleStatus.SendFloodAdvert.hint)
      }

      Section {
        Button {
          showingAdvancedSettings = true
        } label: {
          Label(L10n.Settings.AdvancedSettings.title, systemImage: "gearshape")
        }
      }
    } else {
      Button {
        showingDeviceSelection = true
      } label: {
        Label(L10n.Settings.Device.connect, systemImage: "antenna.radiowaves.left.and.right")
      }
    }
  }

  private var accessibilityHint: String {
    appState.connectedDevice != nil
      ? L10n.Settings.BleStatus.AccessibilityHint.connected
      : L10n.Settings.BleStatus.AccessibilityHint.disconnected
  }

  // MARK: - Computed Properties

  private var iconName: String {
    switch appState.connectionState {
    case .disconnected:
      "antenna.radiowaves.left.and.right.slash"
    case .connecting, .connected, .syncing, .ready:
      "antenna.radiowaves.left.and.right"
    }
  }

  private var iconColor: Color {
    if appState.connectedDevice?.clientRepeat == true {
      return AppColors.Radio.repeatMode
    }
    switch appState.connectionState {
    case .disconnected:
      return .secondary
    case .connecting, .connected, .syncing:
      return AppColors.Radio.connecting
    case .ready:
      return AppColors.Radio.ready
    }
  }

  private var isAnimating: Bool {
    appState.connectionState == .connecting
  }

  private var statusTitle: String {
    switch appState.connectionState {
    case .disconnected:
      L10n.Settings.BleStatus.Status.disconnected
    case .connecting:
      L10n.Settings.BleStatus.Status.connecting
    case .connected:
      L10n.Settings.BleStatus.Status.connected
    case .syncing:
      L10n.Settings.BleStatus.Status.syncing
    case .ready:
      L10n.Settings.BleStatus.Status.ready
    }
  }

  // MARK: - Actions

  private func sendAdvert(flood: Bool) {
    guard !isSendingAdvert else { return }
    isSendingAdvert = true

    Task {
      do {
        try await appState.sendSelfAdvert(flood: flood)
        successFeedbackTrigger.toggle()
      } catch {
        logger.error("Failed to send advert (flood=\(flood)): \(error.localizedDescription)")
        errorFeedbackTrigger.toggle()
      }
      isSendingAdvert = false
    }
  }
}

// MARK: - Status Icon

private struct StatusIcon: View {
  let iconName: String
  let iconColor: Color
  let isAnimating: Bool

  var body: some View {
    Image(systemName: iconName)
      .foregroundStyle(iconColor)
      .symbolEffect(.pulse, isActive: isAnimating)
  }
}

#Preview("Disconnected") {
  NavigationStack {
    Text("Content")
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          BLEStatusIndicatorView()
        }
      }
  }
  .environment(\.appState, AppState())
}
