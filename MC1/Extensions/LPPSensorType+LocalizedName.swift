import MeshCore

extension LPPSensorType {
  /// Localized display name for telemetry labels.
  /// Distinct from `name`, which is the wire/serialization identifier
  /// (stored in snapshot entries and reverse-looked-up via `init?(name:)`)
  /// and must stay English.
  var localizedName: String {
    switch self {
    case .digitalInput: L10n.RemoteNodes.RemoteNodes.Status.Sensor.digitalInput
    case .digitalOutput: L10n.RemoteNodes.RemoteNodes.Status.Sensor.digitalOutput
    case .analogInput: L10n.RemoteNodes.RemoteNodes.Status.Sensor.analogInput
    case .analogOutput: L10n.RemoteNodes.RemoteNodes.Status.Sensor.analogOutput
    case .genericSensor: L10n.RemoteNodes.RemoteNodes.Status.Sensor.genericSensor
    case .illuminance: L10n.RemoteNodes.RemoteNodes.Status.Sensor.illuminance
    case .presence: L10n.RemoteNodes.RemoteNodes.Status.Sensor.presence
    case .temperature: L10n.RemoteNodes.RemoteNodes.Status.Sensor.temperature
    case .humidity: L10n.RemoteNodes.RemoteNodes.Status.Sensor.humidity
    case .accelerometer: L10n.RemoteNodes.RemoteNodes.Status.Sensor.accelerometer
    case .barometer: L10n.RemoteNodes.RemoteNodes.Status.Sensor.barometer
    case .voltage: L10n.RemoteNodes.RemoteNodes.Status.Sensor.voltage
    case .current: L10n.RemoteNodes.RemoteNodes.Status.Sensor.current
    case .frequency: L10n.RemoteNodes.RemoteNodes.Status.Sensor.frequency
    case .percentage: L10n.RemoteNodes.RemoteNodes.Status.Sensor.percentage
    case .altitude: L10n.RemoteNodes.RemoteNodes.Status.Sensor.altitude
    case .load: L10n.RemoteNodes.RemoteNodes.Status.Sensor.load
    case .concentration: L10n.RemoteNodes.RemoteNodes.Status.Sensor.concentration
    case .power: L10n.RemoteNodes.RemoteNodes.Status.Sensor.power
    case .distance: L10n.RemoteNodes.RemoteNodes.Status.Sensor.distance
    case .energy: L10n.RemoteNodes.RemoteNodes.Status.Sensor.energy
    case .direction: L10n.RemoteNodes.RemoteNodes.Status.Sensor.direction
    case .unixTime: L10n.RemoteNodes.RemoteNodes.Status.Sensor.unixTime
    case .gyrometer: L10n.RemoteNodes.RemoteNodes.Status.Sensor.gyrometer
    case .colour: L10n.RemoteNodes.RemoteNodes.Status.Sensor.colour
    case .gps: L10n.RemoteNodes.RemoteNodes.Status.Sensor.gps
    case .switchValue: L10n.RemoteNodes.RemoteNodes.Status.Sensor.switchValue
    }
  }
}
