import Foundation
@testable import MC1
@testable import MC1Services
import UserNotifications

extension ChatViewModelReadStateTests {
  enum Kind: CaseIterable, Sendable {
    case direct, channel, room

    @MainActor
    func conversation(in environment: Environment) -> Conversation {
      switch self {
      case .direct: .direct(environment.contact)
      case .channel: .channel(environment.channel)
      case .room: .room(environment.room)
      }
    }

    @MainActor
    func request(identifier: String, in environment: Environment) -> UNNotificationRequest {
      let content = UNMutableNotificationContent()
      content.userInfo = switch self {
      case .direct: ["contactID": environment.contact.id.uuidString]
      case .channel: ["channelIndex": Int(environment.channel.index), "radioID": environment.radioID.uuidString]
      case .room: ["sessionID": environment.room.id.uuidString]
      }
      return UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
    }

    @MainActor
    func setReadState(_ isRead: Bool, in environment: Environment) async throws {
      switch self {
      case .direct: try await environment.store.setContactReadState(environment.contact.id, isRead: isRead)
      case .channel: try await environment.store.setChannelReadState(environment.channel.id, isRead: isRead)
      case .room: try await environment.store.setRoomReadState(environment.room.id, isRead: isRead)
      }
    }

    @MainActor
    func unreadCount(in environment: Environment) async throws -> Int? {
      switch self {
      case .direct: try await environment.store.fetchContact(id: environment.contact.id)?.unreadCount
      case .channel: try await environment.store.fetchChannel(id: environment.channel.id)?.unreadCount
      case .room: try await environment.store.fetchRemoteNodeSession(id: environment.room.id)?.unreadCount
      }
    }

    @MainActor
    func delete(in environment: Environment) async throws {
      switch self {
      case .direct: try await environment.store.deleteContact(id: environment.contact.id)
      case .channel: try await environment.store.deleteChannel(id: environment.channel.id)
      case .room: try await environment.store.deleteRemoteNodeSession(id: environment.room.id)
      }
    }
  }
}
