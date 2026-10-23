import Foundation
import MeshCore

extension MessageService {
  private static let floodScopeMinimumFirmwareVersion: UInt8 = 8

  func sendChannelMessageWithScope(
    text: String,
    channelIndex: UInt8,
    radioID: UUID,
    timestamp: Date
  ) async throws {
    let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex)
    let device = try await dataStore.fetchDevice(radioID: radioID)
    let scope = ChannelFloodScopeResolver.resolve(
      channelFloodScope: channel?.floodScope ?? .inherit,
      deviceDefaultFloodScopeName: device?.defaultFloodScopeName,
      supportsUnscopedFloodSend: device?.supportsUnscopedFloodSend ?? false
    )

    if let device,
       device.firmwareVersion < Self.floodScopeMinimumFirmwareVersion,
       scope == .scope(.disabled) {
      try await session.sendChannelMessage(channel: channelIndex, text: text, timestamp: timestamp)
    } else {
      try await session.sendChannelMessage(
        channel: channelIndex,
        text: text,
        timestamp: timestamp,
        floodScope: scope
      )
    }
  }
}
