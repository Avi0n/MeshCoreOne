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
                    title: L10n.Localizable.WhatsNew.SiriShortcuts.title,
                    description: L10n.Localizable.WhatsNew.SiriShortcuts.description
                ),
                WhatsNewItem(
                    symbol: "map.fill",
                    title: L10n.Localizable.WhatsNew.MapMemory.title,
                    description: L10n.Localizable.WhatsNew.MapMemory.description
                ),
                WhatsNewItem(
                    symbol: "trash",
                    title: L10n.Localizable.WhatsNew.ClearMessages.title,
                    description: L10n.Localizable.WhatsNew.ClearMessages.description
                ),
                WhatsNewItem(
                    symbol: "at",
                    title: L10n.Localizable.WhatsNew.JumpToMentions.title,
                    description: L10n.Localizable.WhatsNew.JumpToMentions.description
                ),
                WhatsNewItem(
                    symbol: "antenna.radiowaves.left.and.right",
                    title: L10n.Localizable.WhatsNew.InboundHops.title,
                    description: L10n.Localizable.WhatsNew.InboundHops.description
                ),
                WhatsNewItem(
                    symbol: "keyboard",
                    title: L10n.Localizable.WhatsNew.Composing.title,
                    description: L10n.Localizable.WhatsNew.Composing.description
                )
            ]
        )
    ]
}
