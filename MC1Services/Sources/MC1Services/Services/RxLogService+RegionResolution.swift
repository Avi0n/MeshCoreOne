import Foundation
import MeshCore
import OSLog

private let logger = PersistentLogger(subsystem: "com.mc1", category: "RxLogService.Region")

extension RxLogService {
  private static let missLogThrottleSeconds: TimeInterval = 60
  private static let ambiguousLogThrottleSeconds: TimeInterval = 60

  /// Offset of the unencrypted sender prefix byte in a DM `packetPayload`,
  /// matching `findRxLogEntryBySenderPrefix`'s correlation key.
  private static let dmSenderPrefixByteOffset = 1

  /// Bound reprocess scan to the full retention window so rows that exist
  /// between prune passes are not skipped. `transportCode` is unindexed;
  /// fetch uses indexed `radioID` + `receivedAt`.
  static let regionReprocessFetchLimit =
    RxLogRetention.keepCount + RxLogRetention.pruneThreshold

  /// Yield every N entries so live `process` can interleave during multi-match
  /// HMAC work (O(R) per entry).
  private static let reprocessYieldInterval = 32

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

  /// Resolve `transport_codes[0]` into dual storage fields.
  /// Live cost is O(R) HMACs per transport-coded packet; cache rebuilds only
  /// when known regions change.
  func resolveRegion(for parsed: ParsedRxLogData) -> (regionScope: String?, regionScopeMatches: [String]) {
    resolveRegionStorage(
      transportCode: parsed.transportCode,
      payloadTypeBits: parsed.payloadTypeBits,
      payload: parsed.packetPayload
    )
  }

  /// Update known regions, rebuild the scope-key cache, and reprocess.
  /// Runs even when the new list is empty so sticky labels can clear.
  public func updateKnownRegions(_ regions: [String]) async {
    guard knownRegions != regions else { return }
    knownRegions = regions
    scopeKeyCache = Self.buildScopeKeyCache(from: regions)
    await reprocessRegionEntries()
  }

  /// Replace the scope-key cache and reprocess. Lets tests inject two names
  /// that share one key without needing a real 16-bit code collision.
  func replaceScopeKeyCacheAndReprocess(
    _ cache: [(name: String, key: Data)]
  ) async {
    knownRegions = cache.map(\.name)
    scopeKeyCache = cache
    await reprocessRegionEntries()
  }

  /// Re-resolve retained transport-coded entries against the current cache and
  /// write dual region fields on `RxLogEntry` and correlated `Message` rows.
  ///
  /// Concurrent callers set dirty and park on a continuation the owner resumes
  /// when the drain finishes. Overlapping callers converge on the final cache;
  /// the number of passes is bounded by the number of overlapping callers that
  /// arrive across pass boundaries.
  func reprocessRegionEntries() async {
    guard !Task.isCancelled else { return }

    regionReprocessDirty = true
    if isReprocessingRegions {
      await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        regionReprocessWaiters.append(continuation)
      }
      guard !Task.isCancelled else { return }
      // Owner finished between our dirty set and its last while-check.
      // Re-enter so the mark is not lost.
      if regionReprocessDirty {
        await reprocessRegionEntries()
      }
      return
    }

    isReprocessingRegions = true
    defer {
      isReprocessingRegions = false
      let waiters = regionReprocessWaiters
      regionReprocessWaiters.removeAll()
      for waiter in waiters {
        waiter.resume()
      }
    }

    guard let radioID else {
      regionReprocessDirty = false
      return
    }

    while regionReprocessDirty {
      guard !Task.isCancelled else { return }
      regionReprocessDirty = false
      await runReprocessPass(radioID: radioID)
    }
  }

  private func runReprocessPass(radioID: UUID) async {
    do {
      let entries = try await dataStore.fetchEntriesWithTransportCode(
        radioID: radioID,
        limit: Self.regionReprocessFetchLimit
      )

      guard !entries.isEmpty else { return }
      logger.info("Re-processing \(entries.count) transport-coded entries for region resolution")

      // Snapshot so a mid-pass known-regions change is applied on the dirty re-run.
      let cacheSnapshot = scopeKeyCache

      var rxUpdates: [(id: UUID, regionScope: String?, regionScopeMatches: [String])] = []
      var channelMessageUpdates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?, regionScopeMatches: [String])] = []
      var dmMessageUpdates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?, regionScopeMatches: [String])] = []

      for (index, entry) in entries.enumerated() {
        guard !Task.isCancelled else { break }

        if index > 0, index.isMultiple(of: Self.reprocessYieldInterval) {
          await Task.yield()
        }

        let resolved = resolveRegionStorage(
          transportCode: entry.transportCode,
          payloadTypeBits: entry.payloadTypeBits,
          payload: entry.packetPayload,
          cache: cacheSnapshot,
          logMisses: false
        )

        let scopeChanged = entry.regionScope != resolved.regionScope
        let matchesChanged = entry.regionScopeMatches != resolved.regionScopeMatches
        guard scopeChanged || matchesChanged else { continue }

        rxUpdates.append((
          id: entry.id,
          regionScope: resolved.regionScope,
          regionScopeMatches: resolved.regionScopeMatches
        ))

        guard let senderTimestamp = entry.senderTimestamp else { continue }
        if let channelIndex = entry.channelIndex {
          channelMessageUpdates.append((
            channelIndex: channelIndex,
            senderTimestamp: senderTimestamp,
            regionScope: resolved.regionScope,
            regionScopeMatches: resolved.regionScopeMatches
          ))
        } else if entry.packetPayload.count >= Self.dmSenderPrefixByteOffset + 1 {
          let prefixByte = entry.packetPayload[Self.dmSenderPrefixByteOffset]
          dmMessageUpdates.append((
            senderPrefixByte: prefixByte,
            senderTimestamp: senderTimestamp,
            regionScope: resolved.regionScope,
            regionScopeMatches: resolved.regionScopeMatches
          ))
        }
      }

      if !rxUpdates.isEmpty {
        try await dataStore.batchUpdateRxLogRegion(updates: rxUpdates)
      }

      var touchedMessageIDs = Set<UUID>()
      if !channelMessageUpdates.isEmpty {
        let ids = try await dataStore.batchUpdateChannelMessageRegion(
          radioID: radioID,
          updates: channelMessageUpdates
        )
        touchedMessageIDs.formUnion(ids)
      }
      if !dmMessageUpdates.isEmpty {
        let ids = try await dataStore.batchUpdateDMMessageRegion(
          radioID: radioID,
          updates: dmMessageUpdates
        )
        touchedMessageIDs.formUnion(ids)
      }

      if !touchedMessageIDs.isEmpty {
        regionUpdateBroadcaster.yield(Array(touchedMessageIDs))
      }

      let messageCount = channelMessageUpdates.count + dmMessageUpdates.count
      if !rxUpdates.isEmpty || messageCount > 0 {
        logger.info(
          "Region reprocess wrote \(rxUpdates.count) RxLog entries, \(messageCount) message correlations (\(touchedMessageIDs.count) message IDs)"
        )
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
      logger.debug("Region resolution miss against \(knownRegions.count) known regions")
    }
  }

  private func logRegionAmbiguousThrottled(names: [String]) {
    let now = Date()
    if let last = lastRegionAmbiguousLogTime, now.timeIntervalSince(last) < Self.ambiguousLogThrottleSeconds {
      return
    }
    lastRegionAmbiguousLogTime = now
    // Public region names only; never scopeKey bytes, payload, or raw codes.
    logger.debug(
      "Region resolution ambiguous: \(names.count) matches \(names.joined(separator: ", "))"
    )
  }

  private func resolveRegionStorage(
    transportCode: Data?,
    payloadTypeBits: UInt8,
    payload: Data,
    cache: [(name: String, key: Data)]? = nil,
    logMisses: Bool = true
  ) -> (regionScope: String?, regionScopeMatches: [String]) {
    guard let transportCode, transportCode.count >= 2 else {
      return (nil, [])
    }
    let activeCache = cache ?? scopeKeyCache
    // Empty cache skips HMACs but still projects none so writers can clear labels.
    guard !activeCache.isEmpty else {
      return (nil, [])
    }
    let code0 = transportCode.readUInt16LE(at: 0)
    let match = TransportCodeRegionResolver.matchRegions(
      scopeKeys: activeCache,
      expectedTransportCode0: code0,
      payloadTypeBits: payloadTypeBits,
      payload: payload
    )
    if logMisses {
      switch match {
      case .none:
        logRegionMissThrottled()
      case let .ambiguous(names):
        logRegionAmbiguousThrottled(names: names)
      case .unique:
        break
      }
    }
    return RegionScopeSemantics.storageFields(from: match)
  }
}
