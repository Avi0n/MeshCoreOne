import Foundation

/// Per-release What's New notes, keyed by the `major.minor` they belong to. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on upgrade.
enum WhatsNewCatalog {
  static let releases: [WhatsNewRelease] = [
    WhatsNewRelease(
      version: WhatsNewVersion(major: 1, minor: 4),
      items: [
        WhatsNewItem(
          symbol: "translate",
          title: L10n.WhatsNew.WhatsNew.MessageTranslation.title,
          description: L10n.WhatsNew.WhatsNew.MessageTranslation.description
        ),
        WhatsNewItem(
          symbol: "person.crop.circle",
          title: L10n.WhatsNew.WhatsNew.SenderAvatars.title,
          description: L10n.WhatsNew.WhatsNew.SenderAvatars.description
        ),
        WhatsNewItem(
          symbol: "arrow.clockwise",
          title: L10n.WhatsNew.WhatsNew.FailedSends.title,
          description: L10n.WhatsNew.WhatsNew.FailedSends.description
        )
      ],
      releaseNotesURL: URL(string: "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.4.0")!
    )
  ]
}
