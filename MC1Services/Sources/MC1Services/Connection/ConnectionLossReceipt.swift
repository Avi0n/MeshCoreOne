import Foundation

/// A loss notification remains eligible only while its request still owns the manager.
public struct ConnectionLossReceipt: Sendable {
  public let id = UUID()
  public let requestID: UUID
  public let retiredGeneration: UUID?
}
