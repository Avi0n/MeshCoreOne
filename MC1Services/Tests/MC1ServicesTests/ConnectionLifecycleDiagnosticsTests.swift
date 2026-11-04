import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Connection lifecycle diagnostics", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct ConnectionLifecycleDiagnosticsTests {
  @Test
  func `production publication and retirement keep their captured owners in persistent records`() async throws {
    let run = try Harness()
    do {
      let link = try await run.connect()
      let request = try #require(run.env.manager.desiredRequest)
      let attempt = try #require(request.attempt)
      let origin = try #require(request.physicalOrigin)
      let fields = capturedFields(request: request, attempt: attempt, link: link, origin: origin)
      #expect(try await link.resource.session.getBattery().level == 3700)
      run.reclaimLogs()

      _ = await run.env.manager.disconnect(reason: .userInitiated)

      let records = try await run.records(until: ["event=retired,"])
      let terminalRequestID = try #require(run.env.manager.connectionRequestID)
      #expect(records.contains { record in
        record.contains("event=disconnectAdmitted,") && record.contains("request=\(terminalRequestID)")
          && record.contains("currentLink=\(link.generation)")
          && record.contains("currentBinding=\(ObjectIdentifier(link.resource.binding))")
          && record.contains("reason=\(DisconnectReason.userInitiated.rawValue)")
      })
      #expect(records.contains { $0.contains("event=admitted,") && $0.contains("request=\(request.id)") })
      #expect(records.contains { $0.contains("event=attemptStarted,") && $0.contains("attempt=\(ObjectIdentifier(attempt))") })
      for event in ["published", "dispose", "retired"] {
        #expect(records.contains { record in
          record.contains("event=\(event),") && fields.allSatisfy { record.contains($0) }
        }, "The \(event) record must retain the exact request, Link, binding, session and physical origin")
      }
      #expect(records.contains { record in
        record.contains("event=state,") && record.contains("state=ready") && fields.allSatisfy { record.contains($0) }
      })
      #expect(records.contains { $0.contains("event=state,") && $0.contains("state=disconnected") && $0.contains("link=none") })
      #expect(run.env.manager.currentConnection == nil)
      #expect(await run.env.ble.disconnectCount == 1)
      expectAllowedFields(records)
    } catch { await run.close(); throw error }
    await run.close()
  }

  @Test
  func `stale admission and publication explain the rejected owner and same-radio successor`() async throws {
    let run = try Harness()
    do {
      let first = try await run.connect()
      let oldRequest = try #require(run.env.manager.desiredRequest)
      let oldAttempt = try #require(oldRequest.attempt)
      let next = try await run.connect()
      let currentRequest = try #require(run.env.manager.desiredRequest)
      let connects = await run.env.ble.connectCount
      let disconnects = await run.env.ble.disconnectCount
      #expect(next.device.id == first.device.id)
      #expect(next.generation != first.generation)
      run.reclaimLogs()

      #expect(throws: CancellationError.self) { try run.env.manager.requireAttemptOwnership(oldRequest, oldAttempt) }
      #expect(throws: CancellationError.self) { try run.env.manager.publish(first, repeatRanges: []) }

      let records = try await run.records(until: ["event=admissionRejected,", "event=publicationRejected,"])
      let expected = [
        "request=\(oldRequest.id)", "currentRequest=\(currentRequest.id)",
        "currentLink=\(next.generation)", "currentBinding=\(ObjectIdentifier(next.resource.binding))"
      ]
      for (event, reason) in [("admissionRejected", "supersededRequest"), ("publicationRejected", "requestOrBinding")] {
        #expect(records.contains { record in
          record.contains("event=\(event),") && record.contains("reason=\(reason)")
            && expected.allSatisfy { record.contains($0) }
        })
      }
      #expect(records.contains { $0.contains("event=publicationRejected,") && $0.contains("link=\(first.generation)") })
      #expect(run.env.manager.installedLink === next)
      #expect(run.env.manager.connectionState == .ready)
      #expect(try await next.resource.session.getBattery().level == 3700)
      #expect(await run.env.ble.connectCount == connects)
      #expect(await run.env.ble.disconnectCount == disconnects)
      expectAllowedFields(records)
    } catch { await run.close(); throw error }
    await run.close()
  }

  @Test(arguments: [CollectionPreference.absent, .storedFalse, .storedTrue])
  private func `lifecycle records survive the collection preference and restore it`(preference: CollectionPreference) async throws {
    let process = CollectionPreference.capture()
    defer { CollectionPreference.restore(process) }
    preference.install()
    let previousBuffer = DebugLogBuffer.shared
    let run = try Harness()
    do {
      _ = try await run.connect()
      let records = try await run.records(until: ["event=published,"])
      #expect(records.contains { $0.contains("event=published,") })
    } catch {
      await run.close()
      throw error
    }
    await run.close()
    #expect(DebugLogBuffer.shared === previousBuffer)
    preference.assertRestored()
  }

  @Test(arguments: [CollectionPreference.absent, .storedFalse, .storedTrue])
  private func `lifecycle records restore the collection preference after the test body throws`(
    preference: CollectionPreference
  ) async throws {
    let process = CollectionPreference.capture()
    defer { CollectionPreference.restore(process) }
    preference.install()
    let previousBuffer = DebugLogBuffer.shared
    let run = try Harness()
    do {
      _ = try await run.connect()
      let records = try await run.records(until: ["event=published,"])
      #expect(records.contains { $0.contains("event=published,") })
      throw BodyFailure()
    } catch is BodyFailure {
      await run.close()
    } catch {
      await run.close()
      throw error
    }
    #expect(DebugLogBuffer.shared === previousBuffer)
    preference.assertRestored()
  }

  private struct BodyFailure: Error {}

  private enum CollectionPreference: Sendable, CustomStringConvertible {
    case absent
    case storedFalse
    case storedTrue

    private static var key: String {
      AppStorageKey.collectDebugLogs.rawValue
    }

    var description: String {
      switch self {
      case .absent: "absent"
      case .storedFalse: "false"
      case .storedTrue: "true"
      }
    }

    func install() {
      switch self {
      case .absent: UserDefaults.standard.removeObject(forKey: Self.key)
      case .storedFalse: UserDefaults.standard.set(false, forKey: Self.key)
      case .storedTrue: UserDefaults.standard.set(true, forKey: Self.key)
      }
    }

    static func capture() -> Any? {
      UserDefaults.standard.object(forKey: key)
    }

    static func restore(_ value: Any?) {
      if let value {
        UserDefaults.standard.set(value, forKey: key)
      } else {
        UserDefaults.standard.removeObject(forKey: key)
      }
    }

    func assertRestored() {
      switch self {
      case .absent:
        #expect(UserDefaults.standard.object(forKey: Self.key) == nil)
      case .storedFalse:
        #expect(UserDefaults.standard.object(forKey: Self.key) != nil)
        #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
      case .storedTrue:
        #expect(UserDefaults.standard.bool(forKey: Self.key))
      }
    }
  }

  private func capturedFields(
    request: ConnectionRequest, attempt: ConnectionAttempt, link: ConnectionLink, origin: BLELifecycleOrigin
  ) -> [String] {
    ["request=\(request.id)", "attempt=\(ObjectIdentifier(attempt))", "link=\(link.generation)",
     "binding=\(ObjectIdentifier(link.resource.binding))", "session=\(ObjectIdentifier(link.resource.session))",
     "device=\(link.device.id.uuidString.prefix(8))", "physicalGeneration=\(origin.generation)"]
  }

  private func expectAllowedFields(_ records: [String]) {
    for record in records {
      for forbidden in ["payload=", "password=", "pin=", "secret=", "publicKey=", "host=", "ssid="] {
        #expect(!record.localizedCaseInsensitiveContains(forbidden))
      }
    }
  }

  @MainActor
  private final class Harness {
    let env: ConnectionManagerRadioTestHarness
    private let previousBuffer = DebugLogBuffer.shared
    private let capturedPreference: Any?
    private let bootstrap: DebugLogBuffer
    private var ownedBuffers: [DebugLogBuffer]
    private static let settleTimeout: Duration = .seconds(2)
    private static let pollInterval: Duration = .milliseconds(10)
    private static let logLimit = 2000

    init() throws {
      env = try ConnectionManagerRadioTestHarness()
      capturedPreference = CollectionPreference.capture()
      DebugLogBuffer.enableCollection()
      bootstrap = DebugLogBuffer(dataStore: env.manager.persistenceStore)
      ownedBuffers = [bootstrap]
      DebugLogBuffer.shared = bootstrap
    }

    func connect() async throws -> ConnectionLink {
      reclaimLogs()
      await env.manager.wireTransportHandlers()
      _ = try await env.connect(.bluetooth)
      let link = try #require(env.manager.installedLink)
      ownedBuffers.append(link.services.debugLogBuffer)
      #expect(env.manager.connectionState == .ready)
      return link
    }

    func reclaimLogs() {
      DebugLogBuffer.shared = bootstrap
    }

    func records(until markers: [String]) async throws -> [String] {
      let deadline = ContinuousClock.now + Self.settleTimeout
      var messages: [String] = []
      repeat {
        for buffer in ownedBuffers {
          await buffer.flush()
        }
        messages = try await env.manager.persistenceStore.fetchDebugLogEntries(since: .distantPast, limit: Self.logLimit)
          .map(\.message).filter { $0.contains("[ConnectionLifecycle]") }
        if markers.allSatisfy({ marker in messages.contains { $0.contains(marker) } }) { break }
        try await Task.sleep(for: Self.pollInterval)
      } while ContinuousClock.now < deadline
      return messages
    }

    func close() async {
      await env.close()
      for buffer in ownedBuffers {
        await buffer.shutdown()
      }
      if let current = DebugLogBuffer.shared, ownedBuffers.contains(where: { $0 === current }) {
        DebugLogBuffer.shared = previousBuffer
      }
      CollectionPreference.restore(capturedPreference)
    }
  }
}
