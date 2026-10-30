import Foundation

/// URLProtocol's callbacks can race with cancellation; the recursive lock also permits client reentry.
final class OfflineMapURLProtocol: URLProtocol, @unchecked Sendable {
  private static let providerDomain = "openfreemap.org"
  private static let queue: OfflineMapRequestQueue = {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = []
    configuration.urlCache = nil
    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    return OfflineMapRequestQueue(session: URLSession(configuration: configuration))
  }()

  private let lock = NSRecursiveLock()
  private var isStopped = false
  private var requestID: UUID?

  override static func canInit(with request: URLRequest) -> Bool {
    guard let url = request.url,
          url.scheme == "https" || url.scheme == "http",
          let host = url.host?.lowercased() else { return false }
    return host == providerDomain || host.hasSuffix("." + providerDomain)
  }

  override static func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    let id = Self.queue.enqueue(request) { [self] data, response, error in
      lock.withLock {
        guard !isStopped else { return }
        if let error {
          client?.urlProtocol(self, didFailWithError: error)
          return
        }
        if let response { client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
        guard !isStopped else { return }
        if let data { client?.urlProtocol(self, didLoad: data) }
        guard !isStopped else { return }
        client?.urlProtocolDidFinishLoading(self)
      }
    }
    let wasStopped = lock.withLock {
      requestID = id
      return isStopped
    }
    if wasStopped { Self.queue.cancel(id) }
  }

  override func stopLoading() {
    let id = lock.withLock {
      isStopped = true
      return requestID
    }
    if let id { Self.queue.cancel(id) }
  }
}
