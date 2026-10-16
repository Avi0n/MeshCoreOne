// BLEPhaseKind.swift
import Foundation

/// `BLEPhase` without its CoreBluetooth payload: the projection callers
/// outside the transport actor branch on and log. Raw values match
/// `BLEPhase.name` so log lines read identically to the phase names.
public enum BLEPhaseKind: String, Sendable {
  case idle
  case waitingForBluetooth
  case connecting
  case discoveringServices
  case discoveringCharacteristics
  case subscribingToNotifications
  case discoveryComplete
  case connected
  case autoReconnecting
  case restoringState
  case disconnecting
}
