import Foundation
import MeshCore

extension MessageService {
  private static let floodScopeMinimumFirmwareVersion: UInt8 = 8

  func sendChannelMessageWithScope(
    _ transmission: ChannelTransmission,
    channelIndex: UInt8,
    radioID: UUID,
    timestamp: Date
  ) async throws {
    let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex)
    let device = try await dataStore.fetchDevice(radioID: radioID)
    let scope = ChannelFloodScopeResolver.resolve(
      channelFloodScope: channel?.floodScope ?? .inherit,
      supportsUnscopedFloodSend: device?.supportsUnscopedFloodSend ?? false
    )

    let appliedScope: ResolvedFloodScope? = if let device,
                                               device.firmwareVersion < Self.floodScopeMinimumFirmwareVersion,
                                               scope == .scope(.disabled) {
      nil
    } else {
      scope
    }
    switch transmission {
    case let .mcoImage(payload):
      if let appliedScope {
        try await session.sendChannelData(
          channelIndex: channelIndex,
          dataType: MCOImageTransport.applicationDataType,
          payload: payload,
          floodScope: appliedScope
        )
      } else {
        try await session.sendChannelData(
          channelIndex: channelIndex,
          dataType: MCOImageTransport.applicationDataType,
          payload: payload,
          pathLength: PacketBuilder.floodPathSentinel,
          pathBytes: Data()
        )
      }
    case let .text(text):
      if let appliedScope {
        try await session.sendChannelMessage(
          channel: channelIndex,
          text: text,
          timestamp: timestamp,
          floodScope: appliedScope
        )
      } else {
        try await session.sendChannelMessage(channel: channelIndex, text: text, timestamp: timestamp)
      }
    }
  }
}
