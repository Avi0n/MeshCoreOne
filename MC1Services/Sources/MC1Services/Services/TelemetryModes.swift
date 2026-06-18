import Foundation

/// Packed telemetry mode configuration
public struct TelemetryModes: Sendable, Equatable {
    public var base: UInt8
    public var location: UInt8
    public var environment: UInt8

    public init(base: UInt8 = 0, location: UInt8 = 0, environment: UInt8 = 0) {
        self.base = base & 0b11
        self.location = location & 0b11
        self.environment = environment & 0b11
    }

    /// Packed value for protocol encoding
    public var packed: UInt8 {
        (environment << 4) | (location << 2) | base
    }

    public init(packed: UInt8) {
        self.base = packed & 0b11
        self.location = (packed >> 2) & 0b11
        self.environment = (packed >> 4) & 0b11
    }
}
