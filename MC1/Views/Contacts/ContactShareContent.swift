import Foundation
import MC1Services

enum ContactShareContent {
  static func compactPublicKeyHex(_ publicKey: Data) -> String {
    publicKey.uppercaseHexString()
  }

  static func uri(name: String, publicKey: Data, type: ContactType) -> String {
    ContactService.exportContactURI(name: name, publicKey: publicKey, type: type)
  }
}
