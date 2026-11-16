/// Initial queue admission after Message creation. The Message is the creation snapshot;
/// enqueue failure has already attempted to persist `.failed` without downgrading `.delivered`.
public enum ChatSendAdmission: Sendable {
  case queued(MessageDTO)
  case enqueueFailed(MessageDTO, error: any Error)
}
