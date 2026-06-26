import Foundation

/// The leading `major.minor` of a marketing version, ignoring patch. `Comparable`
/// is lexicographic on `(major, minor)`, so "a 0.1 bump or more" is just `>`.
struct WhatsNewVersion: Comparable, Hashable {
    let major: Int
    let minor: Int

    static func < (lhs: WhatsNewVersion, rhs: WhatsNewVersion) -> Bool {
        (lhs.major, lhs.minor) < (rhs.major, rhs.minor)
    }
}

extension WhatsNewVersion {
    /// Parses the leading `major.minor`; returns `nil` for anything unparseable
    /// (`"unknown"`, `"2"`, a TestFlight `"1.0 (123)"`) so the sheet fails closed.
    init?(marketingVersion: String) {
        let components = marketingVersion.split(separator: ".")
        guard components.count >= 2,
              let major = Int(components[0]),
              let minor = Int(components[1]) else {
            return nil
        }
        self.init(major: major, minor: minor)
    }
}
