import Foundation

/// Receives the outcome of a command issued through `MeshCoreBridge`.
///
/// Every command entry point is fire-and-forget: it returns immediately and the
/// outcome arrives later on exactly one of these methods, keyed by the caller's
/// `requestID`. The Kotlin side completes a future/continuation keyed by the same
/// id. Methods may be invoked on any thread.
///
/// The signature is deliberately primitive-only (Int64, Int32, bytes, String) so
/// a JNI export can consume it. `resultBytes` is the versioned JSON envelope
/// documented in `ENCODING.md`.
public protocol BridgeResultListener: AnyObject, Sendable {
  /// The command succeeded. `resultBytes` is the encoded result envelope.
  func onSuccess(requestID: Int64, resultBytes: [UInt8])

  /// The command failed. `errorCode` is a stable `BridgeErrorCode` raw value;
  /// `message` is a human-readable diagnostic.
  func onFailure(requestID: Int64, errorCode: Int32, message: String)
}
