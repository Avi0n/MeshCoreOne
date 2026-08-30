import CryptoKit
import Foundation

/// Resolves the per-message flood region a packet was transmitted under, by
/// matching its `transport_codes[0]` against precomputed scope keys for the
/// caller's known regions.
///
/// Ports the firmware algorithm from `TransportKey::calcTransportCode`
/// (`TransportKeyStore.cpp`) and `RegionMap::findMatch` / `getTransportKeysFor`
/// (`RegionMap.cpp`). The resolver itself is stateless: callers own and refresh
/// the `[(name, scopeKey)]` cache.
public enum TransportCodeRegionResolver {
  private static let scopeKeyByteCount = 16
  private static let payloadTypeMask: UInt8 = 0x0F
  private static let transportCodeMinValue: UInt16 = 1
  private static let transportCodeMaxValue: UInt16 = 0xFFFE
  private static let autoRegionPrefix: Character = "#"
  private static let privateRegionPrefix: Character = "$"

  /// Derive the 16-byte scope key for an auto-named region.
  ///
  /// Mirrors firmware `TransportKeyStore::getAutoKeyFor` plus the
  /// `RegionMap::getTransportKeysFor` rule that prepends "#" if the name
  /// does not already start with "#".
  ///
  /// Returns nil for `$`-prefixed (private) regions and empty / whitespace
  /// names — both are filtered out of the matching pipeline.
  public static func deriveScopeKey(regionName: String) -> Data? {
    let trimmed = regionName.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let first = trimmed.first else { return nil }
    if first == privateRegionPrefix { return nil }

    let normalized = (first == autoRegionPrefix) ? trimmed : "#" + trimmed
    let digest = SHA256.hash(data: Data(normalized.utf8))
    return Data(digest.prefix(scopeKeyByteCount))
  }

  /// Compute `transport_codes[0]` for a given scope key and packet body.
  ///
  /// Mirrors `TransportKey::calcTransportCode`: HMAC-SHA256 over
  /// `[payloadTypeBits & 0x0F] || payload`, with the scope key used as the
  /// HMAC key. The first two bytes of the MAC are read as little-endian
  /// `UInt16`, then `0` and `0xFFFF` are rewritten to the reserved-boundary
  /// neighbors (`0x0001` / `0xFFFE`).
  public static func calcTransportCode(
    scopeKey: Data,
    payloadTypeBits: UInt8,
    payload: Data
  ) -> UInt16 {
    var combined = Data(capacity: 1 + payload.count)
    combined.append(payloadTypeBits & payloadTypeMask)
    combined.append(payload)

    let mac = HMAC<SHA256>.authenticationCode(
      for: combined,
      using: SymmetricKey(data: scopeKey)
    )

    var rawCode: UInt16 = 0
    for (offset, byte) in mac.prefix(2).enumerated() {
      rawCode |= UInt16(byte) << (8 * offset)
    }

    return rewriteReservedCode(rawCode)
  }

  /// Rewrite the reserved transport-code values to their reserved-boundary
  /// neighbors. Mirrors firmware `TransportKey::calcTransportCode` lines 12-15.
  /// Internal for direct boundary-test coverage.
  static func rewriteReservedCode(_ rawCode: UInt16) -> UInt16 {
    if rawCode == 0 {
      return transportCodeMinValue
    } else if rawCode == 0xFFFF {
      return transportCodeMaxValue
    }
    return rawCode
  }

  /// Match a packet against every precomputed `[(regionName, scopeKey)]` entry.
  ///
  /// Collects every name whose `calcTransportCode` equals
  /// `expectedTransportCode0` (no first-hit early exit). Blank names are
  /// dropped. Names are sorted with `localizedStandardCompare` so ambiguous
  /// sets are order-stable. Empty input returns `.none`. Live cost is O(R)
  /// HMACs per transport-coded packet; callers own and rebuild the cache.
  public static func matchRegions(
    scopeKeys: [(name: String, key: Data)],
    expectedTransportCode0: UInt16,
    payloadTypeBits: UInt8,
    payload: Data
  ) -> RegionMatchResult {
    guard !scopeKeys.isEmpty else { return .none }

    var matchedNames: [String] = []
    for (name, key) in scopeKeys {
      let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
      guard !trimmed.isEmpty else { continue }
      let code = calcTransportCode(
        scopeKey: key,
        payloadTypeBits: payloadTypeBits,
        payload: payload
      )
      if code == expectedTransportCode0 {
        matchedNames.append(trimmed)
      }
    }

    let uniqueSorted = Array(Set(matchedNames)).sorted {
      $0.localizedStandardCompare($1) == .orderedAscending
    }

    switch uniqueSorted.count {
    case 0:
      return .none
    case 1:
      return .unique(uniqueSorted[0])
    default:
      return .ambiguous(uniqueSorted)
    }
  }
}

/// Result of matching a packet's `transport_codes[0]` against known public
/// region scope keys. Ambiguous sets always carry at least two sorted names.
public enum RegionMatchResult: Equatable, Sendable {
  case none
  case unique(String)
  case ambiguous([String])
}
