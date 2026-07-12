import Foundation

/// `Identifiable` by its `major.minor` version so a release drives `.sheet(item:)`.
struct WhatsNewRelease: Identifiable {
  let version: WhatsNewVersion
  let items: [WhatsNewItem]

  var id: WhatsNewVersion {
    version
  }
}
