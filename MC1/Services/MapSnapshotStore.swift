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
    private static let bytesPerPixelRGBA = 4
    /// Serial by default: 2-3 concurrent `MLNMapSnapshotter`s are unprecedented
    /// GL load in this app. Raise only if Instruments shows serial is too slow.
    /// Raising this above 1 also requires `MapSnapshotRenderer` to strongly hold
    /// each in-flight snapshotter for its own render duration — it currently
    /// relies on this serial cap (one render at a time), so concurrent renders
    /// could otherwise drop a snapshot callback.
    private static let maxConcurrent = 1

    private let renderer: MapSnapshotRendering
    private let cache = NSCache<NSString, UIImage>()
    private var inFlight: Set<MapSnapshotRequest> = []
    private var failed: Set<MapSnapshotRequest> = []
    private let semaphore = AsyncSemaphore(value: maxConcurrent)

    let resolutionStream: AsyncStream<MapSnapshotRequest>
    private let streamContinuation: AsyncStream<MapSnapshotRequest>.Continuation

    init(renderer: MapSnapshotRendering = MapSnapshotRenderer()) {
        self.renderer = renderer
        cache.countLimit = Self.cacheCountLimit
        cache.totalCostLimit = Self.cacheCostLimitBytes

        let (stream, continuation) = AsyncStream.makeStream(
            of: MapSnapshotRequest.self,
            bufferingPolicy: .bufferingOldest(Self.resolutionStreamBufferDepth)
        )
        resolutionStream = stream
        streamContinuation = continuation

        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            Task { @MainActor in self?.clear() }
        }
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
        // step here is awaited, so the main actor is never blocked.
        await semaphore.wait()
        let image = await renderer.render(request)
        await semaphore.signal()

        inFlight.remove(request)
        if let image {
            cache.setObject(image, forKey: request.cacheKey, cost: Self.cost(of: image))
        } else {
            failed.insert(request)
        }
        streamContinuation.yield(request)
    }

    /// Drops cached and failed state on memory pressure. In-flight renders
    /// complete and re-cache.
    private func clear() {
        cache.removeAllObjects()
        failed.removeAll()
    }

    private static func cost(of image: UIImage) -> Int {
        if let cgImage = image.cgImage {
            return cgImage.bytesPerRow * cgImage.height
        }
        return Int(image.size.width * image.size.height) * bytesPerPixelRGBA
    }
}
