/// Discriminates the two send targets a `MessageTargetEntity` can represent. Raw
/// values are pinned strings because they are embedded in the framework-persisted
/// composite id; a case reorder must never change a saved id.
enum MessageTargetKind: String {
  // swiftlint:disable redundant_string_enum_value
  case contact
  case channel
  // swiftlint:enable redundant_string_enum_value
}
