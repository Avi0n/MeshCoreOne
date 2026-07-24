import Foundation

// MARK: - Neighbours Parser

/// Specialized parser for remote node neighbour lists.
public enum NeighboursParser {
  /// Parses Neighbours response data from binary protocol.
  ///
  /// - Parameters:
  ///   - data: Raw response data.
  ///   - publicKeyPrefix: Target node's public key prefix.
  ///   - tag: Request tag.
  ///   - prefixLength: Expected length of neighbour pubkey prefixes (default 4).
  /// - Returns: A ``NeighboursResponse`` containing the parsed list.
  ///
  /// ### Binary Format
  /// - Offset 0 (2 bytes): Total neighbours count (Int16 LE)
  /// - Offset 2 (2 bytes): Results count in this response (Int16 LE)
  /// - Entries: `[prefix:N][secs_ago:4][snr:1]` where N = `prefixLength`.
  public static func parse(
    _ data: Data,
    publicKeyPrefix: Data,
    tag: Data,
    prefixLength: Int = 4
  ) -> NeighboursResponse {
    guard data.count >= 4 else {
      return NeighboursResponse(
        publicKeyPrefix: publicKeyPrefix,
        tag: tag,
        totalCount: 0,
        neighbours: []
      )
    }

    let totalCount = Int(data.readInt16LE(at: 0))
    let resultsCount = Int(data.readInt16LE(at: 2))

    var neighbours: [Neighbour] = []
    let entrySize = prefixLength + 4 + 1 // pubkey + secs_ago + snr
    var offset = 4

    for _ in 0..<resultsCount {
      guard offset + entrySize <= data.count else { break }

      let keyPrefix = Data(data[offset..<(offset + prefixLength)])
      offset += prefixLength

      let secondsAgo = Int(data.readInt32LE(at: offset))
      offset += 4

      let snr = Double(Int8(bitPattern: data[offset])) / 4.0
      offset += 1

      neighbours.append(Neighbour(
        publicKeyPrefix: keyPrefix,
        secondsAgo: secondsAgo,
        snr: snr
      ))
    }

    return NeighboursResponse(
      publicKeyPrefix: publicKeyPrefix,
      tag: tag,
      totalCount: totalCount,
      neighbours: neighbours
    )
  }
}
