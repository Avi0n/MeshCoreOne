import Foundation

/// How a conversation load fills the timeline.
enum ChatPopulateMode {
  /// Fetch the first page and replace the loaded window.
  case replace
  /// Refetch the already-loaded window in place. Falls back to `replace`
  /// sizing when this conversation is not already presenting.
  case refreshWindow
}
