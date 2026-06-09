import Foundation
@testable import MC1Services

extension SavedTracePathDTO {

    /// Creates a SavedTracePathDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let path = SavedTracePathDTO.testPath(radioID: myRadioID)
    /// ```
    static func testPath(
        id: UUID = UUID(),
        radioID: UUID,
        name: String = "Test Path",
        pathBytes: Data = Data([0x31, 0xA7]),
        hashSize: Int = 1,
        createdDate: Date = Date(),
        runs: [TracePathRunDTO] = []
    ) -> SavedTracePathDTO {
        SavedTracePathDTO(
            id: id,
            radioID: radioID,
            name: name,
            pathBytes: pathBytes,
            hashSize: hashSize,
            createdDate: createdDate,
            runs: runs
        )
    }
}

extension TracePathRunDTO {

    /// Creates a TracePathRunDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let run = TracePathRunDTO.testRun()
    /// ```
    static func testRun(
        id: UUID = UUID(),
        date: Date = Date(),
        success: Bool = true,
        roundTripMs: Int = 250,
        hopsSNR: [Double] = [8.5, 7.0]
    ) -> TracePathRunDTO {
        TracePathRunDTO(
            id: id,
            date: date,
            success: success,
            roundTripMs: roundTripMs,
            hopsSNR: hopsSNR
        )
    }
}
