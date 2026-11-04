import Foundation
import Synchronization

/// Counts requests through completion, including canceled requests still stopping in URLSession.
final class OfflineMapRequestQueue: Sendable {
  static let maximumConcurrentRequests = 4

  typealias Completion = @Sendable (Data?, URLResponse?, (any Error)?) -> Void

  private struct Entry {
    let id: UUID
    let request: URLRequest
    let completion: Completion
  }

  private struct State {
    var pending: [Entry] = []
    var active: [UUID: URLSessionDataTask] = [:]
  }

  private let session: URLSession
  private let state = Mutex(State())

  init(session: URLSession) {
    self.session = session
  }

  @discardableResult
  func enqueue(_ request: URLRequest, completion: @escaping Completion) -> UUID {
    let id = UUID()
    state.withLock { state in
      state.pending.append(Entry(id: id, request: request, completion: completion))
      startPending(&state)
    }
    return id
  }

  func cancel(_ id: UUID) {
    state.withLock { state in
      state.pending.removeAll { $0.id == id }
      state.active[id]?.cancel()
    }
  }

  private func startPending(_ state: inout State) {
    while state.active.count < Self.maximumConcurrentRequests, !state.pending.isEmpty {
      let entry = state.pending.removeFirst()
      let task = session.dataTask(with: entry.request) { [self] data, response, error in
        self.state.withLock { state in
          state.active.removeValue(forKey: entry.id)
          startPending(&state)
        }
        entry.completion(data, response, error)
      }
      state.active[entry.id] = task
      task.resume()
    }
  }
}
