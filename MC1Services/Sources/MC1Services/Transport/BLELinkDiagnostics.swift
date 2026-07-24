// BLELinkDiagnostics.swift
import Foundation

/// One consistent snapshot of the transport's observable state, read in a
/// single actor hop so a log line cannot interleave values from different
/// moments.
public struct BLELinkDiagnostics: Sendable {
  /// CBCentralManager state name (e.g. "poweredOn", "notActivated").
  public let centralState: String

  /// The state machine's current phase.
  public let phase: BLEPhaseKind

  /// Peripheral connection state name, or nil when no phase owns a peripheral.
  public let peripheralState: String?

  public init(centralState: String, phase: BLEPhaseKind, peripheralState: String?) {
    self.centralState = centralState
    self.phase = phase
    self.peripheralState = peripheralState
  }
}
