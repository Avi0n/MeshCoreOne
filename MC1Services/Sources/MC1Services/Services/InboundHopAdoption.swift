import Foundation

/// Adopt rule for inbound advert hop counts, shared by `PersistenceStore` and its test mock so the
/// policy has a single definition.
///
/// Returns the `(hopCount, advertTimestamp)` pair to store, or nil to no-op (the caller skips the
/// save to avoid `@Observable` churn on repeated floods).
///
/// Rules:
///   - stored timestamp nil -> adopt the incoming pair
///   - incoming.ts > stored.ts -> adopt (newer advert; hop may rise or fall)
///   - incoming.ts == stored.ts && incoming.hops < stored.hops -> adopt (closer copy of this broadcast)
///   - else -> no-op
///
/// A nil incoming timestamp is "no grouping signal": adopted only while the stored timestamp is
/// also nil. Once a timestamp is stored, an ungrouped incoming read is a no-op, since its ordering
/// against the stored value is unknowable.
///
/// Known limitation: a node that reboots and loses its RTC re-advertises with a lower timestamp,
/// freezing the stored count until the row ages out of the discovered-node cap. Reset detection is
/// out of scope.
func adoptInboundHop(
  storedHops: Int?,
  storedTimestamp: UInt32?,
  incomingHops: Int,
  incomingTimestamp: UInt32?
) -> (hopCount: Int, advertTimestamp: UInt32?)? {
  guard let storedTimestamp else {
    return (hopCount: incomingHops, advertTimestamp: incomingTimestamp)
  }
  guard let incomingTimestamp else {
    return nil
  }
  if incomingTimestamp > storedTimestamp {
    return (hopCount: incomingHops, advertTimestamp: incomingTimestamp)
  }
  if incomingTimestamp == storedTimestamp, incomingHops < (storedHops ?? Int.max) {
    return (hopCount: incomingHops, advertTimestamp: incomingTimestamp)
  }
  return nil
}
