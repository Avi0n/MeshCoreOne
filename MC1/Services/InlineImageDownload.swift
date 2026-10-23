import Foundation

enum InlineImageDownload {
  private static let htmlMimeType = "text/html"

  static func data(for request: URLRequest, using session: URLSession, byteLimit: Int) async throws -> (Data, URLResponse) {
    let (bytes, response) = try await session.bytes(for: request, delegate: RedirectSafetyDelegate(upgradeToHTTPS: false))
    defer { bytes.task.cancel() }

    if (response as? HTTPURLResponse)?.mimeType == htmlMimeType {
      return (Data(), response)
    }

    guard response.expectedContentLength <= Int64(byteLimit) else {
      throw URLError(.dataLengthExceedsMaximum)
    }

    var data = Data()
    for try await byte in bytes {
      guard data.count < byteLimit else {
        throw URLError(.dataLengthExceedsMaximum)
      }
      data.append(byte)
    }
    return (data, response)
  }
}
