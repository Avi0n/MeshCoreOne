import Foundation

extension Parsers {

    // MARK: - CoreStats

    /// Parser for core system statistics.
    enum CoreStats {
        /// Parses battery, uptime, errors, and queue length.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.coreStatsMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "CoreStats too short: \(data.count) < \(PacketSize.coreStatsMinimum)"
                )
            }
            let batteryMV = data.readUInt16LE(at: 0)
            let uptime = data.readUInt32LE(at: 2)
            let errors = data.readUInt16LE(at: 6)
            let queueLen = data[8]

            return .statsCore(MeshCore.CoreStats(
                batteryMV: batteryMV,
                uptimeSeconds: uptime,
                errors: errors,
                queueLength: queueLen
            ))
        }
    }

    // MARK: - RadioStats

    /// Parser for radio performance statistics.
    enum RadioStats {
        /// Parses noise floor, SNR, and radio airtime.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.radioStatsMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "RadioStats too short: \(data.count) < \(PacketSize.radioStatsMinimum)"
                )
            }
            let noiseFloor = data.readInt16LE(at: 0)
            let lastRSSI = Int8(bitPattern: data[2])
            let lastSNR = Double(Int8(bitPattern: data[3])) / 4.0
            let txAir = data.readUInt32LE(at: 4)
            let rxAir = data.readUInt32LE(at: 8)

            return .statsRadio(MeshCore.RadioStats(
                noiseFloor: noiseFloor,
                lastRSSI: lastRSSI,
                lastSNR: lastSNR,
                txAirtimeSeconds: txAir,
                rxAirtimeSeconds: rxAir
            ))
        }
    }

    // MARK: - PacketStats

    /// Parser for packet counters.
    enum PacketStats {
        /// Parses total sent/received, flood/direct packet counts, and receive errors.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.packetStatsMinimum else {
                return .parseFailure(
                    data: data,
                    reason: "PacketStats too short: \(data.count) < \(PacketSize.packetStatsMinimum)"
                )
            }
            let receiveErrors: UInt32 = data.count >= PacketSize.packetStatsWithReceiveErrors
                ? data.readUInt32LE(at: 24) : 0
            return .statsPackets(MeshCore.PacketStats(
                received: data.readUInt32LE(at: 0),
                sent: data.readUInt32LE(at: 4),
                floodTx: data.readUInt32LE(at: 8),
                directTx: data.readUInt32LE(at: 12),
                floodRx: data.readUInt32LE(at: 16),
                directRx: data.readUInt32LE(at: 20),
                receiveErrors: receiveErrors
            ))
        }
    }
}
