import Foundation

/// Drives the Restore Purchases button: disabled + `ProgressView` while `.syncing`,
/// `.sensoryFeedback(.success)` on `.completed` only. `.cancelled` is distinct from `.completed`
/// so a user dismissing the iCloud password sheet does not trigger the success haptic.
enum RestoreState: Equatable {
  case idle
  case syncing
  case completed
  case cancelled
  case failed
}
