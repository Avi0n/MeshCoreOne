import Foundation
import MeshCore
import OSLog

private let logger = PersistentLogger(subsystem: "com.mc1.services", category: "RxLogService.Region")

extension RxLogService {

    private static let reprocessLookbackSeconds: TimeInterval = -60
    private static let missLogThrottleSeconds: TimeInterval = 60

    /// Offset of the unencrypted sender prefix byte in a DM `packetPayload`,
    /// matching `findRxLogEntryBySenderPrefix`'s correlation key.
    private static let dmSenderPrefixByteOffset = 1

    /// Build a `[(name, scopeKey)]` array from the supplied region names.
    /// Skips names that `TransportCodeRegionResolver.deriveScopeKey` rejects
    /// (`$`-prefixed and empty/whitespace names).
    static func buildScopeKeyCache(from regions: [String]) -> [(name: String, key: Data)] {
        regions.compactMap { name in
            guard let key = TransportCodeRegionResolver.deriveScopeKey(regionName: name) else {
                return nil
            }
            return (name: name, key: key)
        }
    }

    /// Resolve `transport_codes[0]` from a parsed RX packet to a known region
    /// name. Returns nil for packets without a transport code, when the
    /// scope-key cache is empty, or when no region matches.
    func resolveRegionScope(for parsed: ParsedRxLogData) -> String? {
        guard let transportCode = parsed.transportCode, transportCode.count >= 2 else {
            return nil
        }
        let match = findRegionScope(
            transportCode: transportCode,
            payloadTypeBits: parsed.payloadTypeBits,
            payload: parsed.packetPayload
        )
        if match == nil {
            logRegionMissThrottled()
        }
        return match
    }

    /// Update the known-regions list and rebuild the scope-key cache. Also
    /// triggers back-fill of recent entries that arrived before the regions
    /// were known. Wired from `ConnectionManager+Pairing` on
    /// `addKnownRegion` / `removeKnownRegion`.
    public func updateKnownRegions(_ regions: [String]) async {
        guard knownRegions != regions else { return }
        knownRegions = regions
        scopeKeyCache = Self.buildScopeKeyCache(from: regions)
        if !regions.isEmpty {
            await reprocessNoRegionEntries()
        }
    }

    /// Re-resolve recent entries with non-nil `transportCode` and nil
    /// `regionScope` against the current scope-key cache, and back-fill the
    /// resolved region onto both `RxLogEntry` rows and any correlated
    /// `Message` rows (keyed by `(channelIndex, senderTimestamp)`).
    ///
    /// This is the explicit mitigation for two races:
    ///   1. Discovery-time race: regions discover seconds after first
    ///      connection while messages are already arriving.
    ///   2. `addKnownRegion` / `removeKnownRegion` suspension-window race:
    ///      packets that arrive between the synchronous `connectedDevice`
    ///      mutation and the dispatched `updateKnownRegions(_:)` call resolve
    ///      against the stale cache.
    func reprocessNoRegionEntries() async {
        if isReprocessingRegions {
            regionReprocessDirty = true
            return
        }
        isReprocessingRegions = true
        defer { isReprocessingRegions = false }

        guard let radioID else { return }

        repeat {
            regionReprocessDirty = false
            await runReprocessPass(radioID: radioID)
        } while regionReprocessDirty
    }

    private func runReprocessPass(radioID: UUID) async {
        let cutoff = Date().addingTimeInterval(Self.reprocessLookbackSeconds)

        do {
            let entries = try await dataStore.fetchRecentEntriesWithMissingRegion(
                radioID: radioID,
                since: cutoff
            )

            guard !entries.isEmpty else { return }
            logger.info("Re-processing \(entries.count) entries for region back-fill")

            var rxUpdates: [(id: UUID, regionScope: String?)] = []
            var channelMessageUpdates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)] = []
            var dmMessageUpdates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)] = []

            for entry in entries {
                guard !Task.isCancelled else { break }
                guard let resolved = resolveRegionScope(for: entry) else { continue }

                rxUpdates.append((id: entry.id, regionScope: resolved))

                guard let senderTimestamp = entry.senderTimestamp else { continue }
                if let channelIndex = entry.channelIndex {
                    channelMessageUpdates.append((
                        channelIndex: channelIndex,
                        senderTimestamp: senderTimestamp,
                        regionScope: resolved
                    ))
                } else if entry.packetPayload.count >= Self.dmSenderPrefixByteOffset + 1 {
                    let prefixByte = entry.packetPayload[Self.dmSenderPrefixByteOffset]
                    dmMessageUpdates.append((
                        senderPrefixByte: prefixByte,
                        senderTimestamp: senderTimestamp,
                        regionScope: resolved
                    ))
                }
            }

            if !rxUpdates.isEmpty {
                try await dataStore.batchUpdateRxLogRegion(updates: rxUpdates)
            }
            if !channelMessageUpdates.isEmpty {
                try await dataStore.batchUpdateChannelMessageRegion(radioID: radioID, updates: channelMessageUpdates)
            }
            if !dmMessageUpdates.isEmpty {
                try await dataStore.batchUpdateDMMessageRegion(radioID: radioID, updates: dmMessageUpdates)
            }

            let messageCount = channelMessageUpdates.count + dmMessageUpdates.count
            if !rxUpdates.isEmpty || messageCount > 0 {
                logger.info("Back-filled \(rxUpdates.count) RxLog entries, \(messageCount) messages")
            }
        } catch {
            logger.error("Failed to re-process region entries: \(error.localizedDescription)")
        }
    }

    private func logRegionMissThrottled() {
        let now = Date()
        if let last = lastRegionMissLogTime, now.timeIntervalSince(last) < Self.missLogThrottleSeconds {
            return
        }
        lastRegionMissLogTime = now
        if knownRegions.isEmpty {
            logger.debug("Region resolution skipped: no known regions loaded")
        } else {
            logger.debug("Region resolution miss against \(self.knownRegions.count) known regions")
        }
    }

    /// Resolve region scope against an existing `RxLogEntryDTO` (used by the
    /// back-fill path, which has the persisted entry in hand rather than a
    /// fresh `ParsedRxLogData`).
    private func resolveRegionScope(for entry: RxLogEntryDTO) -> String? {
        findRegionScope(
            transportCode: entry.transportCode,
            payloadTypeBits: entry.payloadTypeBits,
            payload: entry.packetPayload
        )
    }

    private func findRegionScope(transportCode: Data?, payloadTypeBits: UInt8, payload: Data) -> String? {
        guard let transportCode, transportCode.count >= 2 else { return nil }
        guard !scopeKeyCache.isEmpty else { return nil }
        let code0 = transportCode.readUInt16LE(at: 0)
        return TransportCodeRegionResolver.findMatchingRegion(
            scopeKeys: scopeKeyCache,
            expectedTransportCode0: code0,
            payloadTypeBits: payloadTypeBits,
            payload: payload
        )
    }

}
