import Compression
import Foundation

/// Block size pulled from the `InputFilter` per iteration during streaming zlib
/// decompression. 64 KiB keeps per-call overhead low while letting the cap check
/// interrupt runaway expansion quickly.
private let zlibDecompressReadChunkSize = 64 * 1024

public extension Data {
    /// Converts data to uppercase hex string with optional separator between bytes
    /// - Parameter separator: String to insert between each byte (default: none)
    /// - Returns: Hex string representation
    func hexString(separator: String = "") -> String {
        map { String(format: "%02X", $0) }.joined(separator: separator)
    }

    /// Initialize Data from a hex string
    /// - Parameter hexString: Hex string (e.g., "AABBCC" or "AA BB CC")
    init?(hexString: String) {
        let hex = hexString.filter { $0.isHexDigit }.uppercased()
        guard hex.count % 2 == 0 else { return nil }

        var data = Data()
        var index = hex.startIndex

        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }

        self = data
    }

    /// Lowercase hex string representation (no separator)
    var hex: String {
        map { String(format: "%02x", $0) }.joined()
    }

    /// Convert first 4 bytes to UInt32 ACK code (little-endian)
    /// Returns 0 if data has fewer than 4 bytes
    var ackCodeUInt32: UInt32 {
        guard count >= 4 else { return 0 }
        return prefix(4).withUnsafeBytes {
            $0.load(as: UInt32.self).littleEndian
        }
    }

    /// zlib-compress this data. Wraps Foundation's NSData bridge.
    func zlibCompressed() throws -> Data {
        try (self as NSData).compressed(using: .zlib) as Data
    }

    /// Stream-decompress a zlib payload, aborting once the output crosses
    /// `maxUncompressedBytes`. Throws `AppBackupError.decompressedTooLarge`
    /// on cap overflow so callers can surface a specific user-facing reason
    /// instead of a generic invalid-file error.
    func zlibDecompressed(maxUncompressedBytes: Int) throws -> Data {
        var offset = 0
        let source = self
        let inputFilter = try InputFilter(.decompress, using: .zlib) { length -> Data? in
            let remaining = source.count - offset
            guard remaining > 0 else { return nil }
            let take = Swift.min(length, remaining)
            let chunk = source.subdata(in: offset..<(offset + take))
            offset += take
            return chunk
        }

        var output = Data()
        while let chunk = try inputFilter.readData(ofLength: zlibDecompressReadChunkSize), !chunk.isEmpty {
            if output.count + chunk.count > maxUncompressedBytes {
                throw AppBackupError.decompressedTooLarge(maxBytes: maxUncompressedBytes)
            }
            output.append(chunk)
        }
        return output
    }
}
