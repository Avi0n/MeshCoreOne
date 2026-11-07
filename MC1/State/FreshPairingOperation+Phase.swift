extension FreshPairingOperation {
  enum Phase: Equatable {
    case running(Attempt)
    case awaitingConfirmation
    case awaitingForegroundRetry
  }
}
