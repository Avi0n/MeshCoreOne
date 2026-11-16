import Foundation
@testable import MC1Services
import Testing

@Suite("AccessoryPickerState")
@MainActor
struct AccessoryPickerStateTests {
  @Test
  func `selection resolves once and keeps picker occupied until dismissal`() async throws {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let selectedID = UUID()
    let task = await harness.begin(id: UUID())

    #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .selected)
    #expect(try await task.value == selectedID)
    #expect(harness.state.isActive)
    #expect(harness.state.accessoryAdded(bluetoothID: UUID()) == .ignored)
    let overlappingTask = await harness.begin(id: UUID())
    await #expect { try await overlappingTask.value } throws: { error in
      if case AccessorySetupKitError.pickerAlreadyActive = error { return true }
      return false
    }

    #expect(harness.state.dismiss()?.outcome == .selected)
    #expect(!harness.state.isActive)
    let nextTask = await harness.begin(id: UUID())
    #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .selected)
    #expect(try await nextTask.value == selectedID)
  }

  @Test
  func `cancellation keeps late selection cleanup owned until dismissal`() async {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let attemptID = UUID()
    let task = await harness.begin(id: attemptID)

    harness.state.cancel(attemptID: attemptID)
    await #expect(throws: CancellationError.self) { try await task.value }
    #expect(harness.state.isActive)
    let overlappingTask = await harness.begin(id: UUID())
    await #expect { try await overlappingTask.value } throws: { error in
      if case AccessorySetupKitError.pickerAlreadyActive = error { return true }
      return false
    }
    #expect(harness.state.accessoryAdded(bluetoothID: UUID()) == .removeAccessory)
    #expect(harness.state.accessoryAdded(bluetoothID: UUID()) == .ignored)
    #expect(harness.state.isActive)
    #expect(harness.state.dismiss()?.outcome == .orphanedAfterCancellation)
    #expect(!harness.state.isActive)
  }

  @Test
  func `dismissal and invalidation do not resolve an attempt twice`() async {
    let harness = Harness()
    let task = await harness.begin(id: UUID())

    harness.state.dismiss()
    harness.state.dismiss()
    harness.state.invalidate()

    await #expect { try await task.value } throws: { error in
      if case AccessorySetupKitError.pickerDismissed = error { return true }
      return false
    }
    #expect(!harness.state.isActive)
  }

  @Test
  func `invalidation rejects old completion and cancellation during new attempt`() async throws {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let oldID = UUID()
    let oldTask = await harness.begin(id: oldID)
    harness.state.invalidate()
    await #expect { try await oldTask.value } throws: { error in
      if case AccessorySetupKitError.sessionInvalidated = error { return true }
      return false
    }

    let newID = UUID()
    let newTask = await harness.begin(id: newID)
    harness.state.cancel(attemptID: oldID)
    harness.state.userCancelled(attemptID: oldID)
    harness.state.fail(
      AccessorySetupKitError.pickerRestricted,
      attemptID: oldID
    )

    #expect(harness.state.attempt?.id == newID)
    let selectedID = UUID()
    #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .selected)
    #expect(try await newTask.value == selectedID)
  }

  @Test
  func `rejected presentation permits retry without dismissal`() async throws {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let attemptID = UUID()
    let task = await harness.begin(id: attemptID)
    harness.state.fail(
      AccessorySetupKitError.pickerRestricted,
      attemptID: attemptID
    )

    await #expect { try await task.value } throws: { error in
      if case AccessorySetupKitError.pickerRestricted = error { return true }
      return false
    }
    #expect(!harness.state.isActive)
    let retryTask = await harness.begin(id: UUID())
    let selectedID = UUID()
    #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .selected)
    #expect(try await retryTask.value == selectedID)
  }

  @Test
  func `failure after caller cancellation preserves late selection cleanup`() async {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let attemptID = UUID()
    let task = await harness.begin(id: attemptID)
    harness.state.didPresent()
    harness.state.cancel(attemptID: attemptID)
    harness.state.fail(
      AccessorySetupKitError.pairingFailed("PIN rejected"),
      attemptID: attemptID
    )

    await #expect(throws: CancellationError.self) { try await task.value }
    #expect(harness.state.accessoryAdded(bluetoothID: UUID()) == .removeAccessory)
    #expect(harness.state.dismiss()?.outcome == .orphanedAfterCancellation)
  }

  @Test
  func `failure before queued cancellation still cleans up late selection`() async {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let attemptID = UUID()
    let task = await harness.begin(id: attemptID)
    harness.state.didPresent()
    harness.state.fail(
      AccessorySetupKitError.pairingFailed("PIN rejected"),
      attemptID: attemptID
    )
    harness.state.cancel(attemptID: attemptID)

    await #expect { try await task.value } throws: { error in
      if case AccessorySetupKitError.pairingFailed = error { return true }
      return false
    }
    #expect(harness.state.accessoryAdded(bluetoothID: UUID()) == .removeAccessory)
    #expect(harness.state.dismiss()?.outcome == .orphanedAfterCancellation)
  }

  @Test
  func `user cancellation callback waits for dismissal`() async {
    let harness = Harness()
    let attemptID = UUID()
    let task = await harness.begin(id: attemptID)

    harness.state.userCancelled(attemptID: attemptID)
    #expect(harness.state.isActive)
    harness.state.dismiss()

    await #expect { try await task.value } throws: { error in
      if case AccessorySetupKitError.pickerDismissed = error { return true }
      return false
    }
  }

  @Test
  func `selection without bluetooth identifier fails but retains picker`() async {
    let harness = Harness()
    let task = await harness.begin(id: UUID())

    #expect(harness.state.accessoryAdded(bluetoothID: nil) == .selected)
    await #expect { try await task.value } throws: { error in
      if case AccessorySetupKitError.noBluetoothIdentifier = error { return true }
      return false
    }
    #expect(harness.state.isActive)
    harness.state.dismiss()
  }

  @Test(arguments: [false, true])
  func `generic failure releases only an unpresented picker`(hasPresented: Bool) async throws {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let attemptID = UUID()
    let task = await harness.begin(id: attemptID)
    if hasPresented { harness.state.didPresent() }
    harness.state.fail(
      AccessorySetupKitError.connectionFailed,
      attemptID: attemptID
    )
    await #expect(throws: AccessorySetupKitError.self) { try await task.value }
    let retryTask = await harness.begin(id: UUID())
    if hasPresented {
      await #expect { try await retryTask.value } throws: { error in
        if case AccessorySetupKitError.pickerAlreadyActive = error { return true }
        return false
      }
    } else {
      let selectedID = UUID()
      #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .selected)
      #expect(try await retryTask.value == selectedID)
    }
  }

  @Test
  func `late failure cannot replace selection or transfer cleanup ownership`() async throws {
    let harness = Harness()
    defer { harness.state.invalidate() }
    let attemptID = UUID()
    let selectedID = UUID()
    let task = await harness.begin(id: attemptID)
    #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .selected)
    harness.state.fail(
      AccessorySetupKitError.pickerRestricted,
      attemptID: attemptID
    )
    harness.state.cancel(attemptID: attemptID)
    #expect(try await task.value == selectedID)
    #expect(harness.state.isActive)
    #expect(harness.state.accessoryAdded(bluetoothID: selectedID) == .ignored)
    #expect(harness.state.dismiss()?.outcome == .cancelledAfterSelection)
  }

  @MainActor
  private final class Harness {
    var state = AccessoryPickerState()

    func begin(id: UUID) async -> Task<UUID, Error> {
      let started = AsyncStream<Void>.makeStream()
      let task = Task {
        try await withCheckedThrowingContinuation { continuation in
          state.begin(id: id, presentedAt: Date(), filteredDiscovery: false, continuation: continuation)
          started.continuation.yield()
          started.continuation.finish()
        }
      }
      for await _ in started.stream {
        break
      }
      return task
    }
  }
}
