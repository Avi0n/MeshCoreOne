import Foundation
import MeshCore

/// Attributes received packets to the flood region they were transmitted under, synchronously and
/// without a session.
///
/// Region matching is `MeshCore.TransportCodeRegionResolver` — the firmware HMAC over
/// `[payloadType nibble] || payload` keyed by the region's scope key — so it belongs below the
/// boundary with the rest of the protocol. A caller hands down the region names it knows and the
/// packets it holds; scope keys are derived here and never cross.
///
/// The batch is the point of the shape: a region list arriving after packets were logged re-derives
/// every held row at once, so the whole log crosses in one call rather than one call per row.
enum RegionResolveService {
  /// A transport code is two bytes. The header field can carry more than one code; only the first
  /// takes part in matching, and a field too short to hold one attributes nothing.
  private static let minTransportCodeBytes = 2

  /// Resolves every row in an encoded request, returning an encoded response. A malformed request
  /// answers with a failure envelope rather than trapping, matching the RX-log decrypt entry point.
  static func resolve(requestBytes: [UInt8]) -> [UInt8] {
    do {
      let request = try RegionResolveCodec.decode(request: requestBytes)
      let keys = scopeKeys(for: request.regionNames)
      let hits = request.rows.map { match(row: $0, scopeKeys: keys) }
      return RegionResolveCodec.encode(hits: hits).serialized()
    } catch {
      let classified = BridgeErrorCode.classify(error)
      return RegionResolveCodec.encode(failure: classified.code, message: classified.message).serialized()
    }
  }

  /// Derives one scope key per usable region name, dropping the names the resolver rejects
  /// (`$`-prefixed private regions and blank names). Derived once per request rather than once per
  /// row, which is what keeps a thousand-row batch to one derivation pass.
  static func scopeKeys(for regionNames: [String]) -> [(name: String, key: Data)] {
    regionNames.compactMap { name in
      guard let key = TransportCodeRegionResolver.deriveScopeKey(regionName: name) else { return nil }
      return (name: name, key: key)
    }
  }

  /// Dual storage fields for one row. Unique: trimmed name plus a one-element match list.
  /// None or an unreadable transport code: nil unique and empty matches. Ambiguous: nil unique
  /// and every matching name so the chip can show the collision instead of Unknown.
  static func match(
    row: RegionResolveCodec.Row,
    scopeKeys: [(name: String, key: Data)]
  ) -> RegionResolveCodec.Hit {
    guard row.transportCode.count >= minTransportCodeBytes else {
      return RegionResolveCodec.Hit(uniqueName: nil, matches: [])
    }
    switch TransportCodeRegionResolver.matchRegions(
      scopeKeys: scopeKeys,
      expectedTransportCode0: row.transportCode.readUInt16LE(at: 0),
      payloadTypeBits: row.payloadTypeBits,
      payload: row.payload
    ) {
    case let .unique(name):
      return RegionResolveCodec.Hit(uniqueName: name, matches: [name])
    case let .ambiguous(names):
      return RegionResolveCodec.Hit(uniqueName: nil, matches: names)
    case .none:
      return RegionResolveCodec.Hit(uniqueName: nil, matches: [])
    }
  }
}
