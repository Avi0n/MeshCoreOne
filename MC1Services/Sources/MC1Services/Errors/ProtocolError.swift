import Foundation

/// Error codes returned by the device
public enum ProtocolError: UInt8, Sendable, Error {
  case unsupportedCommand = 0x01
  case notFound = 0x02
  case tableFull = 0x03
  case badState = 0x04
  case fileIOError = 0x05
  case illegalArgument = 0x06
}

extension ProtocolError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .unsupportedCommand: "Command not supported by device firmware."
    case .notFound: "Item not found on device."
    case .tableFull: "Device storage is full."
    case .badState: "Device is in an invalid state for this operation."
    case .fileIOError: "Device file system error."
    case .illegalArgument: "Invalid parameter sent to device."
    }
  }
}
