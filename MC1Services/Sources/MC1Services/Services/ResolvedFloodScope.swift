import Foundation
import MeshCore

/// The flood-scope action ``ChannelFloodScopeResolver`` resolves to for a conversation.
///
/// Distinguishes a true un-scoped override (firmware sub-command 1, sets `send_unscoped`)
/// from a concrete ``FloodScope`` push (sub-command 0). A zero-key ``FloodScope/disabled``
/// resets the session scope and lets the device fall back to its persisted default, so it
/// cannot stand in for an explicit "all regions" override on firmware that supports one.
public enum ResolvedFloodScope: Sendable, Equatable {
  /// Force un-scoped flood broadcasts, overriding the device default. Push via
  /// ``MeshCoreSession/setFloodScopeUnscoped()``. Requires firmware v12+.
  case unscoped
  /// Push a concrete ``FloodScope`` via ``MeshCoreSession/setFloodScope(_:)``.
  case scope(FloodScope)
}
