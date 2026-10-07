import Foundation

/// Re-validates every HTTP redirect hop of `LinkPreviewService`'s scrape
/// session against the SSRF allow-list. A default `URLSession` follows 3xx
/// redirects automatically, so without this a URL that passes the initial
/// `isSafe` check could redirect to a private host and be fetched anyway.
final class RedirectSafetyDelegate: NSObject, URLSessionTaskDelegate {
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
    let upgraded = LinkPreviewService.httpsScrapeURL(for: hop)
    // The upgraded hop is the URL that just responded, so following it repeats.
    if let responded = response.url?.absoluteString, upgraded.absoluteString == responded {
      completionHandler(nil)
      return
    }
    Task {
      let isSafe = await URLSafetyChecker.isSafe(upgraded)
      guard isSafe else {
        completionHandler(nil)
        return
      }
      var followed = request
      followed.url = upgraded
      completionHandler(followed)
    }
  }
}
