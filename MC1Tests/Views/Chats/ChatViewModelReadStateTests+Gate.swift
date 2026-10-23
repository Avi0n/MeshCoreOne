extension ChatViewModelReadStateTests {
  actor Gate {
    private var waiter: CheckedContinuation<Void, Never>?
    private var isOpen = false

    func wait() async {
      if isOpen { return }
      await withCheckedContinuation { waiter = $0 }
    }

    func open() {
      isOpen = true
      waiter?.resume()
      waiter = nil
    }
  }
}
