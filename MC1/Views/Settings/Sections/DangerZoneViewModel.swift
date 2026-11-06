import MC1Services
import SwiftUI

@Observable
@MainActor
final class DangerZoneViewModel {
  var showingForgetConfirmation = false
  var showingResetAlert = false
  var isResetting = false
  var errorMessage: String?
  var showingRemoveUnfavoritedAlert = false
  var isRemovingUnfavorited = false
  var showRemoveSuccess = false
  var unfavoritedCount = 0
  var showRemoveResult = false
  var removeResult: String?

  private var removeTask: Task<Void, Never>?
  private var removalOperationID: UUID?

  // MARK: - Dependencies

  private var settingsServiceProvider: @MainActor () -> SettingsService? = { nil }
  var settingsService: SettingsService? {
    settingsServiceProvider()
  }

  private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  var connectedDevice: DeviceDTO? {
    connectedDeviceProvider()
  }

  private var connectionManager: ConnectionManager?

  /// How long the transient "Removed" confirmation stays on the button label.
  private static let removeSuccessDisplayDuration: Duration = .seconds(1.5)

  func configure(
    settingsService: @escaping @MainActor () -> SettingsService?,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    connectionManager: ConnectionManager
  ) {
    settingsServiceProvider = settingsService
    connectedDeviceProvider = connectedDevice
    self.connectionManager = connectionManager
  }

  func cancelPendingRemoval() {
    removeTask?.cancel()
  }

  /// Returns true when the device was forgotten and the hosting page should dismiss.
  func forgetDevice(deleteData: Bool) async -> Bool {
    guard let connectionManager, let context = connectionManager.captureCurrentRemoval() else { return false }
    let operationID = UUID()
    removalOperationID = operationID
    defer { if removalOperationID == operationID { removalOperationID = nil } }
    do {
      let result = try await connectionManager.forgetDevice(context, deleteData: deleteData)
      guard removalOperationID == operationID, case let .removed(receipt) = result else { return false }
      return connectionManager.isCurrent(receipt)
    } catch is CancellationError {
      return false
    } catch {
      if removalOperationID == operationID { errorMessage = error.userFacingMessage }
      return false
    }
  }

  /// Returns true when the reset flow finished and the hosting page should dismiss.
  /// A nil service or device ID mirrors a disconnected state.
  func factoryReset() async -> Bool {
    guard settingsService != nil,
          connectedDevice != nil,
          let connectionManager,
          let context = connectionManager.captureCurrentRemoval() else {
      errorMessage = L10n.Settings.DangerZone.Error.servicesUnavailable
      return false
    }

    let operationID = UUID()
    removalOperationID = operationID
    isResetting = true
    defer {
      if removalOperationID == operationID {
        isResetting = false
        removalOperationID = nil
      }
    }

    do {
      let result = try await connectionManager.factoryResetAndEraseRadio(context)
      guard removalOperationID == operationID, case let .removed(receipt) = result else { return false }
      return connectionManager.isCurrent(receipt)
    } catch is CancellationError {
      return false
    } catch {
      if removalOperationID == operationID { errorMessage = error.userFacingMessage }
      return false
    }
  }

  func fetchUnfavoritedCount() async {
    guard let connectionManager else { return }
    do {
      unfavoritedCount = try await connectionManager.unfavoritedNodeCount()
      if unfavoritedCount == 0 {
        removeResult = L10n.Settings.DangerZone.Alert.RemoveUnfavorited.noneFound
        showRemoveResult = true
      } else {
        showingRemoveUnfavoritedAlert = true
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  func removeUnfavoritedNodes() {
    guard let connectionManager else { return }
    isRemovingUnfavorited = true
    removeTask = Task {
      defer { isRemovingUnfavorited = false }
      do {
        let result = try await connectionManager.removeUnfavoritedNodes()
        isRemovingUnfavorited = false
        if result.removed == result.total {
          withAnimation { showRemoveSuccess = true }
          try await Task.sleep(for: Self.removeSuccessDisplayDuration)
          withAnimation { showRemoveSuccess = false }
        } else {
          removeResult = L10n.Settings.DangerZone.Alert.RemoveUnfavorited
            .partial(result.removed, result.total)
          showRemoveResult = true
        }
      } catch {
        if !(error is CancellationError) {
          errorMessage = error.userFacingMessage
        }
      }
    }
  }
}
