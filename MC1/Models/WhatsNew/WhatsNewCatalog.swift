import Foundation

/// Per-release What's New notes, keyed by the `CFBundleVersion` build they ship in. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on that build.
///
/// Build 164 is a one-off TestFlight notice repurposing the sheet to announce the App Store
/// launch and walk testers through backing up before installing the public version.
enum WhatsNewCatalog {
    private static let appStoreURL = URL(string: "https://apps.apple.com/app/meshcore-one/id6757419477")

    static let releases: [WhatsNewRelease] = [
        WhatsNewRelease(
            build: 164,
            items: [
                WhatsNewItem(
                    symbol: "sparkles",
                    title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Live.title,
                    description: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Live.description
                ),
                WhatsNewItem(
                    symbol: "antenna.radiowaves.left.and.right.slash",
                    title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Disconnect.title,
                    description: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Disconnect.description
                ),
                WhatsNewItem(
                    symbol: "externaldrive",
                    title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Backup.title,
                    description: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Backup.description
                ),
                WhatsNewItem(
                    symbol: "square.and.arrow.down",
                    title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Install.title,
                    description: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Install.description
                )
            ],
            title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.title,
            footer: L10n.WhatsNew.WhatsNew.AppStoreLaunch.footer,
            actionURL: appStoreURL,
            actionTitle: L10n.WhatsNew.WhatsNew.AppStoreLaunch.action
        )
    ]
}
