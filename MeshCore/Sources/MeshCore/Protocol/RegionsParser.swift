import Foundation

// MARK: - Regions Parser

enum RegionsParser {
  /// Parses a region query response from binary response data.
  ///
  /// The response data layout (after the binary response parser strips the frame header):
  /// - Offset 0–3 (4 bytes): Repeater timestamp (UInt32 LE) — skipped
  /// - Offset 4+ (variable): Comma-separated UTF-8 region names
  ///
  /// The sender timestamp (4 bytes) is already consumed as the tag by the binary response parser.
  ///
  /// - Parameter responseData: The `data` field from `.binaryResponse(tag:data:)`.
  /// - Returns: An array of region name strings. Empty array if no regions are configured.
  /// - Throws: ``MeshCoreError/parseError(_:)`` if the response is too short or not valid UTF-8.
  static func parse(_ responseData: Data) throws -> [String] {
    guard responseData.count >= 4 else {
      throw MeshCoreError.parseError("Region response too short (\(responseData.count) bytes)")
    }
    let regionData = responseData.dropFirst(4)
    guard let regionString = String(data: regionData, encoding: .utf8) else {
      throw MeshCoreError.parseError("Invalid UTF-8 in region response")
    }
    let trimmed = regionString.trimmingCharacters(in: .controlCharacters)
    if trimmed.isEmpty { return [] }
    return trimmed.split(separator: ",")
      .map { $0.trimmingCharacters(in: .whitespaces) }
      .filter { !$0.isEmpty && $0 != "*" }
  }
}
