import MC1Services
import SwiftUI

struct DeviceActionsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var showingRebootAlert = false
  @State private var showingPowerOffAlert = false
  @State private var isRebooting = false
  @State private var isPoweringOff = false
  @State private var errorMessage: String?

  private var actionsBusy: Bool {
    isRebooting || isPoweringOff
  }

  var body: some View {
    Section {
      Button {
        showingRebootAlert = true
      } label: {
        if isRebooting {
          HStack {
            ProgressView()
            Text(L10n.Settings.DeviceActions.rebooting)
          }
        } else {
          Label(L10n.Settings.DeviceActions.rebootDevice, systemImage: "arrow.clockwise")
        }
      }
      .radioDisabled(for: appState.connectionState, or: actionsBusy)

      if appState.connectedDevice?.supportsRadioCLI == true {
        Button(role: .destructive) {
          showingPowerOffAlert = true
        } label: {
          if isPoweringOff {
            HStack {
              ProgressView()
              Text(L10n.Settings.DeviceActions.powerOff)
            }
          } else {
            Label(L10n.Settings.DeviceActions.powerOff, systemImage: "power")
          }
        }
        .radioDisabled(for: appState.connectionState, or: actionsBusy)
      }
    } header: {
      Text(L10n.Settings.DeviceActions.header)
    }
    .themedRowBackground(theme)
    .alert(L10n.Settings.DeviceActions.Alert.Reboot.title, isPresented: $showingRebootAlert) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      Button(L10n.Settings.DeviceActions.Alert.Reboot.confirm) {
        rebootDevice()
      }
    } message: {
      Text(L10n.Settings.DeviceActions.Alert.Reboot.message)
    }
    .alert(L10n.Settings.DeviceActions.Alert.PowerOff.title, isPresented: $showingPowerOffAlert) {
      Button(L10n.Localizable.Common.cancel, role: .cancel) {}
      Button(L10n.Settings.DeviceActions.Alert.PowerOff.confirm, role: .destructive) {
        powerOffDevice()
      }
    } message: {
      Text(L10n.Settings.DeviceActions.Alert.PowerOff.message)
    }
    .errorAlert($errorMessage)
  }

  private func rebootDevice() {
    guard let settingsService = appState.services?.settingsService else { return }

    isRebooting = true
    Task {
      defer { isRebooting = false }
      do {
        try await settingsService.reboot()
      } catch BLEError.operationTimeout {
        // Expected - device reboots before BLE write callback arrives
      } catch {
        errorMessage = error.userFacingMessage
      }
    }
  }

  private func powerOffDevice() {
    isPoweringOff = true
    Task {
      defer { isPoweringOff = false }
      do {
        try await appState.powerOffRadio()
      } catch is PowerOffSuperseded {
        return
      } catch {
        errorMessage = error.userFacingMessage
      }
    }
  }
}
