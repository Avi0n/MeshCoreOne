import Foundation

/// Parses CommonCLI sensor replies without normalizing firmware keys or values.
enum SensorSettingsResponseParser {
  private static let emptyListResponse = "no custom var"
  private static let listCountSuffix = " vars"
  private static let nextPagePrefix = "... next:"
  private static let valuePrefix = "> "
  private static let missingValueResponse = "null"
  private static let acceptedSetResponse = "ok"
  private static let rejectedSetResponse = "can't find custom var"

  struct Page: Sendable, Equatable {
    let total: Int
    let settings: [SensorSetting]
    let next: Int?
  }

  static func list(_ response: String, offset: Int) throws -> Page {
    if response == emptyListResponse, offset == 0 {
      return Page(total: 0, settings: [], next: nil)
    }
    var lines = response.components(separatedBy: "\n")
    if lines.last == "" { lines.removeLast() }
    guard let header = lines.first, header.hasSuffix(Self.listCountSuffix),
          let total = Int(header.dropLast(Self.listCountSuffix.count)), total > 0,
          offset >= 0, offset < total else { throw RemoteNodeError.invalidResponse }
    lines.removeFirst()
    var next: Int?
    if let last = lines.last, last.hasPrefix(Self.nextPagePrefix) {
      guard let parsed = Int(last.dropFirst(Self.nextPagePrefix.count)) else { throw RemoteNodeError.invalidResponse }
      next = parsed
      lines.removeLast()
    }
    var keys: Set<String> = []
    let settings = try lines.map { line in
      guard let separator = line.firstIndex(of: "=") else { throw RemoteNodeError.invalidResponse }
      let key = String(line[..<separator])
      guard !key.isEmpty, keys.insert(key).inserted else { throw RemoteNodeError.invalidResponse }
      return SensorSetting(key: key, value: String(line[line.index(after: separator)...]))
    }
    let end = offset + settings.count
    guard !settings.isEmpty, end <= total else { throw RemoteNodeError.invalidResponse }
    if let next {
      guard next == end, next > offset, next < total else { throw RemoteNodeError.invalidResponse }
    } else {
      guard end == total else { throw RemoteNodeError.invalidResponse }
    }
    return Page(total: total, settings: settings, next: next)
  }

  static func value(_ response: String) throws -> String? {
    if response == missingValueResponse { return nil }
    guard response.hasPrefix(valuePrefix) else { throw RemoteNodeError.invalidResponse }
    return String(response.dropFirst(valuePrefix.count))
  }

  static func accepted(_ response: String) throws {
    guard response == acceptedSetResponse else {
      if response == rejectedSetResponse { throw RemoteNodeError.sensorSettingRejected }
      throw RemoteNodeError.invalidResponse
    }
  }
}
