import Foundation

/// Pre-fallback direct route snapshot saved for reconnect replay after a link drop.
public struct PendingRouteRestore: Sendable, Equatable {
  public let radioID: UUID
  public let publicKey: Data
  public let outPath: Data
  public let outPathLength: UInt8

  public init(radioID: UUID, publicKey: Data, outPath: Data, outPathLength: UInt8) {
    self.radioID = radioID
    self.publicKey = publicKey
    self.outPath = outPath
    self.outPathLength = outPathLength
  }
}
