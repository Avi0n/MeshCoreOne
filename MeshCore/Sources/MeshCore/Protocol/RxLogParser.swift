import Foundation

/// Parser for raw RF packets from rxLogData events.
public enum RxLogParser {

    /// Parse raw payload bytes into structured ParsedRxLogData.
    public static func parse(snr: Double?, rssi: Int?, payload: Data) -> ParsedRxLogData? {
        guard !payload.isEmpty else { return nil }

        var offset = 0

        // Parse header byte
        let header = payload[offset]
        offset += 1

        let routeTypeBits = header & 0x03
        let payloadTypeBits = (header >> 2) & 0x0F
        let payloadVersion = (header >> 6) & 0x03

        guard let routeType = RouteType(rawValue: routeTypeBits) else {
            return nil
        }
        let payloadType = PayloadType(fromBits: payloadTypeBits)

        // Parse transport code if present
        var transportCode: Data?
        if routeType.hasTransportCode {
            guard payload.count >= offset + 4 else { return nil }
            transportCode = payload[offset..<offset + 4]
            offset += 4
        }

        // Parse path length (multibyte encoded)
        guard payload.count > offset else { return nil }
        let pathLength = payload[offset]
        offset += 1

        // Decode actual byte length from multibyte encoding.
        // Mode 3 is reserved and decodePathLen returns nil; the firmware rejects
        // the whole packet in that case, so fail the parse rather than mis-slicing
        // the remainder as payload.
        guard let pathByteLen = decodePathLen(pathLength)?.byteLength else {
            return nil
        }

        // Parse path nodes
        var pathNodes: [UInt8] = []
        if pathByteLen > 0 {
            guard payload.count >= offset + pathByteLen else { return nil }
            pathNodes = Array(payload[offset..<offset + pathByteLen])
            offset += pathByteLen
        }

        // Remaining bytes are packet payload
        let packetPayload = payload.count > offset ? Data(payload[offset...]) : Data()

        // Extract dest and src hashes for text messages. The firmware prefixes every
        // text message payload with these two hashes regardless of route type, so flood
        // and direct packets share the same offsets; extracting for both lets the
        // consumer resolve the sender name for flood-routed DMs too.
        // DM payload hashes are always 1 byte per MeshCore spec (PATH_HASH_SIZE = 1)
        var senderPubkeyPrefix: Data?
        var recipientPubkeyPrefix: Data?
        let payloadHashSize = 1
        if payloadType == .textMessage && packetPayload.count >= payloadHashSize * 2 {
            recipientPubkeyPrefix = Data(packetPayload[0..<payloadHashSize])
            senderPubkeyPrefix = Data(packetPayload[payloadHashSize..<payloadHashSize * 2])
        }

        return ParsedRxLogData(
            snr: snr,
            rssi: rssi,
            rawPayload: payload,
            routeType: routeType,
            payloadType: payloadType,
            payloadVersion: payloadVersion,
            payloadTypeBits: payloadTypeBits,
            transportCode: transportCode,
            pathLength: pathLength,
            pathNodes: pathNodes,
            packetPayload: packetPayload,
            senderPubkeyPrefix: senderPubkeyPrefix,
            recipientPubkeyPrefix: recipientPubkeyPrefix
        )
    }
}
