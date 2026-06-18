import Foundation

extension Parsers {

    // MARK: - TraceData

    /// Parser for full trace route results.
    public enum TraceData {
        /// Parses trace route data.
        ///
        /// ### Binary Format
        /// (Per firmware MyMesh.cpp onTraceRecv, v1.11+)
        /// - Offset 0 (1 byte): Reserved
        /// - Offset 1 (1 byte): Path length (total hash bytes, not hop count)
        /// - Offset 2 (1 byte): Flags (bits 0-1: path_sz, determines hash size)
        /// - Offset 3 (4 bytes): Tag (UInt32 LE)
        /// - Offset 7 (4 bytes): Auth code (UInt32 LE)
        /// - Offset 11 (pathLen bytes): Hash bytes
        /// - Offset 11+pathLen (hopCount bytes): SNR bytes (one per hop)
        /// - Offset 11+pathLen+hopCount (1 byte): Final SNR at destination
        ///
        /// path_sz encoding:
        /// - 0: 1-byte hashes (pathLen = hopCount)
        /// - 1: 2-byte hashes (hopCount = pathLen / 2)
        /// - 2: 4-byte hashes (hopCount = pathLen / 4)
        /// - 3: 8-byte hashes (hopCount = pathLen / 8)
        static func parse(_ data: Data) -> MeshEvent {
            // Minimum: reserved(1) + pathLen(1) + flags(1) + tag(4) + authCode(4) = 11 bytes
            guard data.count >= PacketSize.traceDataMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "TraceData too short: \(data.count) bytes, need \(PacketSize.traceDataMinimum)"
                )
            }

            let pathLength = Int(data[1])
            let flags = data[2]
            let pathSz = Int(flags & 0x03)
            let hashSize = 1 << pathSz  // 1, 2, 4, or 8 bytes per hop
            let hopCount = pathLength > 0 ? pathLength / hashSize : 0

            let tag = data.readUInt32LE(at: 3)
            let authCode = data.readUInt32LE(at: 7)

            let hashesStart = 11
            let snrsStart = hashesStart + pathLength
            let finalSnrOffset = snrsStart + hopCount

            // Validate we have enough data
            guard data.count >= finalSnrOffset + 1 else {
                return .parseFailure(
                    data: data,
                    reason: "TraceData too short for path: need \(finalSnrOffset + 1), have \(data.count)"
                )
            }

            var path: [TraceNode] = []

            // Parse each hop
            for i in 0..<hopCount {
                let hashOffset = hashesStart + (i * hashSize)
                let hashBytes = Data(data[hashOffset..<(hashOffset + hashSize)])
                let snrOffset = snrsStart + i
                let snr = Double(Int8(bitPattern: data[snrOffset])) / 4.0

                // Check if all hash bytes are 0xFF (destination marker)
                let isDestination = hashBytes.allSatisfy { $0 == 0xFF }
                path.append(TraceNode(hashBytes: isDestination ? nil : hashBytes, snr: snr))
            }

            // Final SNR at destination
            let finalSnr = Double(Int8(bitPattern: data[finalSnrOffset])) / 4.0
            path.append(TraceNode(hashBytes: nil, snr: finalSnr))

            return .traceData(TraceInfo(
                tag: tag,
                authCode: authCode,
                flags: flags,
                pathLength: UInt8(pathLength),
                path: path
            ))
        }
    }

    // MARK: - RawData

    /// Parser for generic raw packet notifications.
    enum RawData {
        /// Parses raw radio data.
        ///
        /// ### Binary Format
        /// (Per firmware MyMesh.cpp push_raw_data)
        /// - Offset 0 (1 byte): SNR scaled by 4 (Int8)
        /// - Offset 1 (1 byte): RSSI (Int8)
        /// - Offset 2 (1 byte): Reserved (0xFF)
        /// - Offset 3 (N bytes): Payload data
        static func parse(_ data: Data) -> MeshEvent {
            // Minimum: snr(1) + rssi(1) + reserved(1) = 3 bytes
            guard data.count >= PacketSize.rawDataMinimum else {
                return .parseFailure(data: data, reason: "RawData too short: \(data.count) bytes, need \(PacketSize.rawDataMinimum)")
            }

            let snr = Double(Int8(bitPattern: data[0])) / 4.0
            let rssi = Int(Int8(bitPattern: data[1]))
            // Skip reserved byte at offset 2
            let payload = Data(data.dropFirst(3))

            return .rawData(RawDataInfo(snr: snr, rssi: rssi, payload: payload))
        }
    }

    // MARK: - LogData

    /// Parser for remote debug log entries.
    enum LogData {
        /// Parses log messages with optional signal metadata.
        /// Returns rxLogData with parsed RF packet if parsing succeeds,
        /// otherwise returns logData with raw payload.
        static func parse(_ data: Data) -> MeshEvent {
            if data.count >= 2 {
                let snr = Double(Int8(bitPattern: data[0])) / 4.0
                let rssi = Int(Int8(bitPattern: data[1]))
                let payload = Data(data.dropFirst(2))
                if let parsed = RxLogParser.parse(snr: snr, rssi: rssi, payload: payload) {
                    return .rxLogData(parsed)
                }
                return .logData(LogDataInfo(snr: snr, rssi: rssi, payload: payload))
            }
            if let parsed = RxLogParser.parse(snr: nil, rssi: nil, payload: data) {
                return .rxLogData(parsed)
            }
            return .logData(LogDataInfo(snr: nil, rssi: nil, payload: data))
        }
    }
}
