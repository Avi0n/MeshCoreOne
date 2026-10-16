import Foundation
import MC1Services

struct HashtagJoinRequest: Identifiable, Hashable {
  let id: String
}

enum HashtagDeeplinkSupport {
  static let scheme = "meshcoreone"
  static let host = "hashtag"

  static func channelNameFromURL(_ url: URL) -> String? {
    guard url.scheme == scheme, url.host == host else { return nil }
    let path = url.pathComponents.dropFirst() // drop leading "/"
    return path.first
  }

  static func fullChannelName(from rawName: String) -> String? {
    let normalizedName = HashtagUtilities.normalizeHashtagName(rawName)
    guard HashtagUtilities.isValidHashtagName(normalizedName) else { return nil }
    return "#\(normalizedName)"
  }

  static func findChannelByName(
    _ name: String,
    radioID: UUID,
    fetchChannels: @Sendable (UUID) async throws -> [ChannelDTO]
  ) async throws -> ChannelDTO? {
    let channels = try await fetchChannels(radioID)
    return channels.first(where: { channel in
      channel.name.localizedCaseInsensitiveCompare(name) == .orderedSame
    })
  }
}
