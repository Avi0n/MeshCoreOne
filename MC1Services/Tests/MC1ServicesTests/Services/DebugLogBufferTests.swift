import Foundation
@testable import MC1Services
import Testing

/// Serialized because both tests reassign the process-global DebugLogBuffer.shared;
/// run in parallel, one test's reassignment breaks the other's final-value assertion.
@Suite("DebugLogBuffer Tests", .serialized)
struct DebugLogBufferTests {
  private func makeBuffer() async throws -> DebugLogBuffer {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return DebugLogBuffer(dataStore: store)
  }

  @Test
  func `shared get and set round-trip through the lock`() async throws {
    let buffer = try await makeBuffer()
    DebugLogBuffer.shared = buffer
    #expect(DebugLogBuffer.shared === buffer)
    DebugLogBuffer.shared = nil
    #expect(DebugLogBuffer.shared == nil)
  }

  /// Reassigning `shared` per connection while loggers read it from arbitrary
  /// actors is the data race this guards against; the lock must make
  /// concurrent reads and writes safe (verified under the thread sanitizer).
  @Test
  func `concurrent reads and writes of shared are race-free`() async throws {
    let bufferA = try await makeBuffer()
    let bufferB = try await makeBuffer()
    DebugLogBuffer.shared = bufferA

    await withTaskGroup(of: Void.self) { group in
      for index in 0..<200 {
        group.addTask {
          if index.isMultiple(of: 2) {
            DebugLogBuffer.shared = index.isMultiple(of: 4) ? bufferA : bufferB
          } else {
            _ = DebugLogBuffer.shared
          }
        }
      }
    }

    // Final value must be one of the two known buffers, never a torn pointer.
    let final = DebugLogBuffer.shared
    #expect(final === bufferA || final === bufferB)

    DebugLogBuffer.shared = nil
  }
}
