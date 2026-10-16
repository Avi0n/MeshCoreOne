import Foundation

enum ChatPinError: Error {
  case storeUnavailable
}

extension ChatPinError {
  var userFacingMessage: String {
    switch self {
    case .storeUnavailable:
      L10n.Chats.Chats.Error.pinSaveFailed
    }
  }
}
