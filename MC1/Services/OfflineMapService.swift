import Foundation
import Network

enum OfflineMapLayer: String, Codable {
    case base

    var label: String { L10n.Settings.OfflineMaps.Layer.base }
}

struct OfflinePack: Identifiable {
    let id = UUID()
}

@MainActor @Observable
final class OfflineMapService {

    private(set) var packs: [OfflinePack] = []
    private(set) var databaseSize: Int64 = 0
    private(set) var isNetworkAvailable = true
    private(set) var lastPackError: String?

    private let monitor = NWPathMonitor()
    private var monitorTask: Task<Void, Never>?

    init() {
        let monitor = self.monitor
        let networkStream = AsyncStream<NWPath> { continuation in
            continuation.onTermination = { _ in monitor.cancel() }
            monitor.pathUpdateHandler = { continuation.yield($0) }
            monitor.start(queue: .global(qos: .utility))
        }
        monitorTask = Task { [weak self] in
            for await path in networkStream {
                self?.isNetworkAvailable = path.status == .satisfied
            }
        }
    }

    isolated deinit {
        monitor.cancel()
        monitorTask?.cancel()
    }

    func hasCompletedPack(for layer: OfflineMapLayer) -> Bool { false }
    func resumeAllPacks() {}
    func deletePack(_ pack: OfflinePack) async {}
    func pausePack(_ pack: OfflinePack) {}
    func resumePack(_ pack: OfflinePack) {}
    func clearLastPackError() { lastPackError = nil }
}
