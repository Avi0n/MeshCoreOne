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

    private func request(_ lat: Double = 37.0, _ lon: Double = -122.0, dark: Bool = false) -> MapSnapshotRequest {
        MapSnapshotRequest(latitude: lat, longitude: lon, isDark: dark)
    }

    @Test("A cache miss returns nil synchronously; isResolved is false")
    func missIsNilAndUnresolved() {
        let store = MapSnapshotStore(renderer: FakeRenderer())
        let req = request()
        #expect(store.image(for: req) == nil)
        #expect(store.isResolved(req) == false)
    }

    @Test("A completed render caches the image and emits on the stream")
    func completedRenderCachesAndEmits() async {
        let fake = FakeRenderer()
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream.makeAsyncIterator()
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

        var iterator = store.resolutionStream.makeAsyncIterator()
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

        store.request(req)
        store.request(req)
        store.request(req)
        // Wait until the single render reaches the gate (it bumps renderCount
        // before parking on the gate); poll instead of guessing the yield count.
        while fake.renderCount == 0 { await Task.yield() }
        #expect(fake.renderCount == 1)

        fake.release()
        var iterator = store.resolutionStream.makeAsyncIterator()
        _ = await iterator.next()
        #expect(store.isResolved(req) == true)
    }

    @Test("A cached request is not re-rendered")
    func cachedRequestNotRerendered() async {
        let fake = FakeRenderer()
        let store = MapSnapshotStore(renderer: fake)
        let req = request()

        var iterator = store.resolutionStream.makeAsyncIterator()
        store.request(req)
        _ = await iterator.next()
        #expect(fake.renderCount == 1)

        store.request(req)
        await Task.yield()
        #expect(fake.renderCount == 1)
    }
}
