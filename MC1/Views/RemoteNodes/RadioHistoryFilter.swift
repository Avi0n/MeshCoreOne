import MC1Services

/// Screens a status capture as a group so invalid signals cannot leave counter spikes in its charts.
enum RadioHistoryFilter {
  private static let snrRange = -32.0...31.75
  private static let signalStrengthRange: ClosedRange<Int16> = -256...98

  static func includes(_ snapshot: NodeStatusSnapshotDTO) -> Bool {
    if let snr = snapshot.lastSNR, !snrRange.contains(snr) { return false }
    if let rssi = snapshot.lastRSSI, !signalStrengthRange.contains(rssi) { return false }
    if let noise = snapshot.noiseFloor, !signalStrengthRange.contains(noise) { return false }
    return true
  }
}
