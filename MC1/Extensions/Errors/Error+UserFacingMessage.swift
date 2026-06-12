import Foundation
import MC1Services

extension Error {
    /// Localized user-facing message for `errorMessage` at the view boundary.
    /// Inside each `case let` the value is concretely typed, so the concrete
    /// type's `userFacingMessage` extension member wins over this protocol
    /// extension and there is no recursion. Errors without a mapping fall back
    /// to `localizedDescription`; each newly mapped error type adds a case here.
    var userFacingMessage: String {
        switch self {
        case let error as MeshCoreError: error.userFacingMessage
        case let error as ProtocolError: error.userFacingMessage
        case let error as TimeoutError: error.userFacingMessage
        case let error as AppBackupError: error.userFacingMessage
        case let error as BLEError: error.userFacingMessage
        case let error as ConnectionError: error.userFacingMessage
        case let error as WiFiTransportError: error.userFacingMessage
        case let error as AccessorySetupKitError: error.userFacingMessage
        case let error as ContactServiceError: error.userFacingMessage
        case let error as MessageServiceError: error.userFacingMessage
        case let error as ChannelServiceError: error.userFacingMessage
        case let error as ChatSendQueueServiceError: error.userFacingMessage
        case let error as MessagePollingError: error.userFacingMessage
        case let error as AdvertisementError: error.userFacingMessage
        case let error as RemoteNodeError: error.userFacingMessage
        case let error as RoomServerError: error.userFacingMessage
        case let error as BinaryProtocolError: error.userFacingMessage
        case let error as PersistenceStoreError: error.userFacingMessage
        case let error as SyncCoordinatorError: error.userFacingMessage
        case let error as DeviceServiceError: error.userFacingMessage
        case let error as SettingsServiceError: error.userFacingMessage
        case let error as KeychainError: error.userFacingMessage
        case let error as KeyGenerationError: error.userFacingMessage
        case let error as NodeConfigServiceError: error.userFacingMessage
        case let error as StoreServiceError: error.userFacingMessage
        default: localizedDescription
        }
    }
}
