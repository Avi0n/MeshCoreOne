import Foundation
import MeshCore

/// Contact commands other services run through `ContactService` so they share one operation gate.
/// Cleanup holders keep this reference weak; `ServiceContainer` retains the service.
public protocol ContactRadioOwning: AnyObject, Sendable {
  func resetPath(radioID: UUID, publicKey: Data) async throws
  func healAbsentRadioContact(radioID: UUID, publicKey: Data) async throws -> Bool
  func importRadioContact(radioID: UUID, contact: MeshContact) async throws
}

/// Late binding for a service constructed before `ContactService` exists.
public final class ContactRadioOwner: @unchecked Sendable {
  private let lock = NSLock()
  private weak var owner: (any ContactRadioOwning)?

  public init() {}

  public func bind(_ owner: (any ContactRadioOwning)?) {
    lock.lock()
    defer { lock.unlock() }
    self.owner = owner
  }

  public func resolve() -> (any ContactRadioOwning)? {
    lock.lock()
    defer { lock.unlock() }
    return owner
  }
}
