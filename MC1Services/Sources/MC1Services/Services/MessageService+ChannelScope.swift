import Foundation
import MeshCore

extension MessageService {
  private static let floodScopeMinimumFirmwareVersion: UInt8 = 8

  func sendChannelMessageWithScope(
    text: String,
    channelIndex: UInt8,
    radioID: UUID,
    timestamp: Date,
    expectedChannelSecret: Data? = nil
  ) async throws {
    let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex)
    let device = try await dataStore.fetchDevice(radioID: radioID)
    let scope = ChannelFloodScopeResolver.resolve(
      channelFloodScope: channel?.floodScope ?? .inherit,
      deviceDefaultFloodScopeName: device?.defaultFloodScopeName,
      supportsUnscopedFloodSend: device?.supportsUnscopedFloodSend ?? false
    )

    let appliedScope: ResolvedFloodScope? = if let device,
                                               device.firmwareVersion < Self.floodScopeMinimumFirmwareVersion,
                                               scope == .scope(.disabled) {
      nil
    } else {
      scope
    }
    if let expectedChannelSecret {
      do {
        try await session.sendVerifiedChannelMessage(
          channel: channelIndex,
          text: text,
          timestamp: timestamp,
          floodScope: appliedScope,
          expectedSecret: expectedChannelSecret
        )
      } catch let error as MeshCoreError {
        guard case .invalidResponse = error else { throw error }
        throw MessageServiceError.channelNotFound
      }
    } else if let appliedScope {
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
