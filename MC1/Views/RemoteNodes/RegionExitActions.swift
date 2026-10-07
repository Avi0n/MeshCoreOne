/// Repeater region exit. Room settings omit this value.
/// `errorMessage` and `unsavedChanges` are read after save; `hasUnsavedChanges` can be stale by then.
struct RegionExitActions {
  var hasUnsavedChanges: Bool
  var save: () async -> Void
  var errorMessage: () -> String?
  var unsavedChanges: () -> Bool
  var discard: () -> Void
}
