import Foundation
@testable import MC1Services
import Testing

/// Serialized because both tests reassign the process-global DebugLogBuffer.shared,
/// so running them in parallel breaks each other's assertions on that global.
/// `ServiceContainer.init` also reassigns this same global, and other test suites
/// that build a `ServiceContainer` run concurrently with this suite, so a read
/// right after a write can observe a foreign buffer written in between. Assertions
/// below read back through a bounded retry to absorb that interleaving.
@Suite("DebugLogBuffer Tests", .serialized)
struct DebugLogBufferTests {
  private static let maxReadBackAttempts = 5

  private func makeBuffer() async throws -> DebugLogBuffer {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return DebugLogBuffer(dataStore: store)
  }

  /// Writes `buffer` to the global and reads it back, retrying because a
  /// concurrently running suite may reassign the global between the write and
  /// the read. Succeeds as soon as one attempt reads back the value just
  /// written; only fails if every attempt observes a foreign buffer instead.
  private func writeAndReadBack(_ buffer: DebugLogBuffer, attempts: Int = maxReadBackAttempts) -> Bool {
    for _ in 0..<attempts {
      DebugLogBuffer.shared = buffer
      if DebugLogBuffer.shared === buffer {
        return true
      }
    }
    return false
  }

  @Test
  func `shared get and set round-trip through the lock`() async throws {
    let buffer = try await makeBuffer()
    #expect(writeAndReadBack(buffer))
    DebugLogBuffer.shared = nil
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

    #expect(writeAndReadBack(bufferA))

    DebugLogBuffer.shared = nil
  }
}
