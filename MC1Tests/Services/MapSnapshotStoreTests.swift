import Testing
import UIKit
@testable import MC1

@Suite("MapSnapshotStore Tests")
@MainActor
struct MapSnapshotStoreTests {

    /// Fake renderer: returns a 1x1 solid image after an optional gate, counting
    /// calls. No MapLibre/GL.
    final class FakeRenderer: MapSnapshotRendering {
        private(set) var renderCount = 0
        var gate: CheckedContinuation<Void, Never>?
        var shouldFail = false

        func render(_ request: MapSnapshotRequest) async -> UIImage? {
            renderCount += 1
            if gate == nil, awaitGate {
                await withCheckedContinuation { gate = $0 }
            }
            if shouldFail { return nil }
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1))
            return renderer.image { ctx in
                UIColor.red.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
            }
        }

        var awaitGate = false
        func release() { gate?.resume(); gate = nil }
    }

    private func request(
        _ lat: Double = 37.0,
        _ lon: Double = -122.0,
        dark: Bool = false,
        offline: Bool = false
    ) -> MapSnapshotRequest {
        MapSnapshotRequest(latitude: lat, longitude: lon, isDark: dark, isOffline: offline)
    }

    @Test("A cache miss returns nil synchronously; isResolved is false")
    func missIsNilAndUnresolved() {
        let store = MapSnapshotStore(renderer: FakeRenderer())
        let req = request()
        #expect(store.image(for: req) == nil)
        #expect(store.isResolved(req) == false)
    }

    @Test("Signed-zero coordinates normalize so cacheKey agrees with Hashable equality")
    func signedZeroNormalizes() {
        // A latitude that rounds to -0.0 must key identically to +0.0: the two are
        // Hashable-equal, so a divergent cacheKey string would split the cache.
        let negativeZero = MapSnapshotRequest(latitude: -0.000004, longitude: 50.0, isDark: false, isOffline: false)
        let positiveZero = MapSnapshotRequest(latitude: 0.0, longitude: 50.0, isDark: false, isOffline: false)
        #expect(negativeZero == positiveZero)
        #expect(negativeZero.hashValue == positiveZero.hashValue)
        #expect(negativeZero.cacheKey == positiveZero.cacheKey)
    }

    @Test("Online and offline requests for the same coordinate produce distinct cache keys")
    func isOfflineDistinguishesCacheKey() {
        let online = MapSnapshotRequest(latitude: 37.0, longitude: -122.0, isDark: false, isOffline: false)
        let offline = MapSnapshotRequest(latitude: 37.0, longitude: -122.0, isDark: false, isOffline: true)
        #expect(online != offline)
        #expect(online.hashValue != offline.hashValue)
        #expect(online.cacheKey != offline.cacheKey)
    }

    @Test("A completed render caches the image and emits on the stream")
    func completedRenderCachesAndEmits() async {
        let fake = FakeRenderer()
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream().makeAsyncIterator()
        store.request(req)
        let emitted = await iterator.next()

        #expect(emitted == req)
        #expect(store.image(for: req) != nil)
        #expect(store.isResolved(req) == true)
        #expect(fake.renderCount == 1)
    }

    @Test("A failed render marks the request resolved with no image")
    func failedRenderResolvesWithoutImage() async {
        let fake = FakeRenderer()
        fake.shouldFail = true
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream().makeAsyncIterator()
        store.request(req)
        _ = await iterator.next()

        #expect(store.image(for: req) == nil)
        #expect(store.isResolved(req) == true)
    }

    @Test("N concurrent requests for the same key enqueue exactly one render")
    func dedupesConcurrentSameKey() async {
        let fake = FakeRenderer()
        fake.awaitGate = true
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream().makeAsyncIterator()
        store.request(req)
        store.request(req)
        store.request(req)
        // Wait until the single render reaches the gate (it bumps renderCount
        // before parking on the gate); poll instead of guessing the yield count.
        while fake.renderCount == 0 { await Task.yield() }
        #expect(fake.renderCount == 1)

        fake.release()
        _ = await iterator.next()
        #expect(store.isResolved(req) == true)
    }

    @Test("A cached request is not re-rendered")
    func cachedRequestNotRerendered() async {
        let fake = FakeRenderer()
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream().makeAsyncIterator()
        store.request(req)
        _ = await iterator.next()
        #expect(fake.renderCount == 1)

        store.request(req)
        await Task.yield()
        #expect(fake.renderCount == 1)
    }

    @Test("The failed set is capped; the oldest entry is evicted when the limit is exceeded")
    func failedSetCapEvictsOldest() async {
        let fake = FakeRenderer()
        fake.shouldFail = true
        let store = MapSnapshotStore(renderer: fake)
        let limit = MapSnapshotStore.failedSetSizeLimit

        var iterator = store.resolutionStream().makeAsyncIterator()
        for i in 0..<(limit + 1) {
            store.request(request(Double(i), 0))
            _ = await iterator.next()
        }

        // Oldest entry was evicted; a fresh request would re-render.
        #expect(store.isResolved(request(0, 0)) == false)
        // Newest entry is still marked failed.
        #expect(store.isResolved(request(Double(limit), 0)) == true)
    }

    @Test("clear() yields resolutions for previously-resolved requests so on-screen rows reload")
    func clearYieldsResolutionsForResolvedRequests() async {
        let fake = FakeRenderer()
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream().makeAsyncIterator()
        store.request(req)
        let firstEmit = await iterator.next()
        #expect(firstEmit == req)
        #expect(store.image(for: req) != nil)

        store.clear()

        let secondEmit = await iterator.next()
        #expect(secondEmit == req)
        #expect(store.image(for: req) == nil)
        #expect(store.isResolved(req) == false)
    }

    @Test("clearFailures() drops failed entries so the next request triggers a retry")
    func clearFailuresAllowsRetry() async {
        let fake = FakeRenderer()
        fake.shouldFail = true
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream().makeAsyncIterator()
        store.request(req)
        _ = await iterator.next()
        #expect(store.isResolved(req) == true)

        store.clearFailures()
        // Drain the invalidation yield emitted by clearFailures() so the next
        // iterator.next() sees the retry's resolution, not the stale invalidation.
        _ = await iterator.next()
        #expect(store.isResolved(req) == false)

        fake.shouldFail = false
        store.request(req)
        _ = await iterator.next()
        #expect(store.image(for: req) != nil)
        #expect(fake.renderCount == 2)
    }

    @Test("Every subscriber receives each resolution (multicast, not single-consumer split)")
    func multicastDeliversToAllSubscribers() async {
        let fake = FakeRenderer()
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iteratorA = store.resolutionStream().makeAsyncIterator()
        var iteratorB = store.resolutionStream().makeAsyncIterator()
        store.request(req)

        // A single shared AsyncStream would hand the yield to only one iterator;
        // both must see it.
        let emittedA = await iteratorA.next()
        let emittedB = await iteratorB.next()
        #expect(emittedA == req)
        #expect(emittedB == req)
        #expect(fake.renderCount == 1)
    }
}
