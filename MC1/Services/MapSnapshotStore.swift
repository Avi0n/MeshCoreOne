import UIKit

/// Process-lifetime cache + bounded render queue for chat map thumbnails.
/// Mirrors `DecodedPreviewCache` / `InlineImageCache.shared`: the cache outlives
/// any `ChatViewModel` so chat re-entry repaints without re-running GL. The VM
/// owns only the `resolutionStream` subscription.
@MainActor
final class MapSnapshotStore {
    static let shared = MapSnapshotStore()

    private static let cacheCountLimit = 50
    private static let cacheCostLimitBytes = 50 * 1024 * 1024
    private static let resolutionStreamBufferDepth = 64
    private static let maxConcurrent = 1
    /// Hard cap on the failed-render set. The set is sticky (failures stay
    /// known until cleared) and grows with chat history during flaky networks;
    /// this cap keeps memory bounded in pathological cases by FIFO-evicting the
    /// oldest entry, which causes that one request to retry on next on-appear.
    internal static let failedSetSizeLimit = 200

    private let renderer: MapSnapshotRendering
    /// Hand-rolled FIFO image cache. Mirrors the `DecodedPreviewCache` and
    /// `InlineImageCache.decodedMirror` patterns — and unlike `NSCache`, FIFO
    /// eviction here knows which key is being dropped, so it can notify
    /// `resolvedKeys` and yield a resolution event so on-screen rows reload to
    /// the skeleton state and re-request the snapshot. Also gives the
    /// `resolvedKeys` mirror a natural bound (it shadows live cache entries).
    private var imageEntries: [MapSnapshotRequest: ImageEntry] = [:]
    private var imageInsertionOrder: [MapSnapshotRequest] = []
    private var totalImageCostBytes: Int = 0
    private var inFlight: Set<MapSnapshotRequest> = []
    private var failed: Set<MapSnapshotRequest> = []
    /// Insertion-order shadow of `failed` for FIFO eviction once the set hits
    /// `failedSetSizeLimit`. Kept in sync with `failed`.
    private var failedOrder: [MapSnapshotRequest] = []
    /// Every request that has ever reached a resolved state (cached image or
    /// known failure). Used to broadcast invalidations on `clear()` and
    /// `clearFailures()` so on-screen rows reload to the skeleton state and
    /// re-request the snapshot. Bounded because cache eviction and failed-set
    /// FIFO eviction both prune the matching key here.
    private var resolvedKeys: Set<MapSnapshotRequest> = []
    private let semaphore = AsyncSemaphore(value: maxConcurrent)
    /// Memory-warning observer token, retained for removal in `deinit`.
    private var memoryWarningObserver: NSObjectProtocol?
    /// Bumped on every `clear()` so a `performRender` that started before the
    /// clear can detect it (the render's `await` releases the main actor; the
    /// memory-warning observer can run during that suspension and empty
    /// everything). The post-await branch reads this and bails to avoid
    /// re-populating the state the clear was trying to evict.
    private var clearGeneration: UInt64 = 0

    /// One continuation per live subscriber. `AsyncStream` is single-consumer: a
    /// single shared stream hands each yield to only one iterator, so with two
    /// `ChatViewModel`s alive (iPad split view, or the overlap during a
    /// conversation switch) some thumbnails would never get their resolution and
    /// stay stuck on the skeleton. Each subscriber gets its own stream via
    /// `resolutionStream()`; every resolution is fanned out to all of them.
    private var resolutionContinuations: [UUID: AsyncStream<MapSnapshotRequest>.Continuation] = [:]

    init(renderer: MapSnapshotRendering = MapSnapshotRenderer()) {
        self.renderer = renderer

        memoryWarningObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            Task { @MainActor in self?.clear() }
        }
    }

    isolated deinit {
        if let memoryWarningObserver {
            NotificationCenter.default.removeObserver(memoryWarningObserver)
        }
    }

    /// A resolution stream scoped to a single subscriber. The subscriber's task
    /// ending (cancel or deinit) terminates the stream and drops its continuation.
    /// Every render resolution is delivered to all live subscribers.
    func resolutionStream() -> AsyncStream<MapSnapshotRequest> {
        let id = UUID()
        // `bufferingNewest` is the right policy for a latest-state notification
        // stream: when the buffer is full, `bufferingOldest` would drop new
        // events instead of replacing stale ones. `clear()` and `clearFailures()`
        // can synchronously yield hundreds of events at once (one per resolved
        // request), so the buffer can saturate before the main-actor consumer
        // runs; the newest events are the ones the UI needs.
        let (stream, continuation) = AsyncStream.makeStream(
            of: MapSnapshotRequest.self,
            bufferingPolicy: .bufferingNewest(Self.resolutionStreamBufferDepth)
        )
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.resolutionContinuations.removeValue(forKey: id) }
        }
        resolutionContinuations[id] = continuation
        return stream
    }

    /// Synchronous cache lookup for the build path and the view resolver.
    func image(for request: MapSnapshotRequest) -> UIImage? {
        imageEntries[request]?.image
    }

    /// True once the render attempt has resolved (cached image or known failure).
    /// Build-time `isReady` reads this.
    func isResolved(_ request: MapSnapshotRequest) -> Bool {
        imageEntries[request] != nil || failed.contains(request)
    }

    /// Lazily enqueue a render. Dedupes against the cache, the failed set, and the
    /// in-flight set, so repeated on-appear calls during scroll are cheap.
    func request(_ request: MapSnapshotRequest) {
        guard imageEntries[request] == nil else { return }
        guard !failed.contains(request) else { return }
        guard !inFlight.contains(request) else { return }
        inFlight.insert(request)
        Task { await performRender(request) }
    }

    private func performRender(_ request: MapSnapshotRequest) async {
        // `await`s here release the main actor; the memory-warning observer
        // can run `clear()` during the suspension. Capture the generation up
        // front so we can detect that case post-await and skip the writes —
        // otherwise we'd re-populate the exact state the clear just evicted.
        let generationAtStart = clearGeneration
        // Drain `inFlight` on every exit path. The renderer's snapshotter is
        // wrapped in `withTaskCancellationHandler`, but if a future caller
        // ever cancels this task before the cache write runs, an explicit
        // `inFlight` remove keeps subsequent on-appear retries from being
        // dedupe-swallowed forever.
        defer { inFlight.remove(request) }
        await semaphore.wait()
        defer { Task { await semaphore.signal() } }
        let image = await renderer.render(request)

        guard clearGeneration == generationAtStart else {
            // `clear()` already evicted everything resolved at the time and
            // yielded an invalidation. Skip the cache write so the eviction
            // sticks; the deferred `inFlight.remove` keeps the dedupe set
            // consistent regardless.
            return
        }

        if let image {
            cacheImage(image, for: request)
        } else {
            insertFailed(request)
        }
        resolvedKeys.insert(request)
        yieldResolution(request)
    }

    /// Inserts an image into the FIFO mirror and evicts oldest entries while
    /// either the count or cost cap is exceeded. Each eviction prunes the
    /// matching `resolvedKeys` entry and yields a resolution so on-screen rows
    /// for that key reload to the skeleton state and re-fire `request(_:)`.
    private func cacheImage(_ image: UIImage, for request: MapSnapshotRequest) {
        let cost = ImageByteCost.bytes(for: image)
        if let existing = imageEntries[request] {
            totalImageCostBytes -= existing.cost
            if let idx = imageInsertionOrder.firstIndex(of: request) {
                imageInsertionOrder.remove(at: idx)
            }
        }
        imageEntries[request] = ImageEntry(image: image, cost: cost)
        imageInsertionOrder.append(request)
        totalImageCostBytes += cost

        // Keep at least the just-inserted entry even when it singly exceeds
        // the cost budget — matches `InlineImageCache.storeDecoded` so a large
        // thumbnail is still served once before being evicted.
        while imageInsertionOrder.count > 1,
              imageInsertionOrder.count > Self.cacheCountLimit
                || totalImageCostBytes > Self.cacheCostLimitBytes {
            let evicted = imageInsertionOrder.removeFirst()
            if let removed = imageEntries.removeValue(forKey: evicted) {
                totalImageCostBytes -= removed.cost
            }
            resolvedKeys.remove(evicted)
            yieldResolution(evicted)
        }
    }

    private func insertFailed(_ request: MapSnapshotRequest) {
        guard failed.insert(request).inserted else { return }
        failedOrder.append(request)
        while failedOrder.count > Self.failedSetSizeLimit {
            let evicted = failedOrder.removeFirst()
            failed.remove(evicted)
            // Mirror the FIFO cache eviction path: pruning a failed entry must
            // also drop the matching `resolvedKeys` and yield a resolution, so
            // any on-screen row showing the stale failure fallback flips back
            // to the skeleton state and re-fires `request(_:)`.
            resolvedKeys.remove(evicted)
            yieldResolution(evicted)
        }
    }

    /// Yields a resolution event to every live subscriber. Each `ChatViewModel`
    /// translates the request into the affected message rows via its
    /// `mapPreviewRequestIndex` and rebuilds them.
    private func yieldResolution(_ request: MapSnapshotRequest) {
        for continuation in resolutionContinuations.values {
            continuation.yield(request)
        }
    }

    /// Drops a single failed key so the next `request(_:)` re-attempts the
    /// render. Wired to the retry control on the chat thumbnail fallback so a
    /// user who hit a transient online failure can recover without waiting on
    /// the offline-to-online edge that calls `clearFailures()`.
    func retry(_ request: MapSnapshotRequest) {
        guard failed.remove(request) != nil else { return }
        if let idx = failedOrder.firstIndex(of: request) {
            failedOrder.remove(at: idx)
        }
        resolvedKeys.remove(request)
        // Yielding flips the row back to the skeleton state via the rebuild,
        // which re-fires `request(_:)` through the existing `.onAppear`.
        yieldResolution(request)
    }

    /// Drops every known failure so the next `request(_:)` for those keys
    /// re-attempts the render. Called when the network transitions from
    /// unavailable to available (in `ChatViewModel.applyEnvInputs`) so that
    /// renders failed during the outage retry on the next chat rebuild. The
    /// yield reloads on-screen rows showing the failure fallback to the
    /// skeleton state so they re-fire `request(_:)` via `onAppear`.
    func clearFailures() {
        let cleared = failed
        failed.removeAll()
        failedOrder.removeAll()
        for request in cleared {
            resolvedKeys.remove(request)
            yieldResolution(request)
        }
    }

    /// Drops cached and failed state on memory pressure and yields a
    /// resolution event for each previously-resolved request so on-screen
    /// rows reload to the skeleton state and re-request the snapshot. Without
    /// the broadcast, rows whose cached image was evicted would strand on the
    /// fallback because the build-time `isReady` doesn't flip back without a
    /// rebuild trigger. Internal for tests; in production it is invoked from
    /// the memory-warning observer in `init`.
    internal func clear() {
        clearGeneration &+= 1
        let toInvalidate = resolvedKeys
        imageEntries.removeAll()
        imageInsertionOrder.removeAll()
        totalImageCostBytes = 0
        failed.removeAll()
        failedOrder.removeAll()
        resolvedKeys.removeAll()
        // In-flight renders restart with `inFlight` empty so post-clear
        // on-appear `request(_:)` calls re-enqueue without dedupe; the
        // generation guard in `performRender` keeps the pre-clear render's
        // result from leaking back into the cleared state.
        inFlight.removeAll()
        for request in toInvalidate {
            yieldResolution(request)
        }
    }

    /// Pairs the cached image with its precomputed byte cost so the eviction
    /// path can decrement the running total without re-running `ImageByteCost`
    /// on the way out.
    private struct ImageEntry {
        let image: UIImage
        let cost: Int
    }
}
