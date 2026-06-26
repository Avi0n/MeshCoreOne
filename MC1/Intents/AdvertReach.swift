import AppIntents

/// The reach of a self-advertisement, surfaced as a Shortcuts-editor picker.
/// Zero-hop reaches direct neighbors only; flood propagates across the whole
/// mesh. The `String` raw values (the case names) are the stable identifiers a
/// saved shortcut persists, so a case must never be renamed without preserving
/// its raw value.
enum AdvertReach: String, AppEnum {
    // swiftlint:disable redundant_string_enum_value
    case zeroHop = "zeroHop"
    case flood = "flood"
    // swiftlint:enable redundant_string_enum_value

    static let typeDisplayRepresentation = TypeDisplayRepresentation(
        name: LocalizedStringResource("intent.advert.reach.type", table: "Tools")
    )

    static let caseDisplayRepresentations: [AdvertReach: DisplayRepresentation] = [
        .zeroHop: DisplayRepresentation(title: LocalizedStringResource("intent.advert.reach.zeroHop", table: "Tools")),
        .flood: DisplayRepresentation(title: LocalizedStringResource("intent.advert.reach.flood", table: "Tools"))
    ]

    var sendsFlood: Bool { self == .flood }
}
