import Foundation

/// Re-validates redirect destinations so link and image requests cannot
/// follow an initially safe URL to a private host.
final class RedirectSafetyDelegate: NSObject, URLSessionTaskDelegate {
  private let upgradeToHTTPS: Bool

  init(upgradeToHTTPS: Bool = true) {
    self.upgradeToHTTPS = upgradeToHTTPS
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest,
    completionHandler: @escaping @Sendable (URLRequest?) -> Void
  ) {
    guard let hop = request.url else {
      completionHandler(nil)
      return
    }
    let destination = upgradeToHTTPS ? LinkPreviewService.httpsScrapeURL(for: hop) : hop
    // Following the URL that just responded would repeat the response.
    if let responded = response.url?.absoluteString, destination.absoluteString == responded {
      completionHandler(nil)
      return
    }
    Task {
      let isSafe = await URLSafetyChecker.isSafe(destination)
      guard isSafe else {
        completionHandler(nil)
        return
      }
      var followed = request
      followed.url = destination
      completionHandler(followed)
    }
  }
}
