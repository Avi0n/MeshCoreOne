import Foundation

/// A domain failure can affect the caller only while this request still owns the manager.
public struct ConnectionRequestFailure: LocalizedError {
  public let requestID: UUID
  public let underlying: any Error
  public var errorDescription: String? {
    underlying.localizedDescription
  }
}
