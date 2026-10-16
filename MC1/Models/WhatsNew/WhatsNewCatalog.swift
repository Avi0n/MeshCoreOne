import Foundation

/// Per-release What's New notes, keyed by the `major.minor` they belong to. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on upgrade.
enum WhatsNewCatalog {
  static let releases: [WhatsNewRelease] = [
    WhatsNewRelease(
      version: WhatsNewVersion(major: 1, minor: 5),
      items: [
        WhatsNewItem(
          symbol: "point.topleft.down.to.point.bottomright.curvepath",
          title: L10n.WhatsNew.WhatsNew.PathDetails.title,
          description: L10n.WhatsNew.WhatsNew.PathDetails.description
        ),
        WhatsNewItem(
          symbol: "face.smiling",
          title: L10n.WhatsNew.WhatsNew.WhoReacted.title,
          description: L10n.WhatsNew.WhatsNew.WhoReacted.description
        ),
        WhatsNewItem(
          symbol: "bolt",
          title: L10n.WhatsNew.WhatsNew.FasterChatLoading.title,
          description: L10n.WhatsNew.WhatsNew.FasterChatLoading.description
        )
      ],
      releaseNotesURL: URL(string: "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.5.0")!
    ),
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
