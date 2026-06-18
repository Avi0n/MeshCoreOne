import Foundation

// MARK: - Data Extensions for Binary Reading

/// Provides utilities for reading binary protocol data and hex string conversions.
extension Data {
    /// Hex digits lookup table (static to avoid repeated allocation)
    private static let hexDigits = Array("0123456789abcdef".utf8)

    /// Computes the optimized hex string representation of the data.
    public var hexString: String {
        var chars = [UInt8](repeating: 0, count: count * 2)
        for (i, byte) in enumerated() {
            chars[i * 2] = Self.hexDigits[Int(byte >> 4)]
            chars[i * 2 + 1] = Self.hexDigits[Int(byte & 0x0F)]
        }
        return String(decoding: chars, as: UTF8.self)
    }

    /// Initializes a new `Data` instance from a hex string.
    ///
    /// - Parameter hexString: The hex string to parse.
    /// - Returns: A `Data` object if the string is valid hex; otherwise, `nil`.
    public init?(hexString: String) {
        let len = hexString.count / 2
        var data = Data(capacity: len)
        var index = hexString.startIndex
        for _ in 0..<len {
            let nextIndex = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }
        self = data
    }

    /// Reads a little-endian `UInt32` from the specified offset.
    ///
    /// - Parameter offset: The index to start reading from.
    /// - Returns: The parsed `UInt32` value, or 0 if the offset is out of bounds.
    public func readUInt32LE(at offset: Int) -> UInt32 {
        guard offset + 4 <= count else { return 0 }
        return self.dropFirst(offset).withUnsafeBytes {
            UInt32(littleEndian: $0.loadUnaligned(as: UInt32.self))
        }
    }

    /// Reads a little-endian `Int32` from the specified offset.
    ///
    /// - Parameter offset: The index to start reading from.
    /// - Returns: The parsed `Int32` value, or 0 if the offset is out of bounds.
    public func readInt32LE(at offset: Int) -> Int32 {
        guard offset + 4 <= count else { return 0 }
        return self.dropFirst(offset).withUnsafeBytes {
            Int32(littleEndian: $0.loadUnaligned(as: Int32.self))
        }
    }

    /// Reads a little-endian `UInt16` from the specified offset.
    ///
    /// - Parameter offset: The index to start reading from.
    /// - Returns: The parsed `UInt16` value, or 0 if the offset is out of bounds.
    public func readUInt16LE(at offset: Int) -> UInt16 {
        guard offset + 2 <= count else { return 0 }
        return self.dropFirst(offset).withUnsafeBytes {
            UInt16(littleEndian: $0.loadUnaligned(as: UInt16.self))
        }
    }

    /// Reads a little-endian `Int16` from the specified offset.
    ///
    /// - Parameter offset: The index to start reading from.
    /// - Returns: The parsed `Int16` value, or 0 if the offset is out of bounds.
    public func readInt16LE(at offset: Int) -> Int16 {
        guard offset + 2 <= count else { return 0 }
        return self.dropFirst(offset).withUnsafeBytes {
            Int16(littleEndian: $0.loadUnaligned(as: Int16.self))
        }
    }

    /// Reads a little-endian `UInt32` from the specified offset.
    ///
    /// This is an alias for ``readUInt32LE(at:)``.
    public func readUInt32(at offset: Int) -> UInt32 { readUInt32LE(at: offset) }
    /// Reads a little-endian `Int32` from the specified offset.
    ///
    /// This is an alias for ``readInt32LE(at:)``.
    public func readInt32(at offset: Int) -> Int32 { readInt32LE(at: offset) }
    /// Reads a little-endian `UInt16` from the specified offset.
    ///
    /// This is an alias for ``readUInt16LE(at:)``.
    public func readUInt16(at offset: Int) -> UInt16 { readUInt16LE(at: offset) }
    /// Reads a little-endian `Int16` from the specified offset.
    ///
    /// This is an alias for ``readInt16LE(at:)``.
    public func readInt16(at offset: Int) -> Int16 { readInt16LE(at: offset) }
}

// MARK: - SNR Helper

extension Int8 {
    /// Converts the raw SNR byte to a floating-point value.
    ///
    /// The MeshCore protocol encodes SNR as a signed byte where the value is SNR * 4.
    public var snrValue: Double {
        Double(self) / 4.0
    }
}

extension UInt8 {
    /// Converts the raw SNR byte (interpreted as signed) to a floating-point value.
    ///
    /// The MeshCore protocol encodes SNR as a signed byte where the value is SNR * 4.
    public var snrValue: Double {
        Int8(bitPattern: self).snrValue
    }
}

// MARK: - Data Padding and Writing

extension Data {
    /// Returns data padded with zeros or truncated to the specified length.
    ///
    /// - Parameter length: The target length.
    /// - Returns: Data of exactly the specified length, or empty data if length is negative.
    public func paddedOrTruncated(to length: Int) -> Data {
        guard length >= 0 else { return Data() }
        if count >= length {
            return Data(prefix(length))
        }
        return self + Data(repeating: 0, count: length - count)
    }

    /// Appends a little-endian UInt32 to the data.
    ///
    /// - Parameter value: The value to append.
    public mutating func appendLittleEndian(_ value: UInt32) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }

    /// Appends a little-endian Int32 to the data.
    ///
    /// - Parameter value: The value to append.
    public mutating func appendLittleEndian(_ value: Int32) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }

    /// Decodes the longest UTF-8-valid prefix of these bytes as a `String`.
    ///
    /// Firmware name fields are fixed C-strings truncated byte-wise, so a field can end
    /// mid-codepoint; this drops the trailing partial sequence rather than fail the whole
    /// name. Decode-side counterpart to ``String.utf8Prefix(maxBytes:)``.
    public func decodingLongestValidUTF8Prefix() -> String {
        if let valid = String(data: self, encoding: .utf8) {
            return valid
        }
        // Truncation only ever damages the tail, so walk back to the last clean boundary.
        for length in stride(from: count - 1, through: 1, by: -1) {
            if let valid = String(data: prefix(length), encoding: .utf8) {
                return valid
            }
        }
        return ""
    }
}

extension String {
    /// Returns the longest prefix of the string whose UTF-8 encoding fits within `maxBytes`.
    ///
    /// Never splits multi-byte characters. Returns the empty string when `maxBytes` is zero or negative.
    ///
    /// - Parameter maxBytes: Maximum number of UTF-8 bytes allowed.
    /// - Returns: A substring (as `String`) whose UTF-8 byte count is at most `maxBytes`.
    public func utf8Prefix(maxBytes: Int) -> String {
        guard maxBytes > 0 else { return "" }

        var byteCount = 0
        var endIndex = startIndex
        for character in self {
            let charBytes = character.utf8.count
            if byteCount + charBytes > maxBytes { break }
            byteCount += charBytes
            endIndex = index(after: endIndex)
        }
        return String(self[startIndex..<endIndex])
    }

    /// Returns the UTF-8 bytes of the string, padded or truncated to the specified length.
    ///
    /// Truncation is UTF-8-safe: multi-byte characters are never split.
    ///
    /// - Parameter length: The target length in bytes.
    /// - Returns: Data of exactly the specified length.
    public func utf8PaddedOrTruncated(to length: Int) -> Data {
        let truncated = utf8Prefix(maxBytes: length)
        return Data(truncated.utf8).paddedOrTruncated(to: length)
    }
}
