import Foundation
import SwiftUI

/// Builds the VoiceOver "open" actions for a message bubble's links. The passive body renderer
/// (`MessageBodyTextView`) installs no link interactions, so the body carries no VoiceOver link
/// rotor; these actions restore activation for every link kind. The list is derived from the same
/// precomputed `AttributedString` the body renders, so the actions can never diverge from the
/// visible links.
///
/// Built during the bubble's `body`, which is gated by `UnifiedMessageBubble`'s `Equatable`-on-
/// `item` seam, so it is never rebuilt while scrolling; and SwiftUI realizes the action descriptors
/// only when an assistive technology focuses the element, so they cost nothing when VoiceOver is off.
enum MessageLinkAccessibility {
  /// One openable link surfaced as a VoiceOver custom action.
  struct Action: Identifiable {
    let name: String
    let url: URL
    var id: URL {
      url
    }
  }

  /// Upper bound on the actions a single message contributes, so a message packed with links
  /// cannot flood the rotor. Links past the cap stay visible but are not separately activatable.
  static let maxActions = 8

  /// Openable links in document order: the preview card's URL first (if any), then each linked
  /// run in the body text. Deduplicated by URL and capped at `maxActions`.
  static func actions(previewURL: URL?, formatted: AttributedString?) -> [Action] {
    var seen: Set<URL> = []
    var actions: [Action] = []

    func append(_ url: URL) {
      guard actions.count < maxActions, seen.insert(url).inserted else { return }
      actions.append(Action(name: name(for: url), url: url))
    }

    if let previewURL {
      append(previewURL)
    }
    if let formatted {
      for run in formatted.runs {
        guard let url = run.link else { continue }
        append(url)
      }
    }
    return actions
  }

  /// A localized, target-bearing action name so a VoiceOver user can tell two links apart. Reuses
  /// the same parsers the tap router does, and falls back to the generic "Open Link" when a kind
  /// exposes no readable target.
  private static func name(for url: URL) -> String {
    let openLink = L10n.Chats.Chats.Message.Action.openLink

    switch url.scheme?.lowercased() {
    case "http", "https":
      guard let host = url.host(), !host.isEmpty else { return openLink }
      return L10n.Chats.Chats.Message.Action.openWebLink(host)

    case MeshCoreURLParser.scheme:
      switch url.host() {
      case "map":
        return L10n.Chats.Chats.Message.Action.openMapLink
      case "contact":
        guard let name = MeshCoreURLParser.parseContactURL(url.absoluteString)?.name else { return openLink }
        return L10n.Chats.Chats.Message.Action.addContact(name)
      case "channel":
        guard let name = MeshCoreURLParser.parseChannelURL(url.absoluteString)?.name else { return openLink }
        return L10n.Chats.Chats.Message.Action.openChannel(name)
      default:
        return openLink
      }

    case MentionDeeplinkSupport.scheme:
      switch url.host() {
      case MentionDeeplinkSupport.host:
        guard let name = MentionDeeplinkSupport.name(from: url) else { return openLink }
        return L10n.Chats.Chats.Message.Action.openMention(name)
      case "hashtag":
        guard let channel = hashtagName(from: url) else { return openLink }
        return L10n.Chats.Chats.Message.Action.openHashtag(channel)
      default:
        return openLink
      }

    default:
      return openLink
    }
  }

  /// Last path component of a `meshcoreone://hashtag/<channel>` link, percent-decoded.
  private static func hashtagName(from url: URL) -> String? {
    var path = url.path(percentEncoded: true)
    if path.hasPrefix("/") { path.removeFirst() }
    guard !path.isEmpty, let decoded = path.removingPercentEncoding, !decoded.isEmpty else { return nil }
    return decoded
  }
}
