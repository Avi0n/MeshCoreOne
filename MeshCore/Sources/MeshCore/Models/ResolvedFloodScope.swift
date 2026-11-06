/// A flood scope for a channel send. The send restores the radio baseline before the next command.
/// A zero-key ``FloodScope/disabled`` selects the radio's persisted default.
public enum ResolvedFloodScope: Sendable, Equatable {
  /// Force unscoped floods, overriding the radio's default. Requires protocol capability 12+.
  case unscoped
  /// Set the session's flood scope, or select the persisted default with ``FloodScope/disabled``.
  case scope(FloodScope)
}
