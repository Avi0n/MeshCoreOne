import Foundation

/// Device error sub-codes carried by a `PACKET_ERROR` frame.
///
/// When a command fails, the firmware replies with `RESP_CODE_ERR` followed by a
/// single error-code byte. These values mirror the `ERR_CODE_*` constants defined
/// in the reference firmware (`examples/companion_radio/MyMesh.cpp`).
///
/// The wire value is preserved as a raw `UInt8?` on ``MeshEvent/error(code:)`` so
/// that sub-codes outside the known range survive round-tripping. Use
/// ``MeshEvent/errorCode`` to obtain the typed value when the byte is one of the
/// known codes.
public enum ErrorCode: UInt8, Sendable {
    /// Unknown or unsupported command byte / sub-command.
    case unsupportedCommand = 1
    /// Target not found (channel, contact, message, etc.).
    case notFound = 2
    /// Internal queue or table is full; retry later.
    case tableFull = 3
    /// Operation not valid in the current device state (e.g. iterator already running).
    case badState = 4
    /// Filesystem or storage I/O failure.
    case fileIOError = 5
    /// Invalid argument (bad length, out-of-range value, reserved field, etc.).
    case illegalArgument = 6
}
