import Foundation

/// Result a channel-pipeline task group task contributes: the consumer's collected channels,
/// or a watchdog tick that has already finished the subscription.
private enum ChannelPipelineOutcome: Sendable {
    case collected([UInt8: ChannelInfo])
    case watchdog
}

extension MeshCoreSession {

    // MARK: - Channel Commands

    /// Retrieves configuration for a channel.
    ///
    /// - Parameter index: Channel index (0-255).
    /// - Returns: Channel information including name and secret.
    /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit configuration for the requested channel.
    public func getChannel(index: UInt8) async throws -> ChannelInfo {
        try await sendAndWait(PacketBuilder.getChannel(index: index)) { event in
            if case .channelInfo(let info) = event, info.index == index { return info }
            return nil
        }
    }

    /// Reads multiple channels in a single bounded-window pipeline of unacknowledged requests,
    /// so the per-request stall — BLE slave latency, or the WiFi per-round-trip TCP delay — is
    /// amortized across the window instead of paid once per index.
    ///
    /// - Returns: `received` are the channels that answered (sorted by index); `missing` are
    ///   the requested indexes that went unanswered — a dropped BLE Write Command, or a TCP send
    ///   the radio never replied to. The caller reconciles `missing` with serial acknowledged
    ///   reads — an unanswered request is data, not a fatal error.
    /// - Throws: ``MeshCoreError`` only on a hard send failure (e.g. disconnect mid-send); an
    ///   idle stall returns the partial set rather than throwing.
    ///
    /// Falls back to serial ``getChannel(index:)`` reads when the transport does not support
    /// pipelined reads, so a radio limited to acknowledged serial reads still works.
    public func getChannels(indices: [UInt8]) async throws -> (received: [ChannelInfo], missing: [UInt8]) {
        guard !indices.isEmpty else { return (received: [], missing: []) }

        guard await transport.supportsPipelinedReads else {
            var received: [ChannelInfo] = []
            for index in indices {
                received.append(try await getChannel(index: index))
            }
            return (received: received, missing: [])
        }

        // One serializer slot for the whole exchange: late/orphaned channel frames are drained
        // under the held slot instead of leaking to the next command, even if the caller is
        // cancelled mid-drain.
        return try await requestResponseSerializer.withSerialization { [self] in
            try await runChannelReadPipeline(indices: indices)
        }
    }

    private func runChannelReadPipeline(
        indices: [UInt8]
    ) async throws -> (received: [ChannelInfo], missing: [UInt8]) {
        let requested = indices
        let requestedSet = Set(indices)
        let window = max(1, configuration.channelPipelineWindow)
        let idleTimeout = configuration.channelPipelineIdleTimeout
        let hardTimeout = configuration.channelPipelineHardTimeout
        let graceTimeout = configuration.channelPipelinePostDrainGrace
        let sessionClock = clock
        let sessionTransport = transport
        let sessionDispatcher = dispatcher

        let (subscriptionID, events) = await dispatcher.subscribeTracked()

        do {
            // Prime the window before draining so the peripheral's send queue stays non-empty
            // and it does not re-enter slave latency between responses.
            //
            // The nRF52 Nordic UART Service does not preserve ATT-write framing: its receive
            // path reads every byte currently available into one firmware frame, so adjacent
            // Write Commands delivered in the same connection event are coalesced and only the
            // first index in the blob is answered. One write is therefore not a guaranteed
            // response — coalesced-away indexes surface in `missing` and the caller reconciles
            // them with acknowledged reads. That reconcile path is load-bearing, not just a
            // disconnect fallback.
            let primeCount = min(window, requested.count)
            for index in requested.prefix(primeCount) {
                try await sessionTransport.sendWithoutResponse(PacketBuilder.getChannel(index: index))
            }

            let progressTracker = StreamProgressTracker()

            let received: [UInt8: ChannelInfo] = try await withThrowingTaskGroup(
                of: ChannelPipelineOutcome.self
            ) { group in
                // Consumer: records matching responses, refills the window, returns as soon as
                // every requested index is in (so completion is not delayed by the idle sleep)
                // or when the watchdog finishes the subscription.
                group.addTask {
                    var collected: [UInt8: ChannelInfo] = [:]
                    var nextToSend = primeCount
                    for await event in events {
                        guard case .channelInfo(let info) = event,
                              requestedSet.contains(info.index),
                              collected[info.index] == nil else {
                            continue
                        }
                        await progressTracker.markProgress()
                        collected[info.index] = info
                        if nextToSend < requested.count {
                            let nextIndex = requested[nextToSend]
                            nextToSend += 1
                            // A refill failure (disconnect mid-drain) just stops sending; the
                            // watchdog's idle timeout then returns the partial set.
                            try? await sessionTransport.sendWithoutResponse(
                                PacketBuilder.getChannel(index: nextIndex)
                            )
                        }
                        if collected.count == requested.count {
                            return .collected(collected)
                        }
                    }
                    return .collected(collected)
                }

                // Watchdog: ends the stream on an inactivity gap or the hard cap so the consumer
                // returns its partial set. It must not finish the subscription once cancelled —
                // that path means the consumer already completed and the grace drain owns teardown.
                group.addTask {
                    while true {
                        if Task.isCancelled { return .watchdog }
                        let before = await progressTracker.snapshot()
                        if before.elapsed >= hardTimeout {
                            await sessionDispatcher.finishSubscription(id: subscriptionID)
                            return .watchdog
                        }
                        let remainingHard = max(0.001, hardTimeout - before.elapsed)
                        let sleepDuration = min(idleTimeout, remainingHard)
                        try? await sessionClock.sleep(for: .seconds(sleepDuration))
                        if Task.isCancelled { return .watchdog }
                        let after = await progressTracker.snapshot()
                        if after.elapsed >= hardTimeout || after.generation == before.generation {
                            await sessionDispatcher.finishSubscription(id: subscriptionID)
                            return .watchdog
                        }
                    }
                }

                var collected: [UInt8: ChannelInfo] = [:]
                for try await outcome in group {
                    if case .collected(let dict) = outcome {
                        collected = dict
                        group.cancelAll()
                        break
                    }
                    // A watchdog tick already finished the subscription; loop until the
                    // consumer returns its (possibly partial) collected set.
                }
                return collected
            }

            let missing = requested.filter { received[$0] == nil }
            if missing.isEmpty {
                // Every index answered. Hold the slot for the grace window with the subscription
                // still open so duplicate/straggler frames are absorbed here instead of leaking
                // into the next command.
                try? await sessionClock.sleep(for: .seconds(graceTimeout))
            }
            await dispatcher.finishSubscription(id: subscriptionID)

            let receivedSorted = received.keys.sorted().compactMap { received[$0] }
            return (received: receivedSorted, missing: missing)
        } catch {
            await dispatcher.finishSubscription(id: subscriptionID)
            throw error
        }
    }

    /// Configures a channel with name and secret.
    ///
    /// - Parameters:
    ///   - index: Channel index (0-255).
    ///   - name: Channel name.
    ///   - secret: The 32-byte channel secret key for encryption.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func setChannel(index: UInt8, name: String, secret: Data) async throws {
        try await sendSimpleCommand(PacketBuilder.setChannel(index: index, name: name, secret: secret))
    }

    /// Configures a channel with automatic secret derivation.
    ///
    /// - Parameters:
    ///   - index: Channel index (0-255).
    ///   - name: Channel name.
    ///   - secret: Secret derivation strategy. Defaults to deriving from name.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func setChannel(index: UInt8, name: String, secret: ChannelSecret = .deriveFromName) async throws {
        let secretData = secret.secretData(channelName: name)
        try await setChannel(index: index, name: name, secret: secretData)
    }
}
