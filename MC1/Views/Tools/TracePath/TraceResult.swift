import Foundation

/// Result of a trace operation
struct TraceResult: Identifiable {
    let id = UUID()
    let hops: [TraceHop]
    let durationMs: Int
    let success: Bool
    let errorMessage: String?
    let tracedPathBytes: [UInt8]  // Path that was actually traced
    let hashSize: Int             // Bytes per hop (1, 2, or 3)

    /// Comma-separated path string for display/copy, chunked by hash size
    var tracedPathString: String {
        let data = Data(tracedPathBytes)
        return stride(from: 0, to: data.count, by: hashSize).map { start in
            let end = min(start + hashSize, data.count)
            return data[start..<end].hexString()
        }.joined(separator: ",")
    }

    static func timeout(attemptedPath: [UInt8], hashSize: Int) -> TraceResult {
        TraceResult(hops: [], durationMs: 0, success: false,
                    errorMessage: L10n.Contacts.Contacts.Trace.Error.noResponse, tracedPathBytes: attemptedPath, hashSize: hashSize)
    }

    static func sendFailed(_ message: String, attemptedPath: [UInt8], hashSize: Int) -> TraceResult {
        TraceResult(hops: [], durationMs: 0, success: false,
                    errorMessage: message, tracedPathBytes: attemptedPath, hashSize: hashSize)
    }
}
