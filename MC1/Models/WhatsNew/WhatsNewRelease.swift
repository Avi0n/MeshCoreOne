import Foundation

/// `Identifiable` by its `major.minor` version so a release drives `.sheet(item:)`.
struct WhatsNewRelease: Identifiable {
  let version: WhatsNewVersion
  let items: [WhatsNewItem]
  let releaseNotesURL: URL

  var id: WhatsNewVersion {
    version
  }
}
