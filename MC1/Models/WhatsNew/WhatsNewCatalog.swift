import Foundation

/// Per-release What's New notes, keyed by the `major.minor` they belong to. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on upgrade.
enum WhatsNewCatalog {
  static let releases: [WhatsNewRelease] = [
    WhatsNewRelease(
      version: WhatsNewVersion(major: 1, minor: 1),
      items: [
        WhatsNewItem(
          symbol: "wand.and.rays",
          title: L10n.WhatsNew.WhatsNew.SiriShortcuts.title,
          description: L10n.WhatsNew.WhatsNew.SiriShortcuts.description
        ),
        WhatsNewItem(
          symbol: "trash",
          title: L10n.WhatsNew.WhatsNew.ClearMessages.title,
          description: L10n.WhatsNew.WhatsNew.ClearMessages.description
        ),
        WhatsNewItem(
          symbol: "antenna.radiowaves.left.and.right",
          title: L10n.WhatsNew.WhatsNew.InboundHops.title,
          description: L10n.WhatsNew.WhatsNew.InboundHops.description
        )
      ]
    )
  ]
}
