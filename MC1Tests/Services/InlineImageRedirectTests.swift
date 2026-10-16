import Foundation
@testable import MC1
import os
import Testing

@Suite("Inline image redirects")
struct InlineImageRedirectTests {
  @Test(arguments: [false, true], [false, true])
  func `Private redirects are never requested`(probe: Bool, intermediate: Bool) async throws {
    let route = intermediate ? "private-chain" : "private"
    let url = try #require(URL(string: "https://media.giphy.com/\(UUID().uuidString)/\(route)/start.png"))
    let session = Self.session()
    defer {
      session.invalidateAndCancel()
      ImageRedirectURLProtocol.removeRequests(for: url)
    }
    let cache = InlineImageCache(session: session)

    if probe {
      #expect(await cache.probeImageDimensions(url: url) == nil)
    } else {
      let result = await cache.fetchImageData(for: url)
      switch result {
      case .failed:
        break
      default:
        Issue.record("An unsafe redirect returned \(result)")
      }
    }

    let requests = ImageRedirectURLProtocol.requests(for: url)
    #expect(requests.count == (intermediate ? 2 : 1))
    #expect(!requests.contains { $0.host() == ImageRedirectURLProtocol.privateHost })
  }

  @Test(arguments: [false, true], ["http://i.giphy.com:80", "https://i.giphy.com:80", "https://i.giphy.com"])
  func `Allowed redirect chains preserve schemes and ports`(probe: Bool, destination: String) async throws {
    var components = try #require(URLComponents(string: "https://media.giphy.com/\(UUID().uuidString)/public-chain/start.png"))
    components.queryItems = [URLQueryItem(name: "destination", value: destination)]
    let url = try #require(components.url)
    let session = Self.session()
    defer {
      session.invalidateAndCancel()
      ImageRedirectURLProtocol.removeRequests(for: url)
    }
    let cache = InlineImageCache(session: session)

    if probe {
      #expect(await cache.probeImageDimensions(url: url) == CGSize(width: 1, height: 1))
    } else {
      let result = await cache.fetchImageData(for: url)
      guard case let .loaded(data) = result else {
        Issue.record("An allowed redirect did not load: \(result)")
        return
      }
      #expect(data == ImageRedirectURLProtocol.image)
    }
    let requests = ImageRedirectURLProtocol.requests(for: url)
    #expect(requests.count == 3)
    #expect(requests.dropFirst().allSatisfy { $0.absoluteString.hasPrefix("\(destination)/") })
  }

  private static func session() -> URLSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ImageRedirectURLProtocol.self]
    return URLSession(configuration: configuration)
  }

  private final class ImageRedirectURLProtocol: URLProtocol {
    static let privateHost = "127.0.0.1"
    static let image = Data(base64Encoded:
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1XkAAAAASUVORK5CYII=")!
    private static let receivedRequests = OSAllocatedUnfairLock(initialState: [String: [URL]]())

    static func requests(for url: URL) -> [URL] {
      receivedRequests.withLock { $0[url.pathComponents[1]] ?? [] }
    }

    static func removeRequests(for url: URL) {
      receivedRequests.withLock { $0[url.pathComponents[1]] = nil }
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
      guard let url = request.url else { return }
      Self.receivedRequests.withLock { $0[url.pathComponents[1], default: []].append(url) }

      if url.lastPathComponent == "image.png" {
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "image/png"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.image)
        client?.urlProtocolDidFinishLoading(self)
        return
      }

      let route = url.pathComponents[2]
      let intermediate = url.lastPathComponent == "start.png" && route != "private"
      var target = URLComponents(url: url, resolvingAgainstBaseURL: false)!
      target.host = intermediate || route == "public-chain" ? "i.giphy.com" : Self.privateHost
      if let originString = target.queryItems?.first(where: { $0.name == "destination" })?.value,
         let origin = URLComponents(string: originString) {
        target.scheme = origin.scheme
        target.host = origin.host
        target.port = origin.port
      }
      target.path = url.deletingLastPathComponent().appendingPathComponent(intermediate ? "middle.png" : "image.png").path
      let destination = target.url!
      let response = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1",
                                     headerFields: ["Location": destination.absoluteString])!
      var redirectRequest = request
      redirectRequest.url = destination
      client?.urlProtocol(self, wasRedirectedTo: redirectRequest, redirectResponse: response)
      if target.host == Self.privateHost {
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
      }
    }

    override func stopLoading() {}
  }
}
