import Foundation

/// Per-release What's New notes, keyed by the `major.minor` they belong to. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on upgrade.
enum WhatsNewCatalog {
  static let releases: [WhatsNewRelease] = [
    WhatsNewRelease(
      version: WhatsNewVersion(major: 1, minor: 3),
      items: [
        WhatsNewItem(
          symbol: "bubble.left.and.bubble.right",
          title: L10n.WhatsNew.WhatsNew.FasterChats.title,
          description: L10n.WhatsNew.WhatsNew.FasterChats.description
        ),
        WhatsNewItem(
          symbol: "person.crop.circle.badge.plus",
          title: L10n.WhatsNew.WhatsNew.ContactPhotos.title,
          description: L10n.WhatsNew.WhatsNew.ContactPhotos.description
        ),
        WhatsNewItem(
          symbol: "map",
          title: L10n.WhatsNew.WhatsNew.MapFilters.title,
          description: L10n.WhatsNew.WhatsNew.MapFilters.description
        )
      ],
      releaseNotesURL: URL(string: "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.3.0")!
    )
  ]
}
