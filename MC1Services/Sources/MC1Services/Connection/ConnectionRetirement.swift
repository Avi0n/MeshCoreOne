@MainActor
struct ConnectionRetirement {
  let worker: Task<Void, Never>
  let receipt: ConnectionLossReceipt

  var value: Void {
    get async { await worker.value }
  }
}
