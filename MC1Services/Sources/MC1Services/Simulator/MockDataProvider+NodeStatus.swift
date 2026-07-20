import Foundation

public extension MockDataProvider {
  /// The node whose seeded location history the demo showcases: Hannah Lee, a
  /// direct chat contact, reached via Contacts, Hannah Lee, then Saved History,
  /// which pushes the offline telemetry overview with the location list and map.
  static var locationHistoryNodePublicKey: Data {
    mockPublicKey(seed: 80)
  }

  /// A seeded run of node status snapshots carrying a plausible GPS track for
  /// `locationHistoryNodePublicKey`, so the location History list and map render
  /// with real content in the simulator and demo mode.
  ///
  /// The track is shaped to exercise the map and the History time filter:
  /// - Four separate outings spread across the past six months, so switching the
  ///   time range visibly adds and drops rows and pins: Week shows only today's
  ///   outing, Month adds the 14-day one, 3 Months adds the 55-day one, and All
  ///   shows the 180-day one too.
  /// - Each outing's fixes sit within the connected interval, but the weeks-long
  ///   gaps between outings (and a 2.5 h pause inside today's) exceed it, so the
  ///   trail draws as separate dashed segments with no phantom line bridging them.
  /// - Altitudes on every fix but one, so the row/callout renders both the
  ///   present and absent altitude paths.
  /// - The newest fix sits at Hannah Lee's advertised coordinate.
  ///
  /// Timestamps are relative to seed time; `SimulatorConnectionMode` seeds this
  /// once per install (skipped when the node already has snapshots) so the track
  /// isn't duplicated on every reconnect.
  static var nodeStatusSnapshots: [NodeStatusSnapshotDTO] {
    let now = Date()
    let key = locationHistoryNodePublicKey

    // (minutesAgo, latitude, longitude, altitudeMeters), oldest first. Four outings
    // dated so the time filter has content to add and drop. A minute is 1440 per day,
    // so the leading values place each outing a set number of days back.
    let minutesPerDay = 1440.0
    let track: [(Double, Double, Double, Double?)] = [
      // ~180 days ago: reachable only under the All filter.
      (180 * minutesPerDay + 30, 37.7690, -122.4830, 30),
      (180 * minutesPerDay + 15, 37.7695, -122.4800, 35),
      (180 * minutesPerDay, 37.7700, -122.4770, 40),
      // ~55 days ago: reachable under 3 Months and All.
      (55 * minutesPerDay + 30, 37.7585, -122.4270, 25),
      (55 * minutesPerDay + 15, 37.7600, -122.4255, 28),
      (55 * minutesPerDay, 37.7615, -122.4240, 32),
      // ~14 days ago: reachable under Month, 3 Months, and All.
      (14 * minutesPerDay + 30, 37.7980, -122.4650, 50),
      (14 * minutesPerDay + 15, 37.7995, -122.4620, 58),
      (14 * minutesPerDay, 37.8010, -122.4590, 64),
      // Today: a morning loop, then a 2.5 h pause, then an afternoon walk that ends
      // at Hannah's advertised spot.
      (300, 37.7350, -122.4770, 55),
      (285, 37.7340, -122.4780, 58),
      (270, 37.7330, -122.4785, 60),
      (255, 37.7325, -122.4790, 57),
      (240, 37.7320, -122.4795, 54),
      // The pause exceeds the connected interval, so the trail breaks here.
      (90, 37.7260, -122.4800, 48),
      (72, 37.7230, -122.4798, 50),
      (54, 37.7200, -122.4796, 46),
      (36, 37.7180, -122.4795, nil), // a fix the node reported without altitude
      (18, 37.7160, -122.4794, 41),
      (5, 37.7149, -122.4794, 42), // newest: Hannah Lee's advertised coordinate
    ]

    // Battery drains gently across the outing so the diagnostics rows read as real.
    let startMillivolts = 4050
    let endMillivolts = 3860

    return track.enumerated().map { index, fix in
      let (minutesAgo, latitude, longitude, altitude) = fix
      let progress = Double(index) / Double(max(track.count - 1, 1))
      let millivolts = startMillivolts - Int(Double(startMillivolts - endMillivolts) * progress)
      return NodeStatusSnapshotDTO(
        timestamp: now.addingTimeInterval(-minutesAgo * 60),
        nodePublicKey: key,
        batteryMillivolts: UInt16(millivolts),
        lastSNR: 8.5 - progress * 3,
        lastRSSI: -72,
        noiseFloor: -108,
        uptimeSeconds: UInt32(6 * 3600 + index * 1800),
        latitude: latitude,
        longitude: longitude,
        altitude: altitude
      )
    }
  }
}
