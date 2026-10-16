import MC1Services
import OSLog
import SwiftUI

private let chatLinkRouterLogger = Logger(subsystem: "com.mc1", category: "ChatLinkRouter")

/// Shared routing for chat-content URLs. Single source of truth for what counts
/// as a "chat-relevant" tap inside any chat surface. Two caller sets: in-chat
/// surfaces (`ChatsView`, and `ChatConversationView` via the `OpenURLAction`
/// that forwards its non-mention URLs) and the app-lifecycle path
/// (`MC1App.onOpenURL` via `routeExternalOpen`, for URLs iOS hands over from a
/// scanned QR code or another app). Mutates `appState.navigation` and spawns
/// fire-and-forget `Task`s for async lookups.
///
/// Returns `true` for any URL whose scheme (and host, for `meshcoreone`) the
/// router claims, so SwiftUI does not fall through to the system URL handler;
/// returns `false` for truly external schemes (`http`, `https`, `mailto`, …)
/// and for a `meshcore://` URL no parser could match.
///
/// Does not handle `meshcoreone://mention/...` — that scheme is intentionally
/// local to `ChatConversationView`, which intercepts mentions in its own child
/// `OpenURLAction` before forwarding everything else to this router.
@MainActor
enum ChatLinkRouter {
  static func route(_ url: URL, appState: AppState) -> Bool {
    if url.scheme == MeshCoreURLParser.scheme {
      return handleMeshCoreLink(url, appState: appState)
    }
    if url.scheme == HashtagDeeplinkSupport.scheme,
       url.host == HashtagDeeplinkSupport.host {
      if let channelName = HashtagDeeplinkSupport.channelNameFromURL(url) {
        handleHashtagTap(name: channelName, appState: appState)
      } else {
        chatLinkRouterLogger.error("Hashtag URL missing host: \(url.absoluteString, privacy: .public)")
      }
      return true
    }
    return false
  }

  /// Routes a URL iOS handed to the app from outside a chat (a scanned QR code
  /// or a tap from another app), via `MC1App.onOpenURL`. Switches to the Chats
  /// tab first so the new-contact/new-channel confirmation sheets, which only
  /// present from Chats, have their host; restores the prior tab when nothing
  /// was handled (`meshcoreone://status`, or a malformed `meshcore://` URL).
  @discardableResult
  static func routeExternalOpen(_ url: URL, appState: AppState) -> Bool {
    let previousTab = appState.navigation.selectedTab
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let handled = route(url, appState: appState)
    if !handled {
      appState.navigation.selectedTab = previousTab
    }
    return handled
  }

  private static func handleMeshCoreLink(_ url: URL, appState: AppState) -> Bool {
    let urlString = url.absoluteString

    if let coordinate = MeshCoreURLParser.parseMapURL(urlString) {
      appState.navigation.navigateToMap(coordinate: coordinate)
      return true
    } else if let contactResult = MeshCoreURLParser.parseContactURL(urlString) {
      handleContactLink(contactResult, appState: appState)
      return true
    } else if let channelResult = MeshCoreURLParser.parseChannelURL(urlString) {
      handleChannelLink(channelResult, appState: appState)
      return true
    } else {
      logFailedMeshCoreParse(url)
      return false
    }
  }

  /// Logs scheme, host, and path only — query values can include channel secrets.
  private static func logFailedMeshCoreParse(_ url: URL) {
    let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
    let hadSecretQuery = components?.queryItems?.contains { item in
      item.name == "secret" && !(item.value?.isEmpty ?? true)
    } ?? false
    chatLinkRouterLogger.error(
      "Failed to parse meshcore URL scheme=\(url.scheme ?? "", privacy: .public) host=\(url.host() ?? "", privacy: .public) path=\(url.path(), privacy: .public) hadSecretQuery=\(hadSecretQuery)"
    )
  }

  private static func handleContactLink(
    _ result: MeshCoreURLParser.ContactResult,
    appState: AppState
  ) {
    Task { @MainActor in
      if result.publicKey == appState.connectedDevice?.publicKey {
        return
      }

      if let deviceID = appState.currentRadioID,
         let existingContact = try? await appState.offlineDataStore?.fetchContact(
           radioID: deviceID,
           publicKey: result.publicKey
         ) {
        appState.navigation.navigateToContactDetail(existingContact)
      } else {
        appState.navigation.pendingContactLink = result
      }
    }
  }

  private static func handleChannelLink(
    _ result: MeshCoreURLParser.ChannelResult,
    appState: AppState
  ) {
    Task { @MainActor in
      if let deviceID = appState.currentRadioID,
         let channels = try? await appState.offlineDataStore?.fetchChannels(radioID: deviceID),
         let existingChannel = channels.first(where: { $0.secret == result.secret }) {
        appState.navigation.navigateToChannel(with: existingChannel)
      } else {
        appState.navigation.pendingChannelLink = result
      }
    }
  }

  private static func handleHashtagTap(name: String, appState: AppState) {
    Task { @MainActor in
      guard let fullName = HashtagDeeplinkSupport.fullChannelName(from: name) else {
        chatLinkRouterLogger.error("Invalid hashtag name in tap: \(name, privacy: .public)")
        return
      }

      guard let deviceID = appState.currentRadioID else {
        appState.navigation.pendingHashtag = HashtagJoinRequest(id: fullName)
        return
      }

      do {
        if let channel = try await HashtagDeeplinkSupport.findChannelByName(
          fullName,
          radioID: deviceID,
          fetchChannels: { deviceID in
            try await appState.offlineDataStore?.fetchChannels(radioID: deviceID) ?? []
          }
        ) {
          appState.navigation.navigateToChannel(with: channel)
        } else {
          appState.navigation.pendingHashtag = HashtagJoinRequest(id: fullName)
        }
      } catch {
        chatLinkRouterLogger.error("Failed to fetch channels for hashtag lookup: \(error)")
        appState.navigation.pendingHashtag = HashtagJoinRequest(id: fullName)
      }
    }
  }
}
