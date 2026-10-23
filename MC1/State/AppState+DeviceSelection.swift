import MC1Services
import os

private let logger = Logger(subsystem: "com.mc1", category: "DeviceSelection")

extension AppState {
  func handleDeviceSelectionSheetDismissed() {
    handleDeviceSelectionSheetDismissed(connect: { [self] target in
      switch target {
      case let .wifi(host, port):
        try await connectViaWiFi(host: host, port: port, forceFullSync: true)
      case let .bluetooth(peripheralID):
        if connectionManager.currentTransportType == .wifi {
          try await connectViaBluetooth(peripheralID: peripheralID, forceFullSync: true)
        } else {
          try await connectionManager.connect(to: peripheralID, forceFullSync: true, forceReconnect: true)
        }
      }
    })
  }

  func handleDeviceSelectionSheetDismissed(
    connect: @escaping @MainActor (SavedDeviceConnectTarget) async throws -> Void
  ) {
    if let queued = connectionUI.queuedSavedDeviceConnection {
      // Consume before scheduling so repeated dismissal callbacks cannot dial twice.
      connectionUI.queuedSavedDeviceConnection = nil
      Task {
        logger.info("Connecting to saved device: \(queued.device.id.uuidString.prefix(8)), name: \(queued.device.nodeName)")
        do {
          try await connect(queued.target)
        } catch {
          connectionUI.presentSavedDeviceConnectFailure(deviceID: queued.device.id, error: error)
        }
      }
      return
    }
    if connectionUI.queuedDeviceScanAfterSelectionDismiss {
      connectionUI.queuedDeviceScanAfterSelectionDismiss = false
      connectionUI.queuedSystemPairingSetup = nil
      connectionManager.isPairingFlowActive = true
      Task {
        await connectionManager.stopBLEScanning()
        startDeviceScan()
      }
      return
    }
    guard let queued = connectionUI.queuedSystemPairingSetup else { return }
    connectionUI.queuedSystemPairingSetup = nil
    guard connectionUI.pendingSystemPairingSetup == nil else { return }
    connectionManager.isPairingFlowActive = true
    connectionUI.pendingSystemPairingSetup = queued
  }
}
