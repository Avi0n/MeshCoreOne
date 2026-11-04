/// Removing a failed pairing advances its caller only after confirmed, owned removal.
public enum ConnectionRemovalResult: Sendable {
  case removed(ConnectionLossReceipt)
  case cancelled
  case superseded
}
