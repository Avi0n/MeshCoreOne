import MC1Services

enum WatchlistFavoriteExplanation {
  /// Favorite keeps a Watchlist repeater from being overwritten when the radio's node list is full.
  static func locksFavorite(type: ContactType, isMyNode: Bool, isFavorite: Bool) -> Bool {
    type == .repeater && isMyNode && isFavorite
  }

  static func text(type: ContactType, isMyNode: Bool, isFavorite: Bool) -> String? {
    guard locksFavorite(type: type, isMyNode: isMyNode, isFavorite: isFavorite) else { return nil }
    return L10n.Localizable.Error.ContactService.removeFromWatchlistBeforeUnfavorite
  }

  static func actionsFooter(type: ContactType, isMyNode: Bool, isFavorite: Bool) -> String? {
    if let text = text(type: type, isMyNode: isMyNode, isFavorite: isFavorite) { return text }
    guard type == .repeater, !isMyNode else { return nil }
    return L10n.Contacts.Contacts.Watchlist.addMarksFavorite
  }
}
