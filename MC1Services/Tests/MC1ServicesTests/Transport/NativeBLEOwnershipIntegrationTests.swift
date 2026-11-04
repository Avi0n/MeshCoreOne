@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Native BLE ownership integration")
@MainActor
struct NativeBLEOwnershipIntegrationTests {
  @Test(.timeLimit(.minutes(1)))
  func `native reconnect replaces the retired stack and resumes protocol IO`() async throws {
    let run = try await NativeConnectionPortHarness()
    let events = ValueTracker<DeviceConnectionState>()
    let stream = run.manager.connectionStateEvents.subscribe()
    let observer = Task { for await state in stream {
      events.record(state)
    } }
    let published = CallTracker()
    let usable = CallTracker()
    let synced = CallTracker()
    run.manager.onConnectionReady = { _ in published.markCalled() }
    run.manager.onConnectionBecameUsable = { _ in usable.markCalled() }
    run.manager.onDeviceSynced = { _, _ in synced.markCalled() }
    do {
      let initial = try await run.manager.connectViaBluetooth(peripheralID: run.native.deviceID)
      #expect(run.manager.isCurrent(initial))
      let first = try #require(run.manager.installedLink)
      run.remember(first)
      let firstStream = await run.adapter.receivedData
      let firstOrigin = try #require(await run.adapter.lifecycleOrigin)
      #expect(first.resource.binding.isActive)
      #expect(first.services.isEventMonitoringActive)
      #expect(await run.machine.currentAppSessionLiveDeviceID == first.device.id)
      let bridge = await run.machine.delegateHandler
      bridge.centralManager(
        run.native.central, didDisconnectPeripheral: run.native.peripheral,
        timestamp: CFAbsoluteTimeGetCurrent(), isReconnecting: true, error: nil
      )
      try await waitUntil("native loss must retire the old stack before reconnect discovery") {
        run.manager.reconnectionCoordinator.currentCycle != nil && run.manager.connectionTeardownCount == 0
      }
      #expect(run.manager.connectionState == .connecting)
      #expect(run.manager.installedLink == nil)
      #expect(!first.resource.binding.isActive)
      #expect(await first.resource.session.sessionIsRunning == false)
      #expect(await first.resource.session.subscriberCountForTest == 0)
      #expect(!first.services.isEventMonitoringActive)
      #expect(await run.machine.currentAppSessionLiveDeviceID == nil)
      #expect(await run.machine.isAutoReconnecting)
      #expect(run.native.cancellationCount == 0)
      var stoppedIterator = firstStream.makeAsyncIterator()
      #expect(await stoppedIterator.next() == nil)
      bridge.centralManager(run.native.central, didConnect: run.native.peripheral)
      // `.ready` is set before `onDeviceSynced`. A session read in between can delay that callback.
      try await waitUntil("real discovery, appStart, and onDeviceSynced must publish a replacement") {
        run.manager.connectionState == .ready && synced.callCount == 2
      }
      let next = try #require(run.manager.installedLink)
      run.remember(next)
      #expect(next !== first)
      #expect(next.requestID == first.requestID)
      #expect(next.resource !== first.resource)
      #expect(next.resource.binding !== first.resource.binding)
      #expect(next.resource.session !== first.resource.session)
      #expect(next.services !== first.services)
      #expect(next.services.dataStore === first.services.dataStore)
      #expect(next.device.id == first.device.id)
      #expect(next.device.radioID == first.device.radioID)
      #expect(next.device.publicKey == first.device.publicKey)
      #expect(next.services.chatSendQueueService.radioID == first.device.radioID)
      #expect(next.resource.binding.isActive)
      #expect(await next.resource.session.sessionIsRunning)
      #expect(next.services.isEventMonitoringActive)
      #expect(await run.machine.currentAppSessionLiveDeviceID == next.device.id)
      #expect(try await next.resource.session.getBattery().level == 3700)
      #expect(try await next.resource.session.getTime().timeIntervalSince1970 > 0)
      #expect(run.native.connectCount == 1)
      #expect(run.native.serviceDiscoveryCount == 2)
      #expect(run.native.subscriptionCount == 2)
      #expect(run.native.cancellationCount == 0)
      #expect(run.native.writePayloads.filter { $0.first == CommandCode.appStart.rawValue }.count == 2)
      #expect(run.native.automaticAcknowledgementCount == run.native.writePayloads.count)
      #expect(published.callCount == 2)
      #expect(usable.callCount == 2)
      #expect(synced.callCount == 2)
      #expect(try await run.manager.persistenceStore.fetchDevice(id: first.device.id)?.radioID == first.device.radioID)
      let closes = run.native.cancellationCount
      await run.manager.handleBLELifecycleEvent(.disconnected(firstOrigin, error: .authenticationFailed))
      #expect(run.manager.installedLink === next)
      #expect(next.resource.binding.isActive)
      #expect(run.native.cancellationCount == closes)
      _ = await run.manager.disconnect(reason: .userInitiated)
      #expect(await next.resource.session.subscriberCountForTest == 0)
      #expect(run.native.cancellationCount == 1)
      run.manager.connectionStateEvents.finish()
      await observer.value
      #expect(events.values == [.connecting, .connecting, .connected, .syncing, .ready,
                                .connecting, .connecting, .connected, .syncing, .ready, .disconnected])
    } catch {
      observer.cancel()
      await observer.value
      await run.close()
      throw error
    }
    await run.close()
  }
}
