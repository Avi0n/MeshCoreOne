import Foundation
import Testing
@testable import MeshCore

@Suite("ErrorCode typed accessors")
struct MeshEventErrorCodeTests {
    @Test("All six firmware sub-codes map to the matching ErrorCode", arguments: [
        (UInt8(1), ErrorCode.unsupportedCommand),
        (UInt8(2), ErrorCode.notFound),
        (UInt8(3), ErrorCode.tableFull),
        (UInt8(4), ErrorCode.badState),
        (UInt8(5), ErrorCode.fileIOError),
        (UInt8(6), ErrorCode.illegalArgument)
    ])
    func errorCodeMapsKnownBytes(raw: UInt8, expected: ErrorCode) {
        #expect(ErrorCode(rawValue: raw) == expected)
        #expect(MeshEvent.error(code: raw).errorCode == expected)
        #expect(MeshCoreError.deviceError(code: raw).deviceErrorCode == expected)
    }

    @Test("Unknown or out-of-range bytes yield a nil typed code but keep the raw value")
    func unknownByteIsNil() {
        let event = MeshEvent.error(code: 0)
        #expect(event.errorCode == nil)
        let event99 = MeshEvent.error(code: 99)
        #expect(event99.errorCode == nil)
        if case .error(let code) = event99 {
            #expect(code == 99)
        } else {
            Issue.record("Expected .error case to preserve the raw byte")
        }
    }

    @Test("A missing sub-code byte yields a nil typed code")
    func missingByteIsNil() {
        #expect(MeshEvent.error(code: nil).errorCode == nil)
    }

    @Test("Non-error events have no typed error code")
    func nonErrorEventIsNil() {
        #expect(MeshEvent.ok(value: nil).errorCode == nil)
        #expect(MeshEvent.noMoreMessages.errorCode == nil)
    }

    @Test("PacketParser maps a RESP_CODE_ERR frame to .error with the firmware sub-code")
    func parserMapsErrorFrame() {
        let frame = Data([ResponseCode.error.rawValue, ErrorCode.illegalArgument.rawValue])
        let event = PacketParser.parse(frame)
        #expect(event.errorCode == .illegalArgument)
    }

    @Test("deviceErrorCode is nil for non-deviceError MeshCoreError cases")
    func deviceErrorCodeNilForOtherCases() {
        #expect(MeshCoreError.timeout.deviceErrorCode == nil)
        #expect(MeshCoreError.deviceError(code: 7).deviceErrorCode == nil)
    }
}
