import MC1Services
import SwiftUI
import UIKit

extension ContactDetailState {
  enum ActiveSheet: Identifiable, Hashable {
    case nodeAuth
    case adminAuth
    case repeaterStatus(RemoteNodeSessionDTO)
    case roomStatus(RemoteNodeSessionDTO)
    case nodeTelemetry(ContactDTO)
    case remoteCLI(ContactDTO)
    case adminSettings(RemoteNodeSessionDTO)

    var id: String {
      switch self {
      case .nodeAuth: "auth"
      case .adminAuth: "admin-auth"
      case let .repeaterStatus(session): "status-\(session.id)"
      case let .roomStatus(session): "room-status-\(session.id)"
      case let .nodeTelemetry(contact): "telemetry-\(contact.id)"
      case let .remoteCLI(contact): "remote-cli-\(contact.id)"
      case let .adminSettings(session): "admin-settings-\(session.id)"
      }
    }
  }

  struct AvatarCropRequest: Identifiable {
    let id = UUID()
    let image: UIImage
  }

  enum ScrollSection: String, CaseIterable {
    case profile, actions, info, location, outPath, technical, danger
  }

  enum Action: Hashable {
    case block, delete, clearMessages, share, ping, nickname, refresh, discoverPath, editPath, resetPath
  }

  struct OperationContext: Equatable {
    let servicesVersion: Int?
    let radioID: UUID?
  }

  struct Dependencies {
    var contactService: @MainActor () -> ContactService?
    var dataStore: @MainActor () -> PersistenceStore?
    var advertisements: @MainActor () -> AdvertisementService?
    var notifications: @MainActor () -> NotificationService?
    var connectedDevice: @MainActor () -> DeviceDTO?
  }
}
