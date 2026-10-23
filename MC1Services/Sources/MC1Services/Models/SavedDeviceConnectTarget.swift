import Foundation

public enum SavedDeviceConnectTarget: Equatable, Sendable {
  case wifi(host: String, port: UInt16)
  case bluetooth(peripheralID: UUID)
}
