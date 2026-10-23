import Foundation
import UserNotifications

public extension NotificationService {
  static func deliveredNotificationIdentifiers(
    forContactID contactID: UUID, systemOperations: SystemOperations = .live
  ) async -> [String] {
    await deliveredNotificationIdentifiers(forContactIDs: [contactID], systemOperations: systemOperations)
  }

  static func deliveredNotificationIdentifiers(
    forContactIDs contactIDs: Set<UUID>, systemOperations: SystemOperations = .live
  ) async -> [String] {
    guard !contactIDs.isEmpty else { return [] }
    let ids = Set(contactIDs.map(\.uuidString))
    return await deliveredNotificationIdentifiers(systemOperations: systemOperations) { request in
      guard let contactID = request.content.userInfo["contactID"] as? String else { return false }
      return ids.contains(contactID)
    }
  }

  static func deliveredNotificationIdentifiers(
    forChannelIndex channelIndex: UInt8, radioID: UUID, systemOperations: SystemOperations = .live
  ) async -> [String] {
    await deliveredNotificationIdentifiers(systemOperations: systemOperations) { request in
      let userInfo = request.content.userInfo
      return userInfo["channelIndex"] as? Int == Int(channelIndex)
        && userInfo["radioID"] as? String == radioID.uuidString
    }
  }

  static func deliveredNotificationIdentifiers(
    forRoomSessionID sessionID: UUID, systemOperations: SystemOperations = .live
  ) async -> [String] {
    await deliveredNotificationIdentifiers(systemOperations: systemOperations) { request in
      request.content.userInfo["sessionID"] as? String == sessionID.uuidString
    }
  }

  private static func deliveredNotificationIdentifiers(
    systemOperations: SystemOperations, matching: (UNNotificationRequest) -> Bool
  ) async -> [String] {
    let requests = await systemOperations.deliveredRequests()
    return requests.filter(matching).map(\.identifier)
  }
}
