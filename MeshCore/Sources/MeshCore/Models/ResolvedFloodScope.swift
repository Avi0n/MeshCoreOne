/// A zero-key ``FloodScope/disabled`` uses the radio's persisted default.
/// ``unscoped`` overrides that default so packets carry no region.
public enum ResolvedFloodScope: Sendable, Equatable {
  /// Force unscoped floods, overriding the radio's default. Requires firmware v12+.
  case unscoped
  /// Set the session's flood scope, or reset it with ``FloodScope/disabled``.
  case scope(FloodScope)
}
