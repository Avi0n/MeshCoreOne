import Foundation
import Testing
@testable import MC1Services

@Suite("ConnectionManager BLE Scanning Tests")
@MainActor
struct ConnectionManagerBLEScanningTests {

    @Test("startBLEScanning starts scan and forwards discoveries")
    func startBLEScanningForwardsDiscoveries() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let stream = manager.startBLEScanning()

        let expectedID = UUID()
        let receiveTask = Task { await nextValue(from: stream, timeout: .seconds(1)) }

        try await waitUntil("BLE scanning should start") {
            await mock.isScanning
        }
        await mock.simulateDiscoveredDevice(id: expectedID, name: "MeshCore-Test", rssi: -68)

        let discovery = await receiveTask.value
        #expect(discovery?.id == expectedID)
        #expect(discovery?.name == "MeshCore-Test")
        #expect(discovery?.rssi == -68)
        #expect(await mock.startScanningCallCount == 1)
        #expect(await mock.isScanning)

        await manager.stopBLEScanning()
        #expect(await mock.isScanning == false)
    }

    @Test("terminated scan stream does not leave scanning active")
    func terminatedStreamDoesNotLeakScanning() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()
        let stream = manager.startBLEScanning()

        let consumeTask = Task {
            for await _ in stream {}
        }
        consumeTask.cancel()
        _ = await consumeTask.result

        try await waitUntil("Stream cleanup should stop scanning") {
            await mock.isScanning == false
        }

        #expect(await mock.isScanning == false)
        #expect(await mock.stopScanningCallCount >= 1)
    }

    @Test("older stream termination does not stop newer scan")
    func oldStreamTerminationDoesNotStopNewScan() async throws {
        let (manager, mock) = try ConnectionManager.createForTesting()

        let stream1 = manager.startBLEScanning()
        let consumeTask1 = Task {
            for await _ in stream1 {}
        }
        try await waitUntil("stream1 scanning should start") {
            await mock.isScanning
        }

        let stream2 = manager.startBLEScanning()
        let consumeTask2 = Task {
            for await _ in stream2 {}
        }
        try await waitUntil("stream2 scanning should start") {
            await mock.startScanningCallCount == 2
        }

        consumeTask1.cancel()
        _ = await consumeTask1.result

        // Fixed sleep: negative assertion — older stream cleanup must not stop the newer scan.
        // We wait briefly to confirm no erroneous stopScanning call fires.
        try? await Task.sleep(for: .milliseconds(100))

        #expect(await mock.isScanning, "Newer scan should remain active")

        consumeTask2.cancel()
        _ = await consumeTask2.result
    }

    private func nextValue(
        from stream: AsyncStream<DiscoveredDevice>,
        timeout: Duration
    ) async -> DiscoveredDevice? {
        await withTaskGroup(of: DiscoveredDevice?.self) { group in
            group.addTask {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next()
            }
            group.addTask {
                try? await Task.sleep(for: timeout)
                return nil
            }

            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
