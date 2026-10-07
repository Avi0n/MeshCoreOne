/// Repeater region exit. Room settings omit this value.
/// `errorMessage` and `unsavedChanges` are read when the decision is made, including after save.
struct RegionExitActions {
  var save: () async -> Void
  var errorMessage: () -> String?
  var unsavedChanges: () -> Bool
  var discard: () -> Void
}
