import Foundation

extension MockDataProvider {
  /// Unique and ambiguous flood-region rows. `decodedText` is omitted because
  /// it is `@Transient` on `RxLogEntry` and `saveRxLogEntry` does not persist it.
  static var rxLogEntries: [RxLogEntryDTO] {
    [
      rxLogEntry(
        id: uniqueRxLogEntryID,
        receivedAtOffset: -3600,
        packetPayload: Data([0x01, 0xAA, 0xBB]),
        regionScope: uniqueRegionName,
        regionScopeMatches: [uniqueRegionName]
      ),
      rxLogEntry(
        id: ambiguousRxLogEntryID,
        receivedAtOffset: -1800,
        packetPayload: Data([0x02, 0xCC, 0xDD]),
        regionScope: nil,
        regionScopeMatches: ambiguousRegionNames
      )
    ]
  }

  private static let seedTransportCode = Data([0x11, 0x22, 0x33, 0x44])

  private static func rxLogEntry(
    id: UUID,
    receivedAtOffset: TimeInterval,
    packetPayload: Data,
    regionScope: String?,
    regionScopeMatches: [String]
  ) -> RxLogEntryDTO {
    let parsed = ParsedRxLogData(
      snr: 8.0,
      rssi: -70,
      rawPayload: Data([0x15, 0x01, 0x02, 0x03]),
      routeType: .tcFlood,
      payloadType: .groupText,
      payloadVersion: 0,
      payloadTypeBits: 5,
      transportCode: seedTransportCode,
      pathLength: 1,
      pathNodes: [0x42],
      packetPayload: packetPayload
    )
    return RxLogEntryDTO(
      id: id,
      radioID: simulatorDeviceID,
      receivedAt: Date().addingTimeInterval(receivedAtOffset),
      from: parsed,
      channelIndex: publicChannelIndex,
      channelName: "Public",
      decryptStatus: .success,
      regionScope: regionScope,
      regionScopeMatches: regionScopeMatches
    )
  }
}
