/// Discriminates the two send targets a `MessageTargetEntity` can represent. Raw
/// values are pinned strings because they are embedded in the framework-persisted
/// composite id; a case reorder must never change a saved id.
enum MessageTargetKind: String {
  case contact
  case channel
}
