import MC1Services
import SwiftUI

/// Shared BLE scan-orchestration model for the device pickers.
///
/// Owns the one piece both pickers previously duplicated: consuming a `startBLEScanning()`
/// discovery stream, smoothing each peripheral's RSSI, recomputing its `RSSITuning.SignalTier`
/// with hysteresis, and expiring peripherals that stop advertising. The macOS scan picker
/// (`DeviceScannerSheet`) reads `devices` to build its list; the iOS picker
/// (`DeviceSelectionSheet`) reads `signalTier(for:)`/`isAdvertising(_:)` to annotate and gate
/// already-saved rows. Both become thin observers so the scan and expiry logic cannot drift apart.
@Observable
@MainActor
final class RSSIScanTracker {
  /// Currently-advertising peripherals keyed by id, each carrying its smoothed RSSI and most
  /// recently advertised name.
  private(set) var devices: [UUID: DiscoveredDevice] = [:]

  private var signalTiers: [UUID: RSSITuning.SignalTier] = [:]
  private var lastSeen: [UUID: Date] = [:]

  /// The smoothed signal tier for a peripheral, or `nil` if it is not currently advertising.
  func signalTier(for id: UUID) -> RSSITuning.SignalTier? {
    signalTiers[id]
  }

  /// Whether the peripheral has a live, unexpired RSSI sample.
  func isAdvertising(_ id: UUID) -> Bool {
    devices[id] != nil
  }

  /// Consumes a discovery stream until the calling task is cancelled, smoothing RSSI,
  /// recomputing tiers, and expiring stale peripherals on the shared `RSSITuning.expiryTick`
  /// cadence (after `RSSITuning.staleWindow` without a fresh advertisement). Scanning stops
  /// automatically when the stream's producer is torn down on task cancellation.
  func consume(_ stream: AsyncStream<DiscoveredDevice>) async {
    let expiry = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(for: RSSITuning.expiryTick)
        self?.expireStale()
      }
    }
    defer { expiry.cancel() }

    for await discovery in stream {
      guard RSSITuning.isUsable(discovery.rssi) else { continue }
      ingest(discovery)
    }
  }

  private func ingest(_ discovery: DiscoveredDevice) {
    let smoothed = RSSITuning.smooth(newRSSI: discovery.rssi, previousRSSI: devices[discovery.id]?.rssi)
    // Preserve a previously-advertised name when a later packet omits it.
    let name = discovery.name ?? devices[discovery.id]?.name
    devices[discovery.id] = DiscoveredDevice(id: discovery.id, name: name, rssi: smoothed)
    signalTiers[discovery.id] = RSSITuning.tier(currentTier: signalTiers[discovery.id], smoothedRSSI: smoothed)
    lastSeen[discovery.id] = .now
  }

  /// Drops peripherals not seen within `RSSITuning.staleWindow` of `now`. `now` is a seam so
  /// tests can force expiry deterministically; production callers use the default.
  func expireStale(asOf now: Date = .now) {
    let cutoff = now.addingTimeInterval(-RSSITuning.staleWindow)
    for (id, seen) in lastSeen where seen < cutoff {
      lastSeen.removeValue(forKey: id)
      devices.removeValue(forKey: id)
      signalTiers.removeValue(forKey: id)
    }
  }
}
