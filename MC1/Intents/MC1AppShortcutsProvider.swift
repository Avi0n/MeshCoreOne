import AppIntents

/// The app's single `AppShortcutsProvider`, exposing the read-only status glance,
/// the send-message intent, and the send-advert intent to Siri and Spotlight.
/// Phrases must each carry `\(.applicationName)` or the system rejects the
/// utterance. Only an `AppEntity`/`AppEnum` parameter may be interpolated into a
/// phrase, so the send phrases bind the target (resolving "Message <name> in
/// MeshCore One" by voice) and the advert phrase binds the reach, but neither
/// binds free text.
struct MC1AppShortcutsProvider: AppShortcutsProvider {
  static var appShortcuts: [AppShortcut] {
    AppShortcut(
      intent: StatusQueryIntent(),
      phrases: [
        "Check radio status in \(.applicationName)",
        "What's my radio battery in \(.applicationName)",
        "Is my radio connected in \(.applicationName)"
      ],
      shortTitle: LocalizedStringResource("intent.status.shortTitle", table: "Tools"),
      systemImageName: "antenna.radiowaves.left.and.right"
    )
    AppShortcut(
      intent: SendMessageIntent(),
      phrases: [
        "Send a message in \(.applicationName)",
        "Message \(\.$target) in \(.applicationName)"
      ],
      shortTitle: LocalizedStringResource("intent.send.shortTitle", table: "Tools"),
      systemImageName: "paperplane"
    )
    AppShortcut(
      intent: SendAdvertIntent(),
      phrases: [
        "Send an advert in \(.applicationName)",
        "Send a \(\.$reach) advert in \(.applicationName)"
      ],
      shortTitle: LocalizedStringResource("intent.advert.shortTitle", table: "Tools"),
      systemImageName: "dot.radiowaves.left.and.right"
    )
  }
}
