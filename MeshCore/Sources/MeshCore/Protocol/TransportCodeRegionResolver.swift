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
    internal static func rewriteReservedCode(_ rawCode: UInt16) -> UInt16 {
        if rawCode == 0 {
            return transportCodeMinValue
        } else if rawCode == 0xFFFF {
            return transportCodeMaxValue
        }
        return rawCode
    }

    /// Find the matching region name for a packet, given a precomputed
    /// `[(regionName, scopeKey)]` array. First match wins (mirrors firmware
    /// iteration order).
    ///
    /// Empty-array input short-circuits to nil. Callers (e.g. `RxLogService`)
    /// own the cache and must rebuild it whenever the known-regions list
    /// changes.
    public static func findMatchingRegion(
        scopeKeys: [(name: String, key: Data)],
        expectedTransportCode0: UInt16,
        payloadTypeBits: UInt8,
        payload: Data
    ) -> String? {
        guard !scopeKeys.isEmpty else { return nil }
        for (name, key) in scopeKeys {
            let code = calcTransportCode(
                scopeKey: key,
                payloadTypeBits: payloadTypeBits,
                payload: payload
            )
            if code == expectedTransportCode0 {
                return name
            }
        }
        return nil
    }
}
