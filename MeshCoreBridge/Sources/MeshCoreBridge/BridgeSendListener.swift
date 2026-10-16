import Foundation

/// Sends outbound bytes to the physical radio link.
///
/// Transports live in Kotlin (Kable BLE on Android, TCP on desktop). The bridge
/// adapts MeshCore's `MeshTransport.send` to this callback: MeshCore hands down a
/// complete frame and Kotlin performs the write. `withoutResponse` mirrors
/// MeshCore's acknowledged-vs-unacknowledged write distinction (BLE Write Request
/// vs Write Command); a transport that cannot honor it treats both the same.
public protocol BridgeSendListener: AnyObject, Sendable {
  /// Writes a frame to the link.
  ///
  /// - Parameters:
  ///   - bytes: The complete frame to transmit.
  ///   - withoutResponse: `true` for an unacknowledged write (pipelined), `false`
  ///     for an acknowledged write.
  func sendBytes(_ bytes: [UInt8], withoutResponse: Bool)

  /// Live GATT Write-Command capability. iOS reads the tx characteristic after discovery;
  /// Kotlin reports the same flag so ESP32 does not take the nRF52 pipelined channel-read path.
  var advertisesWriteWithoutResponse: Bool { get }

  /// Whether windowed channel reads are viable. BLE follows
  /// ``advertisesWriteWithoutResponse``; TCP opts in even without a write characteristic.
  var supportsPipelinedReads: Bool { get }
}
