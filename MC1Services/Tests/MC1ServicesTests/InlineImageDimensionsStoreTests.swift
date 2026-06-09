import Foundation
import CoreGraphics
import Testing
@testable import MC1Services

@Suite("InlineImageDimensionsStore Tests")
struct InlineImageDimensionsStoreTests {

    private static let streamWaitNanoseconds: UInt64 = 1_000_000_000

    private static func makeTempFileURL() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appending(path: "InlineImageDimensionsStoreTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        return dir.appending(path: "InlineImageDimensions.json")
    }

    @Test("save then aspect(for:) returns the expected ratio")
    func saveThenAspectReturnsRatio() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let url = URL(string: "https://example.com/a.png")!
        await store.save(url: url, size: CGSize(width: 200, height: 100))

        #expect(store.aspect(for: url) == 2.0)
    }

    @Test("save with zero width is rejected silently")
    func saveWithZeroWidthIsRejected() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let url = URL(string: "https://example.com/zero-width.png")!
        await store.save(url: url, size: CGSize(width: 0, height: 100))

        #expect(store.aspect(for: url) == nil)
    }

    @Test("save with zero height is rejected silently")
    func saveWithZeroHeightIsRejected() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let url = URL(string: "https://example.com/zero-height.png")!
        await store.save(url: url, size: CGSize(width: 100, height: 0))

        #expect(store.aspect(for: url) == nil)
    }

    @Test("init recovers from a corrupt file by starting empty")
    func initRecoversFromCorruptFile() async throws {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data("not json".utf8).write(to: fileURL)

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let sampleURL = URL(string: "https://example.com/anything.png")!

        #expect(store.aspect(for: sampleURL) == nil)
    }

    @Test("init on non-existent file yields empty store")
    func initOnMissingFileYieldsEmptyStore() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let url = URL(string: "https://example.com/unknown.png")!

        #expect(store.aspect(for: url) == nil)
    }

    @Test("two saves are both readable via aspect(for:)")
    func twoSavesAreBothReadable() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let urlA = URL(string: "https://example.com/a.png")!
        let urlB = URL(string: "https://example.com/b.png")!
        await store.save(url: urlA, size: CGSize(width: 400, height: 200))
        await store.save(url: urlB, size: CGSize(width: 100, height: 400))

        #expect(store.aspect(for: urlA) == 2.0)
        #expect(store.aspect(for: urlB) == 0.25)
    }

    @Test("resolutionStream emits the URL on save")
    func resolutionStreamEmitsOnSave() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let store = InlineImageDimensionsStore(fileURL: fileURL)
        let url = URL(string: "https://example.com/stream.png")!

        let receiveTask = Task<URL?, Never> {
            for await emitted in store.resolutionStream {
                return emitted
            }
            return nil
        }

        let timeoutTask = Task<URL?, Never> {
            try? await Task.sleep(nanoseconds: Self.streamWaitNanoseconds)
            receiveTask.cancel()
            return nil
        }

        await store.save(url: url, size: CGSize(width: 300, height: 150))

        let received = await receiveTask.value
        timeoutTask.cancel()

        #expect(received == url)
    }

    @Test("round-trip: recreated store reads previously persisted aspect")
    func roundTripPersistence() async {
        let fileURL = Self.makeTempFileURL()
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let url = URL(string: "https://example.com/persisted.png")!

        let writer = InlineImageDimensionsStore(fileURL: fileURL)
        await writer.save(url: url, size: CGSize(width: 600, height: 300))

        let reader = InlineImageDimensionsStore(fileURL: fileURL)
        #expect(reader.aspect(for: url) == 2.0)
    }
}
