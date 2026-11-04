extension MessageService {
  func beginInlineSend() throws {
    guard !inlineSendAdmissionClosed else { throw MessageServiceError.notConnected }
    inlineSendsInFlight += 1
  }

  func endInlineSend() {
    inlineSendsInFlight -= 1
    guard inlineSendsInFlight == 0 else { return }
    let waiters = inlineSendWaiters
    inlineSendWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  func retireInlineSends() async {
    inlineSendAdmissionClosed = true
    guard inlineSendsInFlight > 0 else { return }
    await withCheckedContinuation { inlineSendWaiters.append($0) }
  }
}
