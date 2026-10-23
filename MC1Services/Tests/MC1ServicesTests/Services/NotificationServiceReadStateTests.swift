import Foundation
@testable import MC1Services
import Testing
import UserNotifications

@Suite("Notification read-state effects")
@MainActor
struct NotificationServiceReadStateTests {
  @Test(arguments: [AppStorageKey.notificationBadgeEnabled.rawValue, AppStorageKey.notifyContactMessages.rawValue])
  func `badge uses preferences changed while its count fetch is suspended`(keyName: String) async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    let arrived = Gate()
    let resume = Gate()
    fixture.recorder.countHook = {
      await arrived.open()
      await resume.wait()
    }
    let update = Task { await fixture.service.updateBadgeCount() }
    await arrived.wait()
    fixture.defaults.set(false, forKey: keyName)
    await resume.open()
    await update.value

    #expect(fixture.recorder.badges == [0])
    #expect(fixture.service.badgeCount == 0)
  }

  @Test
  func `superseded count fetch cannot overwrite a newer badge`() async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    let arrived = Gate()
    let resume = Gate()
    fixture.recorder.countHook = {
      await arrived.open()
      await resume.wait()
    }
    let old = Task { await fixture.service.updateBadgeCount() }
    await arrived.wait()
    fixture.recorder.countHook = nil
    fixture.recorder.count = 2
    await fixture.service.updateBadgeCount()
    await resume.open()
    await old.value

    #expect(fixture.recorder.badges == [2])
    #expect(fixture.service.badgeCount == 2)
  }

  @Test(arguments: [false, true])
  func `caller cancellation stops its badge task without cancelling a replacement`(replace: Bool) async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    let arrived = Gate()
    let resume = Gate()
    fixture.recorder.countHook = {
      await arrived.open()
      await resume.wait()
    }
    let old = Task { await fixture.service.updateBadgeCount() }
    await arrived.wait()

    let nextArrived = Gate()
    let nextResume = Gate()
    let next: Task<Void, Never>?
    if replace {
      fixture.recorder.count = 2
      fixture.recorder.countHook = {
        await nextArrived.open()
        await nextResume.wait()
      }
      next = Task { await fixture.service.updateBadgeCount() }
      await nextArrived.wait()
    } else {
      next = nil
    }
    old.cancel()
    await resume.open()
    await old.value
    #expect(fixture.recorder.badges.isEmpty)
    await nextResume.open()
    await next?.value

    #expect(fixture.recorder.badges == (replace ? [2] : []))
  }

  @Test(arguments: [false, true])
  func `lost ownership suppresses normal and disabled badge writes`(disabled: Bool) async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    await fixture.service.updateBadgeCount()
    #expect(fixture.recorder.badges == [7])
    fixture.recorder.count = 2
    if disabled { fixture.defaults.set(false, forKey: AppStorageKey.notificationBadgeEnabled.rawValue) }

    await fixture.service.updateBadgeCount(shouldPublish: { false })

    #expect(fixture.recorder.badges == [7])
    #expect(fixture.service.badgeCount == 7)
  }

  @Test
  func `channel matching preserves another radio and unrelated requests`() async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    let radioID = UUID()
    let content = UNMutableNotificationContent()
    content.userInfo = ["channelIndex": 1, "radioID": radioID.uuidString, "type": "reaction"]
    let other = UNMutableNotificationContent()
    other.userInfo = ["channelIndex": 1, "radioID": UUID().uuidString]
    let otherChannel = UNMutableNotificationContent()
    otherChannel.userInfo = ["channelIndex": 2, "radioID": radioID.uuidString]
    fixture.recorder.requests = [
      UNNotificationRequest(identifier: "matching", content: content, trigger: nil),
      UNNotificationRequest(identifier: "other-radio", content: other, trigger: nil),
      UNNotificationRequest(identifier: "other-channel", content: otherChannel, trigger: nil),
      UNNotificationRequest(identifier: "unrelated", content: UNMutableNotificationContent(), trigger: nil)
    ]

    await fixture.service.removeDeliveredNotifications(forChannelIndex: 1, radioID: radioID)

    #expect(fixture.recorder.removed == ["matching"])
    #expect(fixture.recorder.loads == 1)
  }

  @Test
  func `batched contact matching lists once and includes reaction requests`() async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    let ids = [UUID(), UUID()]
    fixture.recorder.requests = ids.enumerated().map { index, id in
      let content = UNMutableNotificationContent()
      content.userInfo = ["contactID": id.uuidString, "type": "reaction"]
      return UNNotificationRequest(identifier: "contact-\(index)", content: content, trigger: nil)
    }
    let outsider = UNMutableNotificationContent()
    outsider.userInfo = ["contactID": UUID().uuidString]
    fixture.recorder.requests.append(UNNotificationRequest(identifier: "outsider", content: outsider, trigger: nil))
    fixture.recorder.requests.append(UNNotificationRequest(identifier: "unrelated", content: UNMutableNotificationContent(), trigger: nil))

    await fixture.service.removeDeliveredNotifications(forContactIDs: Set(ids))

    #expect(fixture.recorder.removed == ["contact-0", "contact-1"])
    #expect(fixture.recorder.loads == 1)
  }

  @Test
  func `room matching and empty snapshots share cleanup behavior`() async throws {
    let fixture = try Fixture()
    defer { fixture.cleanup() }
    let sessionID = UUID()
    await fixture.service.removeDeliveredNotifications(forContactIDs: [])
    #expect(fixture.recorder.loads == 0)
    await fixture.service.removeDeliveredNotifications(forRoomSessionID: sessionID)
    #expect(fixture.recorder.loads == 1)
    #expect(fixture.recorder.removed.isEmpty)
    let content = UNMutableNotificationContent()
    content.userInfo = ["sessionID": sessionID.uuidString]
    let otherRoom = UNMutableNotificationContent()
    otherRoom.userInfo = ["sessionID": UUID().uuidString]
    fixture.recorder.requests = [
      UNNotificationRequest(identifier: "room", content: content, trigger: nil),
      UNNotificationRequest(identifier: "other-room", content: otherRoom, trigger: nil),
      UNNotificationRequest(identifier: "unrelated", content: UNMutableNotificationContent(), trigger: nil)
    ]

    await fixture.service.removeDeliveredNotifications(forRoomSessionID: sessionID)

    #expect(fixture.recorder.removed == ["room"])
    #expect(fixture.recorder.loads == 2)
  }

  @MainActor
  private struct Fixture {
    let suite = "NotificationReadState.\(UUID())"
    let defaults: UserDefaults
    let recorder = Recorder()
    let service: NotificationService

    init() throws {
      defaults = try #require(UserDefaults(suiteName: suite))
      for key in [AppStorageKey.notificationBadgeEnabled, .notifyContactMessages, .notifyChannelMessages, .notifyRoomMessages] {
        defaults.set(true, forKey: key.rawValue)
      }
      service = NotificationService(notificationDefaults: defaults, systemOperations: recorder.operations)
      service.getBadgeCount = { [recorder] in await recorder.counts() }
    }

    func cleanup() {
      defaults.removePersistentDomain(forName: suite)
    }
  }

  @MainActor
  private final class Recorder {
    var badges: [Int] = []
    var count = 7
    var requests: [UNNotificationRequest] = []
    var removed: [String] = []
    var loads = 0
    var countHook: (@MainActor () async -> Void)?

    func counts() async -> (contacts: Int, channels: Int, rooms: Int) {
      let captured = count
      await countHook?()
      return (captured, 0, 0)
    }

    var operations: NotificationService.SystemOperations {
      .init(
        setBadgeCount: { self.badges.append($0) },
        deliveredRequests: { self.loads += 1; return self.requests },
        removeDeliveredNotifications: { self.removed.append(contentsOf: $0) }
      )
    }
  }

  private actor Gate {
    private var waiter: CheckedContinuation<Void, Never>?
    private var isOpen = false

    func wait() async {
      if isOpen { return }
      await withCheckedContinuation { waiter = $0 }
    }

    func open() {
      isOpen = true
      waiter?.resume()
      waiter = nil
    }
  }
}
