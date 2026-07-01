import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct ErrorUserFacingMessageTests {
  // MARK: - Dispatch Through `any Error`

  @Test func `mesh core error dispatches to concrete mapping`() {
    let error: any Error = MeshCoreError.timeout
    #expect(error.userFacingMessage == L10n.Localizable.Error.MeshCore.timeout)
  }

  @Test func `protocol error dispatches to concrete mapping`() {
    let error: any Error = ProtocolError.tableFull
    #expect(error.userFacingMessage == L10n.Localizable.Error.Device.storageFull)
  }

  @Test func `timeout error dispatches to concrete mapping`() {
    let error: any Error = TimeoutError(operationName: "sync", timeout: .seconds(5))
    #expect(error.userFacingMessage == L10n.Localizable.Error.Timeout.operationTimedOut)
  }

  @Test func `app backup error dispatches to concrete mapping`() {
    let error: any Error = AppBackupError.invalidFile
    #expect(error.userFacingMessage == AppBackupError.invalidFile.userFacingMessage)
  }

  @Test func `ble error dispatches to concrete mapping`() {
    let detail = "peripheral unreachable"
    let error: any Error = BLEError.connectionFailed(detail)
    #expect(error.userFacingMessage == L10n.Localizable.Error.Ble.connectionFailed(detail))
  }

  @Test func `connection error dispatches to concrete mapping`() {
    let reason = "handshake rejected"
    let error: any Error = ConnectionError.initializationFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.Connection.initializationFailed(reason))
  }

  @Test func `wifi transport error dispatches to concrete mapping`() {
    let error: any Error = WiFiTransportError.invalidHost
    #expect(error.userFacingMessage == L10n.Localizable.Error.Wifi.invalidHost)
  }

  @Test func `accessory setup kit error dispatches to concrete mapping`() {
    let reason = "user declined"
    let error: any Error = AccessorySetupKitError.pairingFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.AccessorySetup.pairingFailed(reason))
  }

  @Test func `contact service error dispatches to concrete mapping`() {
    let error: any Error = ContactServiceError.contactTableFull
    #expect(error.userFacingMessage == L10n.Localizable.Error.ContactService.contactTableFull)
  }

  @Test func `message service error dispatches to concrete mapping`() {
    let reason = "queue rejected"
    let error: any Error = MessageServiceError.sendFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.MessageService.sendFailed)
    #expect(!error.userFacingMessage.contains(reason))
  }

  @Test func `channel service error dispatches to concrete mapping`() {
    let failureCount = 3
    let error: any Error = ChannelServiceError.circuitBreakerOpen(consecutiveFailures: failureCount)
    #expect(error.userFacingMessage == L10n.Localizable.Error.ChannelService.circuitBreakerOpen(failureCount))
  }

  @Test func `chat send queue service error dispatches to concrete mapping`() {
    let error: any Error = ChatSendQueueServiceError.notConnected
    #expect(error.userFacingMessage == L10n.Localizable.Error.ChatSendQueue.notConnected)
  }

  @Test func `message polling error dispatches to concrete mapping`() {
    let error: any Error = MessagePollingError.pollingFailed
    #expect(error.userFacingMessage == L10n.Localizable.Error.MessagePolling.pollingFailed)
  }

  @Test func `advertisement error dispatches to concrete mapping`() {
    let error: any Error = AdvertisementError.sendFailed
    #expect(error.userFacingMessage == L10n.Localizable.Error.Advertisement.sendFailed)
  }

  @Test func `remote node error dispatches to concrete mapping`() {
    let reason = "authentication failed"
    let error: any Error = RemoteNodeError.loginFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.RemoteNode.loginFailed)
    #expect(!error.userFacingMessage.contains(reason))
  }

  @Test func `room server error dispatches to concrete mapping`() {
    let error: any Error = RoomServerError.sessionNotFound
    #expect(error.userFacingMessage == L10n.Localizable.Error.RoomServer.sessionNotFound)
  }

  @Test func `room server send failed drops raw reason`() {
    let reason = "Retry already in progress"
    let error: any Error = RoomServerError.sendFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.RoomServer.sendFailed)
    #expect(!error.userFacingMessage.contains(reason))
  }

  @Test func `binary protocol error dispatches to concrete mapping`() {
    let error: any Error = BinaryProtocolError.timeout
    #expect(error.userFacingMessage == L10n.Localizable.Error.BinaryProtocol.timeout)
  }

  @Test func `persistence store error dispatches to concrete mapping`() {
    let reason = "store unavailable"
    let error: any Error = PersistenceStoreError.saveFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.Persistence.saveFailed(reason))
  }

  @Test func `sync coordinator error dispatches to concrete mapping`() {
    let error: any Error = SyncCoordinatorError.alreadySyncing
    #expect(error.userFacingMessage == L10n.Localizable.Error.SyncCoordinator.alreadySyncing)
  }

  @Test func `device service error dispatches to concrete mapping`() {
    let reason = "write rejected"
    let error: any Error = DeviceServiceError.persistenceFailed(reason)
    #expect(error.userFacingMessage == L10n.Localizable.Error.DeviceService.persistenceFailed(reason))
  }

  @Test func `settings service error dispatches to concrete mapping`() {
    let expected = "915.5"
    let actual = "868.0"
    let error: any Error = SettingsServiceError.verificationFailed(expected: expected, actual: actual)
    #expect(error.userFacingMessage == L10n.Localizable.Error.Settings.verificationFailed(expected, actual))
  }

  @Test func `keychain error dispatches to concrete mapping`() {
    let status: OSStatus = errSecDuplicateItem
    let error: any Error = KeychainError.storageFailed(status)
    #expect(error.userFacingMessage == L10n.Localizable.Error.Keychain.storageFailed(Int(status)))
  }

  @Test func `key generation error dispatches to concrete mapping`() {
    let error: any Error = KeyGenerationError.reservedPrefix
    #expect(error.userFacingMessage == L10n.Localizable.Error.KeyGeneration.reservedPrefix)
  }

  @Test func `node config service error dispatches to concrete mapping`() {
    let channelIndex = 2
    let hexLength = 30
    let error: any Error = NodeConfigServiceError.invalidChannelSecret(index: channelIndex, hexLength: hexLength)
    #expect(
      error.userFacingMessage
        == L10n.Settings.ConfigImport.Error.invalidChannelSecret(channelIndex, hexLength)
    )
  }

  @Test func `store service error dispatches to concrete mapping`() {
    let reason = "storefront rejected"
    let error: any Error = StoreServiceError.purchaseFailed(reason: reason)
    #expect(error.userFacingMessage == L10n.Settings.Support.Error.purchaseFailed(reason))
  }

  @Test func `device GPS verification failed picks boolean variant key`() {
    #expect(
      SettingsServiceError.deviceGPSVerificationFailed(expectedEnabled: true, actualEnabled: false)
        .userFacingMessage == L10n.Localizable.Error.Settings.gpsNotSavedExpectedOn
    )
    #expect(
      SettingsServiceError.deviceGPSVerificationFailed(expectedEnabled: false, actualEnabled: true)
        .userFacingMessage == L10n.Localizable.Error.Settings.gpsNotSavedExpectedOff
    )
  }

  @Test func `unmapped error falls back to localized description`() {
    let description = "Something went wrong"
    let error: any Error = NSError(
      domain: "MC1Tests",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: description]
    )
    #expect(error.userFacingMessage == description)
  }

  // MARK: - Service Error sessionError Delegation

  @Test func `session error delegates to central mesh core mapping`() {
    #expect(
      (ContactServiceError.sessionError(.timeout) as any Error).userFacingMessage
        == L10n.Localizable.Error.MeshCore.timeout
    )
    #expect(
      MessageServiceError.sessionError(.notConnected).userFacingMessage
        == L10n.Localizable.Error.MeshCore.notConnected
    )
    #expect(
      ChannelServiceError.sessionError(.sessionNotStarted).userFacingMessage
        == L10n.Localizable.Error.MeshCore.sessionNotStarted
    )
    #expect(
      MessagePollingError.sessionError(.bluetoothPoweredOff).userFacingMessage
        == L10n.Localizable.Error.MeshCore.bluetoothPoweredOff
    )
    #expect(
      AdvertisementError.sessionError(.featureDisabled).userFacingMessage
        == L10n.Localizable.Error.MeshCore.featureDisabled
    )
    #expect(
      RemoteNodeError.sessionError(.timeout).userFacingMessage
        == L10n.Localizable.Error.MeshCore.timeout
    )
    #expect(
      RoomServerError.sessionError(.notConnected).userFacingMessage
        == L10n.Localizable.Error.MeshCore.notConnected
    )
    #expect(
      BinaryProtocolError.sessionError(.sessionNotStarted).userFacingMessage
        == L10n.Localizable.Error.MeshCore.sessionNotStarted
    )
    #expect(
      SettingsServiceError.sessionError(.timeout).userFacingMessage
        == L10n.Localizable.Error.MeshCore.timeout
    )
  }

  @Test func `persist failed recurses into underlying error`() {
    let underlying = MessageServiceError.notConnected
    #expect(
      ChatSendQueueServiceError.persistFailed(underlying: underlying).userFacingMessage
        == L10n.Localizable.Error.ChatSendQueue.persistFailed(
          L10n.Localizable.Error.MessageService.notConnected
        )
    )
  }

  // MARK: - MeshCoreError Mapping

  @Test func `interpolated cases carry associated values`() {
    let detail = "bad frame"
    #expect(
      MeshCoreError.parseError(detail).userFacingMessage
        == L10n.Localizable.Error.MeshCore.parseError(detail)
    )
    #expect(
      MeshCoreError.dataTooLarge(maxSize: 184, actualSize: 200).userFacingMessage
        == L10n.Localizable.Error.MeshCore.dataTooLarge(200, 184)
    )
  }

  @Test func `device error maps known codes through protocol error`() {
    for protocolError in [ProtocolError.unsupportedCommand, .notFound, .tableFull, .badState, .fileIOError, .illegalArgument] {
      #expect(
        MeshCoreError.deviceError(code: protocolError.rawValue).userFacingMessage
          == protocolError.userFacingMessage
      )
    }
  }

  @Test func `device error falls back for unknown code`() {
    let unknownCode: UInt8 = 0x42
    #expect(
      MeshCoreError.deviceError(code: unknownCode).userFacingMessage
        == L10n.Localizable.Error.Device.unknown(Int(unknownCode))
    )
  }

  @Test func `connection lost recurses into underlying error`() {
    let underlying = MeshCoreError.bluetoothPoweredOff
    #expect(
      MeshCoreError.connectionLost(underlying: underlying).userFacingMessage
        == L10n.Localizable.Error.MeshCore.connectionLost(
          L10n.Localizable.Error.MeshCore.bluetoothPoweredOff
        )
    )
    #expect(
      MeshCoreError.connectionLost(underlying: nil).userFacingMessage
        == L10n.Localizable.Error.MeshCore.connectionLostNoDetail
    )
  }
}
