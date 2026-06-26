import Foundation

/// `Identifiable` by the `CFBundleVersion` build it ships in so a release drives `.sheet(item:)`.
struct WhatsNewRelease: Identifiable {
    let build: Int
    let items: [WhatsNewItem]
    /// Overrides the default "What's New" sheet title when set.
    var title: String?
    /// Optional closing line shown under the last item.
    var footer: String?
    /// When set, the sheet shows a prominent link button alongside Continue.
    var actionURL: URL?
    var actionTitle: String?

    var id: Int { build }
}
