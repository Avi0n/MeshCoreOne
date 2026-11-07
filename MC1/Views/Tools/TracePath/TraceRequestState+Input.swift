extension TraceRequestState {
  struct Input {
    let ticket: Epoch.Ticket
    let isBatch: Bool
    let path: [UInt8]
    let flags: UInt8

    var hashSize: Int {
      1 << Int(flags)
    }
  }
}
