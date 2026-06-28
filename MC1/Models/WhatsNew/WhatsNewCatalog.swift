import Foundation

/// Per-release What's New notes, keyed by the `CFBundleVersion` build they ship in. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on that build.
///
/// Build 165 is a one-off TestFlight notice repurposing the sheet to announce the App Store
/// launch, thank testers, and explain how the private TestFlight group carries over.
enum WhatsNewCatalog {
    private static let appStoreURL = URL(string: "https://apps.apple.com/app/meshcore-one/id6757419477")

    static let releases: [WhatsNewRelease] = [
        WhatsNewRelease(
            build: 165,
            items: [
                WhatsNewItem(
                    symbol: "heart.fill",
                    title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Thanks.title,
                    description: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Thanks.description
                ),
                WhatsNewItem(
                    symbol: "testtube.2",
                    title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Feedback.title,
                    description: L10n.WhatsNew.WhatsNew.AppStoreLaunch.Feedback.description
                )
            ],
            title: L10n.WhatsNew.WhatsNew.AppStoreLaunch.title,
            subtitle: L10n.WhatsNew.WhatsNew.AppStoreLaunch.subtitle,
            actionURL: appStoreURL,
            actionTitle: L10n.WhatsNew.WhatsNew.AppStoreLaunch.action
        )
    ]
}
