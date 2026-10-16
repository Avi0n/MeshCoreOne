import Foundation
@testable import MeshCore
import Testing

@Suite("ErrorCode typed accessors")
struct MeshEventErrorCodeTests {
  @Test(arguments: [
    (UInt8(1), ErrorCode.unsupportedCommand),
    (UInt8(2), ErrorCode.notFound),
    (UInt8(3), ErrorCode.tableFull),
    (UInt8(4), ErrorCode.badState),
    (UInt8(5), ErrorCode.fileIOError),
    (UInt8(6), ErrorCode.illegalArgument)
  ])
  func `All six firmware sub-codes map to the matching ErrorCode`(raw: UInt8, expected: ErrorCode) {
    #expect(ErrorCode(rawValue: raw) == expected)
    #expect(MeshEvent.error(code: raw).errorCode == expected)
    #expect(MeshCoreError.deviceError(code: raw).deviceErrorCode == expected)
  }

  @Test
  func `Unknown or out-of-range bytes yield a nil typed code but keep the raw value`() {
    let event = MeshEvent.error(code: 0)
    #expect(event.errorCode == nil)
    let event99 = MeshEvent.error(code: 99)
    #expect(event99.errorCode == nil)
    if case let .error(code) = event99 {
      #expect(code == 99)
    } else {
      Issue.record("Expected .error case to preserve the raw byte")
    }
  }

  @Test
  func `A missing sub-code byte yields a nil typed code`() {
    #expect(MeshEvent.error(code: nil).errorCode == nil)
  }

  @Test
  func `Non-error events have no typed error code`() {
    #expect(MeshEvent.ok(value: nil).errorCode == nil)
    #expect(MeshEvent.noMoreMessages.errorCode == nil)
  }

  @Test
  func `PacketParser maps a RESP_CODE_ERR frame to .error with the firmware sub-code`() {
    let frame = Data([ResponseCode.error.rawValue, ErrorCode.illegalArgument.rawValue])
    let event = PacketParser.parse(frame)
    #expect(event.errorCode == .illegalArgument)
  }

  @Test
  func `deviceErrorCode is nil for non-deviceError MeshCoreError cases`() {
    #expect(MeshCoreError.timeout.deviceErrorCode == nil)
    #expect(MeshCoreError.deviceError(code: 7).deviceErrorCode == nil)
  }
}
