import Foundation

/// Per-release What's New notes, keyed by the `major.minor` they belong to. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on upgrade.
enum WhatsNewCatalog {
  static let releases: [WhatsNewRelease] = [
    WhatsNewRelease(
      version: WhatsNewVersion(major: 1, minor: 6),
      items: [
        WhatsNewItem(
          symbol: "pin",
          title: L10n.WhatsNew.WhatsNew.PinnedChats.title,
          description: L10n.WhatsNew.WhatsNew.PinnedChats.description
        ),
        WhatsNewItem(
          symbol: "envelope.badge",
          title: L10n.WhatsNew.WhatsNew.ReadAndUnread.title,
          description: L10n.WhatsNew.WhatsNew.ReadAndUnread.description
        ),
        WhatsNewItem(
          symbol: "ipad.and.iphone",
          title: L10n.WhatsNew.WhatsNew.IPhoneDuo.title,
          description: L10n.WhatsNew.WhatsNew.IPhoneDuo.description
        ),
        // Flipping the landscape glyph puts its home indicator on top, which is the tab bar.
        WhatsNewItem(
          symbol: "ipad.landscape",
          title: L10n.WhatsNew.WhatsNew.IPadTabBar.title,
          description: L10n.WhatsNew.WhatsNew.IPadTabBar.description,
          flipsSymbolVertically: true
        ),
        WhatsNewItem(
          symbol: "slider.horizontal.3",
          title: L10n.WhatsNew.WhatsNew.AdvancedMapFilters.title,
          description: L10n.WhatsNew.WhatsNew.AdvancedMapFilters.description
        ),
        WhatsNewItem(
          symbol: "arrow.left.arrow.right",
          title: L10n.WhatsNew.WhatsNew.BluetoothAndWiFi.title,
          description: L10n.WhatsNew.WhatsNew.BluetoothAndWiFi.description
        ),
        WhatsNewItem(
          symbol: "list.bullet",
          title: L10n.WhatsNew.WhatsNew.MoreChanges.title,
          description: L10n.WhatsNew.WhatsNew.MoreChanges.description
        )
      ],
      releaseNotesURL: URL(string: "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.6.0")!
    ),
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
    )
  ]
}
