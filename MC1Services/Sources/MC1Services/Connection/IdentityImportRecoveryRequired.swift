/// The radio's identity could have changed without a confirmed wire result.
public struct IdentityImportRecoveryRequired: Error, Sendable {
  public let recovery: IdentityImportRecovery
}
