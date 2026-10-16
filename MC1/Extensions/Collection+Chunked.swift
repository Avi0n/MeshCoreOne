import Foundation

extension Collection {
  /// Split collection into chunks of specified size.
  /// Returns empty array if size <= 0 (prevents infinite loop).
  func chunked(into size: Int) -> [[Element]] {
    guard size > 0 else { return [] }
    return stride(from: 0, to: count, by: size).map {
      Array(self[index(startIndex, offsetBy: $0)..<index(startIndex, offsetBy: Swift.min($0 + size, count))])
    }
  }
}
