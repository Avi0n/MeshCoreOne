import CryptoKit
import Foundation

/// Defines the destination for a message or command in the mesh network.
///
/// A destination can be specified using raw data, a hex string, or a ``MeshContact`` object.
public enum Destination: Sendable {
  /// Destination specified as raw data.
  case data(Data)
  /// Destination specified as a hex string.
  case hexString(String)
  /// Destination specified as a mesh contact.
  case contact(MeshContact)

  /// Returns the public key prefix of the specified length for this destination.
  ///
  /// - Parameter prefixLength: The number of bytes to extract from the start of the public key. Defaults to 6.
  /// - Returns: A `Data` object containing the public key prefix.
  /// - Throws: `DestinationError.insufficientLength` if the data is shorter than requested.
  ///           `DestinationError.invalidHexString` if the hex string cannot be parsed.
  public func publicKey(prefixLength: Int = 6) throws -> Data {
    switch self {
    case let .data(data):
      guard data.count >= prefixLength else {
        throw DestinationError.insufficientLength(expected: prefixLength, actual: data.count)
      }
      return Data(data.prefix(prefixLength))

    case let .hexString(hex):
      guard let data = Data(hexString: hex) else {
        throw DestinationError.invalidHexString(hex)
      }
      guard data.count >= prefixLength else {
        throw DestinationError.insufficientLength(expected: prefixLength, actual: data.count)
      }
      return Data(data.prefix(prefixLength))

    case let .contact(contact):
      guard contact.publicKey.count >= prefixLength else {
        throw DestinationError.insufficientLength(expected: prefixLength, actual: contact.publicKey.count)
      }
      return Data(contact.publicKey.prefix(prefixLength))
    }
  }

  /// Returns the full 32-byte public key for this destination.
  ///
  /// - Returns: A `Data` object containing the full public key.
  /// - Throws: `DestinationError` if the destination data is invalid or too short.
  public func fullPublicKey() throws -> Data {
    try publicKey(prefixLength: 32)
  }
}

/// Errors that can occur when resolving a destination.
public enum DestinationError: Error, Sendable {
  /// Indicates the hex string is not valid hex.
  case invalidHexString(String)
  /// Indicates the destination data is shorter than the requested length.
  case insufficientLength(expected: Int, actual: Int)
}

/// Defines the scope for flood routing in the mesh network.
public enum FloodScope: Sendable, Equatable {
  /// Flood routing is disabled.
  case disabled
  /// Scope based on a channel name.
  case channelName(String)
  /// Scope based on a raw 16-byte key.
  case rawKey(Data)
  /// Scope based on a public region name. The key is derived as `SHA256("#" + name).prefix(16)`,
  /// matching the firmware convention for public hashtag regions.
  ///
  /// Region names from ``MeshCoreSession/requestRegions(from:)`` can be passed directly
  /// (e.g., `"Europe"`). The `#` prefix is added automatically if not present.
  case region(String)

  /// Generates a 16-byte scope key from the current scope.
  ///
  /// - Returns: A 16-byte `Data` object used for message encryption and routing.
  public func scopeKey() -> Data {
    switch self {
    case .disabled:
      return Data(repeating: 0, count: 16)

    case let .channelName(name):
      let hash = SHA256.hash(data: Data(name.utf8))
      return Data(hash.prefix(16))

    case let .rawKey(key):
      var padded = key.prefix(16)
      while padded.count < 16 {
        padded.append(0)
      }
      return Data(padded)

    case let .region(name):
      let prefixed = name.hasPrefix("#") ? name : "#\(name)"
      let hash = SHA256.hash(data: Data(prefixed.utf8))
      return Data(hash.prefix(16))
    }
  }
}

/// Defines the secret used for channel encryption.
public enum ChannelSecret: Sendable {
  /// An explicit 16-byte secret.
  case explicit(Data)
  /// A secret derived from the channel name.
  case deriveFromName

  /// Generates the secret data for the given channel name.
  ///
  /// - Parameter channelName: The name of the channel.
  /// - Returns: A 16-byte `Data` object containing the secret.
  public func secretData(channelName: String) -> Data {
    switch self {
    case let .explicit(data):
      var padded = data.prefix(16)
      while padded.count < 16 {
        padded.append(0)
      }
      return Data(padded)

    case .deriveFromName:
      let hash = SHA256.hash(data: Data(channelName.utf8))
      return Data(hash.prefix(16))
    }
  }
}
