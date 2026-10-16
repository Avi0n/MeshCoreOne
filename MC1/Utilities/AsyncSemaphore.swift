import Foundation

/// Simple async semaphore for limiting concurrent operations
actor AsyncSemaphore {
  private var count: Int
  private var waiters: [CheckedContinuation<Void, Never>] = []

  init(value: Int) {
    count = value
  }

  func wait() async {
    if count > 0 {
      count -= 1
      return
    }
    await withCheckedContinuation { continuation in
      waiters.append(continuation)
    }
  }

  func signal() {
    if let waiter = waiters.first {
      waiters.removeFirst()
      waiter.resume()
    } else {
      count += 1
    }
  }
}
