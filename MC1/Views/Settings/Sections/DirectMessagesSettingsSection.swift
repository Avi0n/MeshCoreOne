import MC1Services
import SwiftUI

struct DirectMessagesSettingsSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @AppStorage(AppStorageKey.dmDirectAttempts.rawValue)
  private var storedDirectAttempts = AppStorageKey.defaultDMDirectAttempts
  @AppStorage(AppStorageKey.dmFloodAttempts.rawValue)
  private var storedFloodAttempts = AppStorageKey.defaultDMFloodAttempts
  @State private var errorMessage: String?
  @State private var retryAlert = RetryAlertState()
  @State private var isSaving = false

  private var device: DeviceDTO? {
    appState.connectedDevice
  }

  var body: some View {
    Section {
      Picker(L10n.Settings.DirectMessages.acknowledgments, selection: acksBinding) {
        Text("1").tag(1)
        Text("2").tag(2)
      }
      .pickerStyle(.menu)
      .radioDisabled(for: appState.connectionState, or: isSaving)

      Picker(L10n.Settings.DirectMessages.directAttempts, selection: directAttemptsBinding) {
        ForEach(Array(DMSendBudget.directRange), id: \.self) { count in
          Text(String(count)).tag(count)
        }
      }
      .pickerStyle(.menu)

      Picker(L10n.Settings.DirectMessages.floodAttempts, selection: floodAttemptsBinding) {
        ForEach(Array(DMSendBudget.floodRange), id: \.self) { count in
          Text(String(count)).tag(count)
        }
      }
      .pickerStyle(.menu)
    } header: {
      Text(L10n.Settings.DirectMessages.header)
    } footer: {
      Text(L10n.Settings.DirectMessages.footer + "\n\n" + L10n.Settings.DirectMessages.attemptsFooter)
    }
    .themedRowBackground(theme)
    .errorAlert($errorMessage)
    .retryAlert(retryAlert)
  }

  // MARK: - Binding

  private var directAttemptsBinding: Binding<Int> {
    Binding(
      get: { DMSendBudget.clampedDirectAttempts(storedDirectAttempts) },
      set: { storedDirectAttempts = DMSendBudget.clampedDirectAttempts($0) }
    )
  }

  private var floodAttemptsBinding: Binding<Int> {
    Binding(
      get: { DMSendBudget.clampedFloodAttempts(storedFloodAttempts) },
      set: { storedFloodAttempts = DMSendBudget.clampedFloodAttempts($0) }
    )
  }

  private var acksBinding: Binding<Int> {
    Binding(
      get: { Int(device?.multiAcks ?? 0) + 1 },
      set: { saveMultiAcks(UInt8($0 - 1)) }
    )
  }

  // MARK: - Save

  private func saveMultiAcks(_ value: UInt8) {
    guard let device, let settingsService = appState.services?.settingsService else { return }

    isSaving = true
    Task {
      do {
        try await settingsService.setOtherParamsVerified(multiAcks: value)
        retryAlert.reset()
      } catch let error as SettingsServiceError where error.isRetryable {
        retryAlert.show(
          message: error.userFacingMessage,
          onRetry: { saveMultiAcks(value) },
          onMaxRetriesExceeded: { dismiss() }
        )
      } catch {
        errorMessage = error.userFacingMessage
      }
      isSaving = false
    }
  }
}

#Preview {
  Form {
    DirectMessagesSettingsSection()
  }
}
