import MC1Services
import OSLog
import SwiftUI

private let chatLinkRouterLogger = Logger(subsystem: "com.mc1", category: "ChatLinkRouter")

/// Shared routing for chat-content URLs. Single source of truth for what counts
/// as a "chat-relevant" tap inside any chat surface. `ChatsView` calls it today;
/// `ChatConversationView` forwards non-mention URLs here once its child
/// `OpenURLAction` is wired. Mutates `appState.navigation` and spawns
/// fire-and-forget `Task`s for async lookups.
///
/// Returns `true` for any chat-relevant scheme so SwiftUI does not fall through
/// to the system URL handler; returns `false` only for truly external schemes
/// (`http`, `https`, `mailto`, …).
///
/// Does not handle `meshcoreone://mention/...` — that scheme is intentionally
/// local to `ChatConversationView`, which intercepts mentions in its own child
/// `OpenURLAction` before forwarding everything else to this router.
@MainActor
enum ChatLinkRouter {
  static func route(_ url: URL, appState: AppState) -> Bool {
    if url.scheme == MeshCoreURLParser.scheme {
      handleMeshCoreLink(url, appState: appState)
      return true
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

  private static func handleMeshCoreLink(_ url: URL, appState: AppState) {
    let urlString = url.absoluteString

    if let coordinate = MeshCoreURLParser.parseMapURL(urlString) {
      appState.navigation.navigateToMap(coordinate: coordinate)
    } else if let contactResult = MeshCoreURLParser.parseContactURL(urlString) {
      handleContactLink(contactResult, appState: appState)
    } else if let channelResult = MeshCoreURLParser.parseChannelURL(urlString) {
      handleChannelLink(channelResult, appState: appState)
    } else {
      chatLinkRouterLogger.error("Failed to parse meshcore URL: \(urlString, privacy: .public)")
    }
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
