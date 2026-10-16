import MC1Services

extension ChannelDTO {
  /// Localized display name, falling back to a default name based on channel index.
  var displayName: String {
    name.isEmpty ? L10n.Chats.Chats.Channel.defaultName(Int(index)) : name
  }
}
