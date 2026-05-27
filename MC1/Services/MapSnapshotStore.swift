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
    /// Serial by default: 2-3 concurrent `MLNMapSnapshotter`s are unprecedented
    /// GL load in this app. Raise only if Instruments shows serial is too slow.
    /// Raising this above 1 also requires `MapSnapshotRenderer` to strongly hold
    /// each in-flight snapshotter for its own render duration — it currently
    /// relies on this serial cap (one render at a time), so concurrent renders
    /// could otherwise drop a snapshot callback.
    private static let maxConcurrent = 1
    /// Hard cap on the failed-render set. The set is sticky (failures stay
    /// known until cleared) and grows with chat history during flaky networks;
    /// this cap keeps memory bounded in pathological cases by FIFO-evicting the
    /// oldest entry, which causes that one request to retry on next on-appear.
    internal static let failedSetSizeLimit = 200

    private let renderer: MapSnapshotRendering
    private let cache = NSCache<NSString, UIImage>()
    private var inFlight: Set<MapSnapshotRequest> = []
    private var failed: Set<MapSnapshotRequest> = []
    /// Insertion-order shadow of `failed` for FIFO eviction once the set hits
    /// `failedSetSizeLimit`. Kept in sync with `failed`.
    private var failedOrder: [MapSnapshotRequest] = []
    /// Every request that has ever reached a resolved state (cached image or
    /// known failure). Used to broadcast invalidations on `clear()` and
    /// `clearFailures()` so on-screen rows reload to the skeleton state and
    /// re-request the snapshot.
    private var resolvedKeys: Set<MapSnapshotRequest> = []
    private let semaphore = AsyncSemaphore(value: maxConcurrent)
    /// Memory-warning observer token, retained for removal in `deinit`.
    private var memoryWarningObserver: NSObjectProtocol?

    /// One continuation per live subscriber. `AsyncStream` is single-consumer: a
    /// single shared stream hands each yield to only one iterator, so with two
    /// `ChatViewModel`s alive (iPad split view, or the overlap during a
    /// conversation switch) some thumbnails would never get their resolution and
    /// stay stuck on the skeleton. Each subscriber gets its own stream via
    /// `resolutionStream()`; every resolution is fanned out to all of them.
    private var resolutionContinuations: [UUID: AsyncStream<MapSnapshotRequest>.Continuation] = [:]

    init(renderer: MapSnapshotRendering = MapSnapshotRenderer()) {
        self.renderer = renderer
        cache.countLimit = Self.cacheCountLimit
        cache.totalCostLimit = Self.cacheCostLimitBytes

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
        let (stream, continuation) = AsyncStream.makeStream(
            of: MapSnapshotRequest.self,
            bufferingPolicy: .bufferingOldest(Self.resolutionStreamBufferDepth)
        )
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.resolutionContinuations.removeValue(forKey: id) }
        }
        resolutionContinuations[id] = continuation
        return stream
    }

    /// Wait-free cache lookup for the build path and the view resolver.
    func image(for request: MapSnapshotRequest) -> UIImage? {
        cache.object(forKey: request.cacheKey)
    }

    /// True once the render attempt has resolved (cached image or known failure).
    /// Build-time `isReady` reads this.
    func isResolved(_ request: MapSnapshotRequest) -> Bool {
        cache.object(forKey: request.cacheKey) != nil || failed.contains(request)
    }

    /// Lazily enqueue a render. Dedupes against the cache, the failed set, and the
    /// in-flight set, so repeated on-appear calls during scroll are cheap.
    func request(_ request: MapSnapshotRequest) {
        guard cache.object(forKey: request.cacheKey) == nil else { return }
        guard !failed.contains(request) else { return }
        guard !inFlight.contains(request) else { return }
        inFlight.insert(request)
        Task { await performRender(request) }
    }

    private func performRender(_ request: MapSnapshotRequest) async {
        // The bound runs off the main actor (the semaphore is an actor); every
        // step here is awaited, so the main actor is never blocked. `defer`
        // releases the semaphore on every exit path, including future cancel/
        // throw refactors — the current `render(_:)` never throws.
        await semaphore.wait()
        defer { Task { await semaphore.signal() } }
        let image = await renderer.render(request)

        inFlight.remove(request)
        if let image {
            cache.setObject(image, forKey: request.cacheKey, cost: ImageByteCost.bytes(for: image))
        } else {
            insertFailed(request)
        }
        resolvedKeys.insert(request)
        yieldResolution(request)
    }

    private func insertFailed(_ request: MapSnapshotRequest) {
        guard failed.insert(request).inserted else { return }
        failedOrder.append(request)
        while failedOrder.count > Self.failedSetSizeLimit {
            let evicted = failedOrder.removeFirst()
            failed.remove(evicted)
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
        let toInvalidate = resolvedKeys
        cache.removeAllObjects()
        failed.removeAll()
        failedOrder.removeAll()
        resolvedKeys.removeAll()
        for request in toInvalidate {
            yieldResolution(request)
        }
    }
}
