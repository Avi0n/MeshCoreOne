import Foundation
import MapLibre
@testable import MC1
import Synchronization
import Testing

@Suite("Offline map network", .serialized)
struct OfflineMapNetworkTests {
  @Test
  func `limits requests until they complete`() async throws {
    Transport.reset()
    let session = makeSession()
    defer { session.invalidateAndCancel() }
    let queue = OfflineMapRequestQueue(session: session)
    let completed = Mutex(0)

    for index in 0..<10 {
      var request = try URLRequest(url: #require(URL(string: "https://tiles.openfreemap.org/\(index)")))
      request.setValue("MC1/1.6.2 MapLibre", forHTTPHeaderField: "User-Agent")
      request.setValue("cached-etag", forHTTPHeaderField: "If-None-Match")
      queue.enqueue(request) { _, _, _ in completed.withLock { $0 += 1 } }
    }

    #expect(await eventually { Transport.startedCount == 4 })
    #expect(Transport.maximumActive == 4)
    #expect(Transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "User-Agent") == "MC1/1.6.2 MapLibre" })
    #expect(Transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "If-None-Match") == "cached-etag" })
    Transport.finishActive()
    #expect(await eventually { Transport.startedCount == 8 })
    Transport.finishActive()
    #expect(await eventually { Transport.startedCount == 10 })
    Transport.finishActive()
    #expect(await eventually { completed.withLock { $0 } == 10 })
    #expect(Transport.maximumActive == 4)
  }

  @Test
  func `canceled queued request never starts`() async throws {
    Transport.reset()
    let session = makeSession()
    defer { session.invalidateAndCancel() }
    let queue = OfflineMapRequestQueue(session: session)
    let completed = Mutex(0)
    let ids = try (0..<6).map { index in
      try queue.enqueue(URLRequest(url: #require(URL(string: "https://tiles.openfreemap.org/\(index)")))) { _, _, _ in
        completed.withLock { $0 += 1 }
      }
    }
    #expect(await eventually { Transport.startedCount == 4 })
    queue.cancel(ids[4])
    Transport.finishActive()
    #expect(await eventually { Transport.startedCount == 5 })
    #expect(!Transport.requests.contains { $0.url?.lastPathComponent == "4" })
    #expect(Transport.requests.contains { $0.url?.lastPathComponent == "5" })
    Transport.finishActive()
    #expect(await eventually { completed.withLock { $0 } == 5 })
  }

  @Test
  func `canceling active request releases its slot without exceeding limit`() async throws {
    Transport.reset()
    let session = makeSession()
    defer { session.invalidateAndCancel() }
    let queue = OfflineMapRequestQueue(session: session)
    let cancellations = Mutex(0)
    let completed = Mutex(0)
    let ids = try (0..<6).map { index in
      try queue.enqueue(URLRequest(url: #require(URL(string: "https://tiles.openfreemap.org/\(index)")))) { _, _, error in
        if (error as? URLError)?.code == .cancelled { cancellations.withLock { $0 += 1 } }
        completed.withLock { $0 += 1 }
      }
    }
    #expect(await eventually { Transport.startedCount == 4 })
    queue.cancel(ids[0])
    #expect(await eventually { cancellations.withLock { $0 } == 1 && Transport.startedCount == 5 })
    #expect(Transport.maximumActive == 4)
    Transport.finishActive()
    #expect(await eventually { Transport.startedCount == 6 })
    Transport.finishActive()
    #expect(await eventually { completed.withLock { $0 } == 6 })
  }

  @Test @MainActor
  func `map libre uses the provider request queue`() throws {
    let protocols = try #require(MLNNetworkConfiguration.sharedManager.sessionConfiguration?.protocolClasses)
    #expect(protocols.contains { $0 == OfflineMapURLProtocol.self })
  }

  @Test(arguments: [
    ("https://tiles.openfreemap.org/planet", true),
    ("https://openfreemap.org/style", true),
    ("https://a.tile.opentopomap.org/tile", false),
  ])
  func `only handles provider requests`(url: String, expected: Bool) throws {
    let request = try URLRequest(url: #require(URL(string: url)))
    #expect(OfflineMapURLProtocol.canInit(with: request) == expected)
  }

  private func makeSession() -> URLSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [Transport.self]
    return URLSession(configuration: configuration)
  }

  private func eventually(_ predicate: () -> Bool) async -> Bool {
    for _ in 0..<200 {
      if predicate() { return true }
      try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
  }

  private final class Transport: URLProtocol, @unchecked Sendable {
    private struct State {
      var active: [String: Transport] = [:]
      var requests: [URLRequest] = []
      var maximumActive = 0
    }

    private static let state = Mutex(State())

    static var startedCount: Int {
      state.withLock { $0.requests.count }
    }

    static var maximumActive: Int {
      state.withLock { $0.maximumActive }
    }

    static var requests: [URLRequest] {
      state.withLock { $0.requests }
    }

    static func reset() {
      state.withLock { $0 = State() }
    }

    static func finishActive() {
      let protocols = state.withLock { state in
        let protocols = Array(state.active.values)
        state.active.removeAll()
        return protocols
      }
      for entry in protocols {
        guard let url = entry.request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil) else { continue }
        entry.client?.urlProtocol(entry, didReceive: response, cacheStoragePolicy: .notAllowed)
        entry.client?.urlProtocol(entry, didLoad: Data([1]))
        entry.client?.urlProtocolDidFinishLoading(entry)
      }
    }

    override static func canInit(with request: URLRequest) -> Bool {
      true
    }

    override static func canonicalRequest(for request: URLRequest) -> URLRequest {
      request
    }

    override func startLoading() {
      Self.state.withLock { state in
        state.active[request.url!.absoluteString] = self
        state.requests.append(request)
        state.maximumActive = max(state.maximumActive, state.active.count)
      }
    }

    override func stopLoading() {
      _ = Self.state.withLock { $0.active.removeValue(forKey: request.url!.absoluteString) }
    }
  }
}
