import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionIntent Tests")
struct ConnectionIntentTests {
  // MARK: - Convenience Properties

  @Test
  func `wantsConnection returns true for .wantsConnection`() {
    #expect(ConnectionIntent.wantsConnection().wantsConnection == true)
  }

  @Test
  func `wantsConnection returns true for .wantsConnection(forceFullSync: true)`() {
    #expect(ConnectionIntent.wantsConnection(forceFullSync: true).wantsConnection == true)
  }

  @Test
  func `wantsConnection returns false for .none`() {
    #expect(ConnectionIntent.none.wantsConnection == false)
  }

  @Test
  func `wantsConnection returns false for .userDisconnected`() {
    #expect(ConnectionIntent.userDisconnected.wantsConnection == false)
  }

  @Test
  func `isUserDisconnected returns true only for .userDisconnected`() {
    #expect(ConnectionIntent.userDisconnected.isUserDisconnected == true)
    #expect(ConnectionIntent.none.isUserDisconnected == false)
    #expect(ConnectionIntent.wantsConnection().isUserDisconnected == false)
  }

  // MARK: - Equatable

  @Test
  func `wantsConnection default is equatable`() {
    #expect(ConnectionIntent.wantsConnection() == ConnectionIntent.wantsConnection(forceFullSync: false))
  }

  @Test
  func `wantsConnection with different forceFullSync are not equal`() {
    #expect(ConnectionIntent.wantsConnection(forceFullSync: true) != ConnectionIntent.wantsConnection(forceFullSync: false))
  }

  // MARK: - Migration Equivalence

  @Test
  func `wantsConnection replaces shouldBeConnected = true`() {
    let intent = ConnectionIntent.wantsConnection()
    #expect(intent.wantsConnection == true)
    #expect(intent.isUserDisconnected == false)
  }

  @Test
  func `userDisconnected replaces setUserDisconnected + shouldBeConnected = false`() {
    let intent = ConnectionIntent.userDisconnected
    #expect(intent.wantsConnection == false)
    #expect(intent.isUserDisconnected == true)
  }

  @Test
  func `forceFullSync can be consumed and reset`() {
    var intent = ConnectionIntent.wantsConnection(forceFullSync: true)

    // Consume
    if case let .wantsConnection(force) = intent {
      #expect(force == true)
      intent = .wantsConnection()
    }

    // Verify reset
    if case let .wantsConnection(force) = intent {
      #expect(force == false)
    } else {
      Issue.record("Expected .wantsConnection after reset")
    }
  }
}

// MARK: - Persistence Tests

@Suite("ConnectionIntent Persistence Tests")
struct ConnectionIntentPersistenceTests {
  private let defaults: UserDefaults

  init() {
    defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  @Test
  func `userDisconnected persists and restores`() {
    ConnectionIntent.userDisconnected.persist(to: defaults)
    let restored = ConnectionIntent.restored(from: defaults)
    #expect(restored == .userDisconnected)
  }

  @Test
  func `none clears persisted userDisconnected`() {
    ConnectionIntent.userDisconnected.persist(to: defaults)
    #expect(ConnectionIntent.restored(from: defaults) == .userDisconnected)

    ConnectionIntent.none.persist(to: defaults)
    #expect(ConnectionIntent.restored(from: defaults) == .none)
  }

  @Test
  func `wantsConnection clears persisted userDisconnected`() {
    ConnectionIntent.userDisconnected.persist(to: defaults)
    #expect(ConnectionIntent.restored(from: defaults) == .userDisconnected)

    ConnectionIntent.wantsConnection().persist(to: defaults)
    #expect(ConnectionIntent.restored(from: defaults) == .none)
  }

  @Test
  func `restored returns .none when nothing persisted`() {
    #expect(ConnectionIntent.restored(from: defaults) == .none)
  }
}
