import MC1Services

extension RoomPermissionLevel {
    /// Localized display label for room info and status rows.
    var localizedName: String {
        switch self {
        case .guest: L10n.RemoteNodes.RemoteNodes.Permission.guest
        case .readWrite: L10n.RemoteNodes.RemoteNodes.Permission.member
        case .admin: L10n.RemoteNodes.RemoteNodes.Permission.admin
        }
    }
}
