import Foundation

/// Outcome of a `MeshCoreSession.login` exchange.
public struct LoginExchangeResult: Sendable, Equatable {
  /// Present when authentication succeeded; `nil` when the node rejected the login.
  public let info: LoginInfo?
  /// True when an unconsumed earlier login to the same contact with a different password
  /// could have produced this reply, so callers must not mutate stored credentials.
  public let mayAnswerEarlierLogin: Bool

  public var succeeded: Bool {
    info != nil
  }

  public init(info: LoginInfo?, mayAnswerEarlierLogin: Bool = false) {
    self.info = info
    self.mayAnswerEarlierLogin = mayAnswerEarlierLogin
  }
}
