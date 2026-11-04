import Foundation

/// Counts transport ingress before protocol parsing, scoped to one installed receive stream.
struct BLEIngressDiagnostics: Sendable, Equatable {
  let deviceID: UUID
  let instanceID: String
  let connectionGeneration: UInt64
  let streamSequence: UInt64
  let binding: String?
  var acceptedFrames = 0
  var droppedFrames = 0
  var rejectedFrames = 0
  var terminatedFrames = 0

  var context: String {
    "device=\(deviceID.uuidString.prefix(8)) instance=\(instanceID) generation=\(connectionGeneration) stream=\(streamSequence) binding=\(binding ?? "none")"
  }
}
