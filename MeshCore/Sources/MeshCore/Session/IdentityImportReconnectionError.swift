/// Outcomes specific to the private-key exchange's admission and first response.
public enum IdentityImportReconnectionError: Error, Sendable {
  case uncertainResponseHistory
  case rejected(MeshCoreError)
}
