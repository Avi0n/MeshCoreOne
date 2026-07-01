import CoreGraphics

/// Fixed geometry for the chat map thumbnail. Shared by the renderer (snapshot
/// size) and the fragment view (frame). The size is intentionally a constant,
/// not part of `MapSnapshotRequest` — it never varies, so it must not shard the
/// cache.
enum MapSnapshotLayout {
  static let width: CGFloat = 250
  static let height: CGFloat = 150
  static let cornerRadius: CGFloat = 12
  /// `MLNMapSnapshotOptions` has no MapKit-style span; zoom is the framing
  /// control. Approximate framing — the exact pin is shown on tap. Tune visually.
  static let zoomLevel: Double = 14
}
