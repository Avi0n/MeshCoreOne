import Foundation
@testable import MC1
import os
import Testing

@Suite("Inline image download limits", .timeLimit(.minutes(1)))
struct InlineImageDownloadLimitTests {
  @Test(arguments: [false, true], LengthHeader.allCases)
  private func `Oversized responses stop before the body finishes`(probe: Bool, lengthHeader: LengthHeader) async throws {
    let limit = probe ? Self.probeLimit : Self.imageLimit
    let fixture = Fixture(byteCount: limit * 2, lengthHeader: lengthHeader)
    let url = try #require(URL(string: "https://media.giphy.com/\(UUID().uuidString).png"))
    StreamingImageURLProtocol.register(fixture, for: url)
    let session = Self.session()
    defer {
      session.invalidateAndCancel()
      StreamingImageURLProtocol.removeFixture(for: url)
    }
    let cache = InlineImageCache(session: session)

    if probe {
      #expect(await cache.probeImageDimensions(url: url) == nil)
    } else {
      let result = await cache.fetchImageData(for: url)
      guard case .failed = result else {
        Issue.record("An oversized image returned \(result)")
        return
      }
    }

    var events = fixture.termination.makeAsyncIterator()
    let terminal = try #require(await events.next())
    #expect(!terminal.finishedBody)
    #expect(terminal.emittedBytes < fixture.byteCount)
  }

  @Test(arguments: [false, true], [false, true])
  func `Normal and exactly at cap images still load`(probe: Bool, exactlyAtCap: Bool) async throws {
    let limit = probe ? Self.probeLimit : Self.imageLimit
    let byteCount = exactlyAtCap ? limit : Self.smallImageBytes
    let fixture = Fixture(byteCount: byteCount, lengthHeader: .actual)
    let url = try #require(URL(string: "https://media.giphy.com/\(UUID().uuidString).png"))
    StreamingImageURLProtocol.register(fixture, for: url)
    let session = Self.session()
    defer {
      session.invalidateAndCancel()
      StreamingImageURLProtocol.removeFixture(for: url)
    }
    let cache = InlineImageCache(session: session)

    if probe {
      #expect(await cache.probeImageDimensions(url: url) == CGSize(width: 1, height: 1))
    } else {
      let result = await cache.fetchImageData(for: url)
      guard case let .loaded(data) = result else {
        Issue.record("An image within the cap did not load: \(result)")
        return
      }
      #expect(data.count == byteCount)
    }

    var events = fixture.termination.makeAsyncIterator()
    let terminal = try #require(await events.next())
    #expect(terminal.finishedBody)
    #expect(terminal.emittedBytes == byteCount)
  }

  private static let probeLimit = 1024 * 1024
  private static let imageLimit = 10 * 1024 * 1024
  private static let smallImageBytes = 1024

  private static func session() -> URLSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StreamingImageURLProtocol.self]
    return URLSession(configuration: configuration)
  }

  private enum LengthHeader: CaseIterable, Sendable {
    case actual, missing, understated
  }

  private struct Terminal: Sendable {
    let emittedBytes: Int
    let finishedBody: Bool
  }

  private final class Fixture: Sendable {
    let byteCount: Int
    let lengthHeader: LengthHeader
    let termination: AsyncStream<Terminal>
    private let continuation: AsyncStream<Terminal>.Continuation
    private let state = OSAllocatedUnfairLock(initialState: State())

    init(byteCount: Int, lengthHeader: LengthHeader) {
      self.byteCount = byteCount
      self.lengthHeader = lengthHeader
      (termination, continuation) = AsyncStream.makeStream()
    }

    func recordEmission(_ count: Int) {
      state.withLock { $0.emittedBytes += count }
    }

    func terminate(finishedBody: Bool) {
      let terminal = state.withLock { state -> Terminal? in
        guard !state.terminated else { return nil }
        state.terminated = true
        return Terminal(emittedBytes: state.emittedBytes, finishedBody: finishedBody)
      }
      if let terminal {
        continuation.yield(terminal)
        continuation.finish()
      }
    }

    private struct State {
      var emittedBytes = 0
      var terminated = false
    }
  }

  private final class StreamingImageURLProtocol: URLProtocol {
    private static let fixtures = OSAllocatedUnfairLock(initialState: [URL: Fixture]())
    private static let chunkBytes = 256 * 1024
    private static let chunkSpacing = Duration.milliseconds(5)
    private static let image = Data(base64Encoded:
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1XkAAAAASUVORK5CYII=")!
    private let streamingState = OSAllocatedUnfairLock(initialState: StreamingState())

    static func register(_ fixture: Fixture, for url: URL) {
      fixtures.withLock { $0[url] = fixture }
    }

    static func removeFixture(for url: URL) {
      fixtures.withLock { $0[url] = nil }
    }

    // swiftlint:disable:next static_over_final_class
    override class func canInit(with request: URLRequest) -> Bool {
      true
    }

    // swiftlint:disable:next static_over_final_class
    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
      request
    }

    override func startLoading() {
      guard let url = request.url, let fixture = Self.fixtures.withLock({ $0[url] }) else { return }
      var headers = ["Content-Type": "image/png"]
      switch fixture.lengthHeader {
      case .actual:
        headers["Content-Length"] = String(fixture.byteCount)
      case .missing:
        break
      case .understated:
        headers["Content-Length"] = String(InlineImageDownloadLimitTests.smallImageBytes)
      }
      let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
      client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)

      // Body callbacks run on one stream task; cancellation touches lock-guarded state.
      nonisolated(unsafe) let source = self
      streamingState.withLock { state in
        guard !state.stopped else { return }
        state.task = Task { @Sendable [fixture] in
          var emitted = 0
          while emitted < fixture.byteCount {
            do {
              try await Task.sleep(for: StreamingImageURLProtocol.chunkSpacing)
            } catch {
              return
            }
            let count = min(StreamingImageURLProtocol.chunkBytes, fixture.byteCount - emitted)
            var chunk = Data(count: count)
            if emitted == 0 {
              chunk.replaceSubrange(0..<StreamingImageURLProtocol.image.count, with: StreamingImageURLProtocol.image)
            }
            fixture.recordEmission(count)
            source.client?.urlProtocol(source, didLoad: chunk)
            emitted += count
          }
          fixture.terminate(finishedBody: true)
          source.client?.urlProtocolDidFinishLoading(source)
        }
      }
    }

    override func stopLoading() {
      streamingState.withLock { state in
        state.stopped = true
        state.task?.cancel()
      }
      if let url = request.url, let fixture = Self.fixtures.withLock({ $0[url] }) {
        fixture.terminate(finishedBody: false)
      }
    }

    private struct StreamingState {
      var task: Task<Void, Never>?
      var stopped = false
    }
  }
}
