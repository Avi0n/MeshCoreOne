import Foundation

/// Per-release What's New notes, keyed by the `major.minor` they belong to. Add a
/// release as one `WhatsNewRelease` plus its `L10n` strings; it presents once on upgrade.
enum WhatsNewCatalog {
    static let releases: [WhatsNewRelease] = []
}
